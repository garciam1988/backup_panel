package app.coincidir.api.marketing.service;

import app.coincidir.api.domain.BotConfig;
import app.coincidir.api.marketing.domain.Coupon;
import app.coincidir.api.marketing.domain.LoyaltyCard;
import app.coincidir.api.marketing.domain.LoyaltyCustomer;
import app.coincidir.api.marketing.domain.LoyaltyProgram;
import app.coincidir.api.marketing.domain.LoyaltyRedemption;
import app.coincidir.api.marketing.domain.LoyaltyReward;
import app.coincidir.api.marketing.domain.MarketingCampaign;
import app.coincidir.api.repository.BotConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * LoyaltyBotToolsService — Tools del módulo Marketing expuestas al bot
 * conversacional.
 *
 * Análogo a BotTableService.buildToolsForBot(), pero para funciones nativas
 * (no BotTables ni SQL libre). Cada tool acá es un método Java que opera
 * sobre los services del módulo Marketing.
 *
 * Tools disponibles:
 *   - get_loyalty_status       Consulta el estado de la tarjeta de un cliente
 *   - enroll_customer          Enrola un cliente nuevo (o reactiva existente)
 *   - list_rewards             Lista premios disponibles actualmente
 *   - request_redemption       Solicita un canje y devuelve el código
 *   - get_active_coupons       Lista cupones activos
 *   - get_active_campaigns     Lista campañas activas (para auto-promo)
 *   - apply_coupon             Aplica un cupón (solo desde staff con context)
 *
 * Activación:
 *   - Si bot_config.marketing_enabled = false → no expone ninguna tool.
 *   - Si está activo y bot_config.marketing_config_json.exposedTools tiene un
 *     array, expone SOLO esas tools. Si no, expone todas.
 *
 * Identificación del cliente:
 *   Las tools que operan sobre un cliente aceptan customer_hash O phone.
 *   El bot debería preferir customer_hash si ya conoció al cliente en la
 *   sesión; si no, usa el phone que el usuario le compartió.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoyaltyBotToolsService {

    private final LoyaltyProgramService programService;
    private final LoyaltyCustomerService customerService;
    private final LoyaltyCardService cardService;
    private final LoyaltyRewardService rewardService;
    private final LoyaltyRedemptionService redemptionService;
    private final LoyaltyTransactionService transactionService;
    private final CouponService couponService;
    private final MarketingCampaignService campaignService;
    private final BotConfigRepository botConfigRepo;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${marketing.pwa-base-url:}")
    private String pwaBaseUrl;

    public static class ToolDef {
        public String name;
        public String description;
        public JsonNode inputSchema;
    }

    public static class ToolResult {
        public boolean ok;
        public String output;
        public ToolResult(boolean ok, String output) { this.ok = ok; this.output = output; }
        public static ToolResult ok(String s)    { return new ToolResult(true, s); }
        public static ToolResult error(String s) { return new ToolResult(false, s); }
    }

    /**
     * Devuelve las tools que el bot puede usar para Marketing.
     * Si el módulo está apagado o no hay programa activo, devuelve lista vacía.
     */
    public List<ToolDef> buildToolsForBot() {
        Optional<BotConfig> cfg = botConfigRepo.findById(1L);
        if (cfg.isEmpty() || !Boolean.TRUE.equals(cfg.get().getMarketingEnabled())) return List.of();

        Set<String> exposed = parseExposedTools(cfg.get().getMarketingConfigJson());

        List<ToolDef> all = allTools();
        if (exposed == null || exposed.isEmpty()) return all;
        return all.stream().filter(t -> exposed.contains(t.name)).toList();
    }

    private Set<String> parseExposedTools(String configJson) {
        if (configJson == null || configJson.isBlank()) return null;
        try {
            JsonNode n = objectMapper.readTree(configJson);
            JsonNode arr = n.path("exposedTools");
            if (!arr.isArray()) return null;
            Set<String> out = new HashSet<>();
            arr.forEach(x -> out.add(x.asText()));
            return out;
        } catch (Exception e) {
            log.warn("marketing_config_json mal formado: {}", e.getMessage());
            return null;
        }
    }

    private List<ToolDef> allTools() {
        LoyaltyProgram program = programService.getActiveProgram();
        String programDesc = describeProgram(program);

        List<ToolDef> tools = new ArrayList<>();

        tools.add(build("get_loyalty_status",
            "Consulta el estado de la tarjeta de fidelización de un cliente. " +
            programDesc +
            " Pasá customer_hash (si ya lo conocés de la sesión) o phone. Devuelve estampillas/puntos/cashback actuales " +
            "y cuántas estampillas le faltan para el próximo premio.",
            "{\"type\":\"object\",\"properties\":{" +
            "\"customer_hash\":{\"type\":\"string\",\"description\":\"Hash del cliente en la URL de su tarjeta\"}," +
            "\"phone\":{\"type\":\"string\",\"description\":\"Teléfono del cliente con o sin prefijo internacional\"}" +
            "}}"));

        tools.add(build("enroll_customer",
            "Enrola un cliente nuevo al programa de fidelización (o reactiva si ya existía). " +
            "Devuelve el customer_hash y la URL pública de la tarjeta del cliente para mandársela. " +
            "El phone es obligatorio; first_name también. Si el cliente ya existe con ese teléfono, " +
            "lo reactiva y completa los campos faltantes con los nuevos datos.",
            "{\"type\":\"object\",\"required\":[\"phone\",\"first_name\"],\"properties\":{" +
            "\"phone\":{\"type\":\"string\"}," +
            "\"first_name\":{\"type\":\"string\"}," +
            "\"last_name\":{\"type\":\"string\"}," +
            "\"email\":{\"type\":\"string\"}," +
            "\"birth_date\":{\"type\":\"string\",\"description\":\"Formato YYYY-MM-DD\"}," +
            "\"branch_id\":{\"type\":\"string\",\"description\":\"Sucursal donde se enrola (opcional)\"}" +
            "}}"));

        tools.add(build("list_rewards",
            "Lista los premios canjeables disponibles AHORA (filtrados por vigencia, stock y horarios). " +
            "Usalo cuando el cliente pregunta qué puede canjear con sus estampillas/puntos. " +
            "Devuelve nombre, descripción y costo de cada premio. Opcionalmente filtrá por sucursal.",
            "{\"type\":\"object\",\"properties\":{" +
            "\"branch_id\":{\"type\":\"string\"}" +
            "}}"));

        tools.add(build("request_redemption",
            "Solicita canjear un premio para un cliente. Devuelve un código corto (redemption_code) " +
            "que el cliente tiene que mostrarle al mozo en el local. El código vence en 24hs. " +
            "Valida que el cliente tenga saldo, que el premio esté vigente y haya stock. " +
            "Si falla, devuelve el motivo (ej: 'te faltan 3 estampillas').",
            "{\"type\":\"object\",\"required\":[\"reward_id\"],\"properties\":{" +
            "\"customer_hash\":{\"type\":\"string\"}," +
            "\"phone\":{\"type\":\"string\"}," +
            "\"reward_id\":{\"type\":\"integer\"}," +
            "\"branch_id\":{\"type\":\"string\"}" +
            "}}"));

        tools.add(build("get_active_coupons",
            "Lista los cupones de descuento activos para promocionarle al cliente. " +
            "Devuelve código, descripción del descuento y vigencia.",
            "{\"type\":\"object\",\"properties\":{}}"));

        tools.add(build("get_active_campaigns",
            "Lista campañas de marketing actualmente en ejecución. Útil para que el bot mencione promos " +
            "del momento al cliente. Solo expone campañas con status RUNNING y con mensaje de WhatsApp definido.",
            "{\"type\":\"object\",\"properties\":{}}"));

        tools.add(build("apply_coupon",
            "Aplica un cupón sobre una compra de un cliente. SOLO uso en flujo de checkout (post-compra real, " +
            "no para validar antes). Registra el uso e incrementa el contador del cupón. " +
            "Devuelve el monto de descuento aplicado o el motivo del rechazo.",
            "{\"type\":\"object\",\"required\":[\"code\",\"purchase_amount\"],\"properties\":{" +
            "\"code\":{\"type\":\"string\"}," +
            "\"customer_hash\":{\"type\":\"string\"}," +
            "\"phone\":{\"type\":\"string\"}," +
            "\"purchase_amount\":{\"type\":\"number\"}," +
            "\"branch_id\":{\"type\":\"string\"}" +
            "}}"));

        return tools;
    }

    private ToolDef build(String name, String desc, String schemaJson) {
        ToolDef t = new ToolDef();
        t.name = name;
        t.description = desc;
        try { t.inputSchema = objectMapper.readTree(schemaJson); }
        catch (Exception e) { t.inputSchema = objectMapper.createObjectNode(); }
        return t;
    }

    private String describeProgram(LoyaltyProgram p) {
        StringBuilder sb = new StringBuilder("El programa actual usa: ");
        boolean any = false;
        if (Boolean.TRUE.equals(p.getStampsEnabled())) {
            sb.append("ESTAMPILLAS");
            if (p.getStampsRequired() != null)
                sb.append(" (").append(p.getStampsRequired()).append(" para el premio principal)");
            any = true;
        }
        if (Boolean.TRUE.equals(p.getPointsEnabled())) {
            if (any) sb.append(", ");
            sb.append("PUNTOS"); any = true;
        }
        if (Boolean.TRUE.equals(p.getCashbackEnabled())) {
            if (any) sb.append(", ");
            sb.append("CASHBACK"); any = true;
        }
        sb.append(".");
        return sb.toString();
    }

    // ── Ejecución ────────────────────────────────────────────────────────

    /** Despacha la tool por nombre. */
    @Transactional
    public ToolResult execute(String toolName, JsonNode args) {
        try {
            return switch (toolName) {
                case "get_loyalty_status"   -> getLoyaltyStatus(args);
                case "enroll_customer"      -> enrollCustomer(args);
                case "list_rewards"         -> listRewards(args);
                case "request_redemption"   -> requestRedemption(args);
                case "get_active_coupons"   -> getActiveCoupons();
                case "get_active_campaigns" -> getActiveCampaigns();
                case "apply_coupon"         -> applyCoupon(args);
                default -> ToolResult.error("Tool no reconocida: " + toolName);
            };
        } catch (Exception e) {
            log.warn("Error ejecutando tool {}: {}", toolName, e.getMessage());
            return ToolResult.error("Error ejecutando " + toolName + ": " + e.getMessage());
        }
    }

    // ── Implementación de cada tool ──────────────────────────────────────

    private ToolResult getLoyaltyStatus(JsonNode args) {
        LoyaltyCustomer c = resolveCustomer(args);
        if (c == null) return ToolResult.error("Cliente no encontrado. Pasá customer_hash o phone válido, o enrolá primero al cliente.");

        LoyaltyProgram program = programService.getActiveProgram();
        LoyaltyCard card = cardService.getOrCreate(c);

        StringBuilder sb = new StringBuilder();
        sb.append("Cliente: ").append(c.getFirstName());
        if (c.getLastName() != null && !c.getLastName().isBlank())
            sb.append(" ").append(c.getLastName());
        sb.append("\n");

        if (Boolean.TRUE.equals(program.getStampsEnabled())) {
            sb.append("Estampillas: ").append(card.getCurrentStamps());
            if (program.getStampsRequired() != null) {
                int falta = Math.max(0, program.getStampsRequired() - card.getCurrentStamps());
                if (falta == 0) sb.append(" — ¡ya alcanzó el premio principal!");
                else sb.append(" (le faltan ").append(falta).append(" para ");
                if (falta > 0) {
                    sb.append(program.getStampsRewardText() != null
                        ? program.getStampsRewardText() : "el premio");
                    sb.append(")");
                }
            }
            sb.append("\n");
        }
        if (Boolean.TRUE.equals(program.getPointsEnabled())) {
            sb.append("Puntos: ").append(card.getCurrentPoints()).append("\n");
        }
        if (Boolean.TRUE.equals(program.getCashbackEnabled())) {
            sb.append("Cashback disponible: $").append(card.getCashbackBalance()).append("\n");
        }
        sb.append("URL de su tarjeta: ").append(buildPwaUrl(c.getCustomerHash())).append("\n");
        sb.append("customer_hash: ").append(c.getCustomerHash());
        return ToolResult.ok(sb.toString());
    }

    private ToolResult enrollCustomer(JsonNode args) {
        String phone = textOrNull(args, "phone");
        String firstName = textOrNull(args, "first_name");
        if (phone == null || firstName == null)
            return ToolResult.error("phone y first_name son obligatorios para enrolar.");

        LocalDate birth = null;
        String bd = textOrNull(args, "birth_date");
        if (bd != null && !bd.isBlank()) {
            try { birth = LocalDate.parse(bd); }
            catch (Exception e) { return ToolResult.error("birth_date inválido. Usá formato YYYY-MM-DD."); }
        }

        var res = customerService.enrollOrReactivate(new LoyaltyCustomerService.EnrollInput(
            phone, firstName,
            textOrNull(args, "last_name"),
            textOrNull(args, "email"),
            birth,
            textOrNull(args, "branch_id"),
            "bot",
            null, null
        ));

        StringBuilder sb = new StringBuilder();
        if (res.alreadyExisted()) {
            sb.append("Cliente ya estaba enrolado. ");
        } else {
            sb.append("Cliente enrolado correctamente. ");
        }
        sb.append("customer_hash: ").append(res.customer().getCustomerHash()).append("\n");
        sb.append("URL de su tarjeta: ").append(buildPwaUrl(res.customer().getCustomerHash())).append("\n");
        sb.append("Mandale este link al cliente para que vea su tarjeta y arme su PWA en el celular.");
        return ToolResult.ok(sb.toString());
    }

    private ToolResult listRewards(JsonNode args) {
        LoyaltyProgram program = programService.getActiveProgram();
        List<LoyaltyReward> rewards = rewardService.listAvailableNow(program.getId());
        if (rewards.isEmpty()) {
            return ToolResult.ok("No hay premios disponibles en este momento.");
        }

        StringBuilder sb = new StringBuilder("Premios disponibles:\n");
        for (LoyaltyReward r : rewards) {
            sb.append("- [id=").append(r.getId()).append("] ").append(r.getName());
            if (r.getDescription() != null && !r.getDescription().isBlank())
                sb.append(" — ").append(r.getDescription());
            sb.append(" — cuesta ");
            switch (r.getRewardType()) {
                case STAMPS   -> sb.append(r.getCostStamps()).append(" estampillas");
                case POINTS   -> sb.append(r.getCostPoints()).append(" puntos");
                case CASHBACK -> sb.append("$").append(r.getCostCashback()).append(" de cashback");
                case FREE     -> sb.append("GRATIS");
            }
            if (r.getStockRemaining() != null) sb.append(" (stock: ").append(r.getStockRemaining()).append(")");
            sb.append("\n");
        }
        return ToolResult.ok(sb.toString());
    }

    private ToolResult requestRedemption(JsonNode args) {
        LoyaltyCustomer c = resolveCustomer(args);
        if (c == null) return ToolResult.error("Cliente no encontrado. Pasá customer_hash o phone, o enrolá primero.");

        if (!args.has("reward_id")) return ToolResult.error("reward_id es obligatorio.");
        Long rewardId = args.get("reward_id").asLong();
        String branchId = textOrNull(args, "branch_id");

        var result = redemptionService.request(c.getId(), rewardId, branchId);
        if (!result.accepted()) {
            return ToolResult.error("No se pudo canjear: " + result.reasonIfRejected());
        }
        var r = result.redemption();
        String rewardName = rewardService.findById(rewardId).map(LoyaltyReward::getName).orElse("el premio");
        StringBuilder sb = new StringBuilder();
        sb.append("¡Canje generado! Mostrale este código al mozo en el local:\n\n");
        sb.append("🎟️  CÓDIGO: ").append(r.getRedemptionCode()).append("\n");
        sb.append("Premio: ").append(rewardName).append("\n");
        sb.append("Vence: ").append(r.getExpiresAt()).append("\n");
        sb.append("Si no usa el código antes de esa fecha, las estampillas/puntos se le devuelven automáticamente.");
        return ToolResult.ok(sb.toString());
    }

    private ToolResult getActiveCoupons() {
        List<Coupon> active = couponService.list(
            org.springframework.data.domain.PageRequest.of(0, 50)
        ).getContent().stream()
            .filter(c -> Boolean.TRUE.equals(c.getActive()))
            .filter(c -> c.getValidUntil() == null || c.getValidUntil().isAfter(Instant.now()))
            .filter(c -> c.getMaxUsesTotal() == null || c.getCurrentUses() < c.getMaxUsesTotal())
            .toList();

        if (active.isEmpty()) return ToolResult.ok("No hay cupones activos en este momento.");

        StringBuilder sb = new StringBuilder("Cupones activos:\n");
        for (Coupon c : active) {
            sb.append("- ").append(c.getCode()).append(": ").append(c.getName());
            if (c.getDescription() != null && !c.getDescription().isBlank())
                sb.append(" — ").append(c.getDescription());
            sb.append(" (descuento: ").append(describeDiscount(c)).append(")");
            if (c.getMinPurchase() != null)
                sb.append(" [mín compra $").append(c.getMinPurchase()).append("]");
            sb.append("\n");
        }
        return ToolResult.ok(sb.toString());
    }

    private String describeDiscount(Coupon c) {
        return switch (c.getDiscountType()) {
            case PERCENTAGE -> c.getDiscountValue() + "%";
            case FIXED      -> "$" + c.getDiscountValue();
            case FREE_ITEM  -> "ítem gratis: " + c.getFreeItemRef();
            case BOGO       -> "2x1: " + c.getFreeItemRef();
        };
    }

    private ToolResult getActiveCampaigns() {
        var running = campaignService.listRecent(20).getContent().stream()
            .filter(c -> c.getStatus() == MarketingCampaign.Status.RUNNING)
            .filter(c -> c.getMessageWhatsapp() != null && !c.getMessageWhatsapp().isBlank())
            .toList();

        if (running.isEmpty()) return ToolResult.ok("No hay campañas activas en este momento.");

        StringBuilder sb = new StringBuilder("Campañas activas:\n");
        for (var c : running) {
            sb.append("- ").append(c.getName()).append(": ").append(c.getMessageWhatsapp());
            if (c.getCtaUrl() != null && !c.getCtaUrl().isBlank())
                sb.append(" (link: ").append(c.getCtaUrl()).append(")");
            sb.append("\n");
        }
        return ToolResult.ok(sb.toString());
    }

    private ToolResult applyCoupon(JsonNode args) {
        String code = textOrNull(args, "code");
        if (code == null) return ToolResult.error("code es obligatorio.");

        LoyaltyCustomer c = resolveCustomer(args);
        if (c == null) return ToolResult.error("Cliente no encontrado para aplicar el cupón.");

        BigDecimal amount = args.has("purchase_amount")
            ? BigDecimal.valueOf(args.get("purchase_amount").asDouble())
            : null;
        if (amount == null || amount.signum() <= 0)
            return ToolResult.error("purchase_amount es obligatorio y debe ser positivo.");

        var result = couponService.apply(code, c.getId(), amount, textOrNull(args, "branch_id"), "bot");
        if (!result.accepted())
            return ToolResult.error("Cupón rechazado: " + result.reasonIfRejected());
        return ToolResult.ok("Cupón aplicado. Descuento: $" + result.discountApplied() +
            " sobre una compra de $" + amount + ".");
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private LoyaltyCustomer resolveCustomer(JsonNode args) {
        String hash = textOrNull(args, "customer_hash");
        if (hash != null) return customerService.findByHash(hash).orElse(null);
        String phone = textOrNull(args, "phone");
        if (phone != null) return customerService.findByPhone(phone).orElse(null);
        return null;
    }

    private String textOrNull(JsonNode args, String field) {
        if (args == null || !args.has(field) || args.get(field).isNull()) return null;
        String s = args.get(field).asText();
        return s.isBlank() ? null : s;
    }

    private String buildPwaUrl(String hash) {
        if (pwaBaseUrl == null || pwaBaseUrl.isBlank()) return "/c/" + hash;
        return pwaBaseUrl.replaceAll("/+$", "") + "/c/" + hash;
    }
}
