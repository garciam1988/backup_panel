package app.coincidir.api.marketing.controller;

import app.coincidir.api.domain.BotConfig;
import app.coincidir.api.marketing.domain.LoyaltyCustomer;
import app.coincidir.api.marketing.domain.LoyaltyProgram;
import app.coincidir.api.marketing.dto.MarketingDtos;
import app.coincidir.api.marketing.dto.MarketingDtos.CardDto;
import app.coincidir.api.marketing.dto.MarketingDtos.CouponDto;
import app.coincidir.api.marketing.dto.MarketingDtos.CustomerDto;
import app.coincidir.api.marketing.dto.MarketingDtos.ProgramDto;
import app.coincidir.api.marketing.dto.MarketingDtos.PublicCardView;
import app.coincidir.api.marketing.dto.MarketingDtos.RewardDto;
import app.coincidir.api.marketing.dto.MarketingDtos.TransactionDto;
import app.coincidir.api.marketing.service.CouponService;
import app.coincidir.api.marketing.service.LoyaltyCardService;
import app.coincidir.api.marketing.service.LoyaltyCustomerService;
import app.coincidir.api.marketing.service.LoyaltyProgramService;
import app.coincidir.api.marketing.service.LoyaltyRedemptionService;
import app.coincidir.api.marketing.service.LoyaltyRewardService;
import app.coincidir.api.marketing.service.LoyaltyTransactionService;
import app.coincidir.api.marketing.service.WebPushService;
import app.coincidir.api.repository.BotConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PublicLoyaltyCardController — Vista pública de la tarjeta del cliente.
 *
 * Accesible desde la PWA usando solo el customer_hash en la URL. NO requiere
 * JWT. La seguridad se basa en que el hash es opaco (21 chars URL-safe,
 * espacio 64^21) y solo lo conoce el cliente.
 *
 * URL pública: GET /api/public/loyalty/card/{customerHash}
 *
 * Devuelve un PublicCardView consolidado con todo lo que la PWA necesita
 * para renderizar la pantalla principal en una sola llamada:
 *   - Config del programa (colores, métodos, etc).
 *   - Datos del cliente.
 *   - Estado actual de la tarjeta.
 *   - Premios disponibles (filtrados por vigencia y stock).
 *   - Últimas transacciones (para el feed).
 */
@Slf4j
@RestController
@RequestMapping("/api/public/loyalty")
@RequiredArgsConstructor
public class PublicLoyaltyCardController {

    private final LoyaltyCustomerService customerService;
    private final LoyaltyCardService cardService;
    private final LoyaltyProgramService programService;
    private final LoyaltyRewardService rewardService;
    private final LoyaltyTransactionService transactionService;
    private final LoyaltyRedemptionService redemptionService;
    private final CouponService couponService;
    private final WebPushService webPushService;
    private final BotConfigRepository botConfigRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * URL base del frontend de la PWA. Se usa como fallback en el manifest
     * cuando no llega header Origin/Referer (ej. cuando abrís el endpoint
     * directo en el navegador para debuggear). En operación normal, el
     * Referer del browser apunta al frontend y este valor no se usa.
     *
     * Configurable vía env var MARKETING_FRONTEND_URL en Railway.
     * Si no se configura, el manifest puede quedar con URLs relativas que
     * Chrome puede rechazar al instalar la PWA.
     */
    @org.springframework.beans.factory.annotation.Value("${marketing.frontend-url:}")
    private String configuredFrontendUrl;

    @GetMapping("/card/{customerHash}")
    public ResponseEntity<?> getCard(@PathVariable String customerHash) {
        return customerService.findByHash(customerHash).map(cust -> {
            var program = programService.getActiveProgram();
            var card = cardService.getOrCreate(cust);
            var allRewards = rewardService.listAvailableNow(program.getId());
            var recent = transactionService.recent(cust.getId());

            // Canjes del cliente (ordenados por más reciente).
            var allRedemptions = redemptionService.listForCustomer(cust.getId());

            // IDs de premios con canje "activo" — PENDING (esperando al mozo)
            // o REDEEMED (ya usado). Se ocultan de la lista de disponibles para
            // que el cliente no pueda volver a pedir el mismo premio. Aparecerán
            // en la sección "Mis canjes".
            var activeRewardIds = allRedemptions.stream()
                .filter(r -> r.getStatus() == app.coincidir.api.marketing.domain.LoyaltyRedemption.Status.PENDING
                          || r.getStatus() == app.coincidir.api.marketing.domain.LoyaltyRedemption.Status.REDEEMED)
                .map(app.coincidir.api.marketing.domain.LoyaltyRedemption::getRewardId)
                .collect(java.util.stream.Collectors.toSet());

            var availableRewards = allRewards.stream()
                .filter(r -> !activeRewardIds.contains(r.getId()))
                .map(RewardDto::fromEntity)
                .toList();

            // Pre-cargo los rewards en un map para evitar N+1 al armar el DTO.
            var rewardsById = allRewards.stream()
                .collect(java.util.stream.Collectors.toMap(
                    app.coincidir.api.marketing.domain.LoyaltyReward::getId, r -> r));
            // Pueden quedar redemptions cuyo reward fue dado de baja: lo buscamos
            // individualmente como fallback.
            var myRedemptions = allRedemptions.stream()
                .map(r -> {
                    var reward = rewardsById.get(r.getRewardId());
                    if (reward == null) {
                        reward = rewardService.findById(r.getRewardId()).orElse(null);
                    }
                    return MarketingDtos.RedemptionDto.fromEntity(r, reward);
                })
                .toList();

            return ResponseEntity.ok((Object) new PublicCardView(
                ProgramDto.fromEntity(program),
                CustomerDto.fromEntity(cust),
                CardDto.fromEntity(card),
                availableRewards,
                recent.stream().map(TransactionDto::fromEntity).toList(),
                myRedemptions
            ));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/card/{customerHash}/preferences")
    public ResponseEntity<?> updatePrefs(@PathVariable String customerHash,
                                         @RequestBody Map<String, Boolean> body) {
        try {
            LoyaltyCustomer updated = customerService.updateCommunicationPrefs(
                customerHash,
                body.get("acceptsWhatsapp"),
                body.get("acceptsEmail"),
                body.get("acceptsPush")
            );
            return ResponseEntity.ok(CustomerDto.fromEntity(updated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Lista los cupones activos vigentes que el cliente puede usar.
     *
     * Estrategia MVP: devolvemos todos los cupones activos en este momento
     * (filtrados por validFrom/validUntil) que NO sean SINGLE_USE_GLOBAL
     * ya quemados. La PWA los muestra como códigos copiables; el mozo los
     * aplica desde Staff y el backend valida ahí los límites por cliente.
     *
     * No hace falta verificar cuál cliente tiene asignado cuál cupón porque
     * los cupones son "globales" en este modelo — cualquier cliente con el
     * código puede usarlo (con sus límites). En el futuro se puede agregar
     * coupon_assignment para cupones personalizados.
     */
    @GetMapping("/card/{customerHash}/coupons")
    public ResponseEntity<?> getCoupons(@PathVariable String customerHash) {
        var custOpt = customerService.findByHash(customerHash);
        if (custOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        // Filtramos por cliente: los cupones de uso único que ya consumió no
        // aparecen más. Apenas el mozo aplica el cupón en su Staff App, el
        // próximo reload de la PWA del cliente lo va a omitir automáticamente.
        var active = couponService.listActiveNowForCustomer(custOpt.get().getId());
        return ResponseEntity.ok(active.stream().map(CouponDto::fromEntity).toList());
    }

    /**
     * Devuelve la clave pública VAPID del servidor. La PWA la necesita
     * para llamar a pushManager.subscribe({applicationServerKey}).
     *
     * Endpoint público (sin auth) porque la PWA del cliente lo consume
     * antes de que el cliente acepte push. Si VAPID no está configurado,
     * devuelve 503 para que la PWA muestre un mensaje claro.
     */
    @GetMapping("/vapid/public-key")
    public ResponseEntity<?> getVapidPublicKey() {
        if (!webPushService.isConfigured()) {
            return ResponseEntity.status(503).body(Map.of("error", "Web Push no configurado en este servidor"));
        }
        return ResponseEntity.ok(Map.of("publicKey", webPushService.getPublicKey()));
    }

    /**
     * Registra/actualiza la subscription de Web Push del cliente. Se
     * llama desde la PWA después de pushManager.subscribe() exitoso.
     *
     * Body: el JSON completo de la PushSubscription tal cual lo devuelve
     * el browser (formato estándar W3C: { endpoint, keys: { p256dh, auth } }).
     *
     * El backend guarda el JSON entero en loyalty_customer.web_push_subscription
     * y setea acceptsPush=true (porque si el cliente subscribió es porque
     * dio permiso explícito).
     */
    @PostMapping("/card/{customerHash}/push-subscription")
    public ResponseEntity<?> registerPushSubscription(@PathVariable String customerHash,
                                                      @RequestBody Map<String, Object> subscription) {
        try {
            String subscriptionJson = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(subscription);
            customerService.saveWebPushSubscription(customerHash, subscriptionJson);
            // Si el cliente subscribió es porque dio permiso explícito.
            // Actualizamos accepts_push=true para que las campañas le manden.
            customerService.updateCommunicationPrefs(customerHash, null, null, true);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Borra la subscription (cliente revocó permiso desde el navegador). */
    @DeleteMapping("/card/{customerHash}/push-subscription")
    public ResponseEntity<?> deletePushSubscription(@PathVariable String customerHash) {
        try {
            customerService.saveWebPushSubscription(customerHash, null);
            customerService.updateCommunicationPrefs(customerHash, null, null, false);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Manifest PWA dinámico, customizado por cliente del programa.
     *
     * Cuando el cliente final abre /c/{hash}, el index.html inyecta este
     * link como manifest. Al instalar la PWA, el navegador usa estos valores:
     *   - name / short_name: nombre del programa (ej: "Mikhuna Nikkei")
     *   - icons: el logoUrl del cardDesignJson (no íconos genéricos)
     *   - start_url: /c/{hash} → al abrir el ícono se carga LA TARJETA del
     *     cliente, no el bot ni la raíz del dominio.
     *   - theme_color: el color primario del diseño
     *
     * Servimos con content-type "application/manifest+json" como pide W3C.
     * NO cacheamos en CDN agresivamente porque el admin puede cambiar el
     * branding y queremos que se refleje pronto.
     *
     * Si el hash no existe, devolvemos 404 (igual que la card view).
     */
    @GetMapping(value = "/card/{customerHash}/manifest.json")
    public ResponseEntity<?> getManifest(
        @PathVariable String customerHash,
        @RequestHeader(value = "Origin", required = false) String originHeader,
        @RequestHeader(value = "Referer", required = false) String refererHeader
    ) {
        return customerService.findByHash(customerHash).map(cust -> {
            LoyaltyProgram program = programService.getActiveProgram();

            // BotConfig singleton (id=1) — fuente de fallback para branding.
            // Muchos clientes ya tienen el logo y el nombre cargados en /admin
            // del bot, así que reutilizamos eso si el módulo Marketing todavía
            // no lo tiene customizado.
            BotConfig botConfig = botConfigRepository.findById(1L).orElse(null);

            // Defaults
            String name = resolveName(program, botConfig);
            String shortName = shortenName(name);
            String themeColor = "#1D3557";
            String backgroundColor = "#FFFFFF";
            String logoUrl = null;

            // Determinar el origen del FRONTEND que llamó. Como backend y frontend
            // están en dominios distintos en Railway, necesitamos que las URLs
            // del manifest (start_url, scope) apunten al frontend para que la
            // PWA se instale "como app del frontend". Si tomáramos URLs relativas,
            // se instalaría como app del backend (api.xxx.railway.app) que no
            // tiene UI.
            //
            // Orden de preferencia: Origin > Referer > fallback hardcoded.
            String frontendOrigin = resolveFrontendOrigin(originHeader, refererHeader);

            // Parseamos cardDesignJson si está presente
            try {
                if (program.getCardDesignJson() != null && !program.getCardDesignJson().isBlank()) {
                    JsonNode cd = objectMapper.readTree(program.getCardDesignJson());
                    if (cd.hasNonNull("secondaryColor")) {
                        themeColor = cd.get("secondaryColor").asText(themeColor);
                    }
                    if (cd.hasNonNull("logoUrl")) {
                        String raw = cd.get("logoUrl").asText("").trim();
                        if (!raw.isEmpty()) logoUrl = raw;
                    }
                }
            } catch (Exception e) {
                log.warn("[Manifest] error parseando cardDesignJson para customerHash={}: {}",
                    customerHash, e.getMessage());
            }

            // Fallback: si no hay logo en cardDesign, usar el del bot_config.
            // El admin generalmente lo carga ahí PRIMERO (es la config inicial
            // del cliente) así que es muy probable que esté disponible.
            // Aceptamos URLs http(s) y también data:image base64 (que es como
            // BotConfig a veces los guarda); en ese caso lo serviríamos
            // embebido en el manifest — funciona pero hace el manifest pesado,
            // ideal sería tener URL http(s). Si es data: largo, lo descartamos
            // porque el manifest tiene límites de tamaño en algunos browsers.
            if (logoUrl == null && botConfig != null && botConfig.getLogoUrl() != null) {
                String botLogo = botConfig.getLogoUrl().trim();
                if (botLogo.startsWith("http://") || botLogo.startsWith("https://")) {
                    logoUrl = botLogo;
                } else if (botLogo.startsWith("data:image/") && botLogo.length() < 100_000) {
                    // data:image embebido razonablemente chico (< ~100KB base64)
                    logoUrl = botLogo;
                } else {
                    log.debug("[Manifest] bot_config.logo_url no es URL http(s) ni data:image chico, se descarta");
                }
            }

            // Armar la lista de icons. Si hay logoUrl del cliente, lo usamos
            // como ícono principal en 2 tamaños declarados (any). Los
            // navegadores se encargan de reescalar para los launchers.
            // Si no hay logo, fallback a los íconos genéricos /icon-192.png
            // y /icon-512.png que ya existen como respaldo.
            List<Map<String, Object>> icons = new java.util.ArrayList<>();
            if (logoUrl != null) {
                icons.add(Map.of(
                    "src", logoUrl,
                    "sizes", "192x192",
                    "type", guessImageMime(logoUrl),
                    "purpose", "any"
                ));
                icons.add(Map.of(
                    "src", logoUrl,
                    "sizes", "512x512",
                    "type", guessImageMime(logoUrl),
                    "purpose", "any"
                ));
                // Maskable: necesita padding, sigue siendo el mismo asset pero
                // declarado distinto para Android adaptive icons. Por ahora
                // reusamos el mismo (el ideal sería tener una versión maskable
                // dedicada por cliente, pero alcanza para no tener placeholder).
                icons.add(Map.of(
                    "src", logoUrl,
                    "sizes", "512x512",
                    "type", guessImageMime(logoUrl),
                    "purpose", "maskable"
                ));
            } else {
                icons.add(Map.of(
                    "src", frontendOrigin + "/icon-192.png",
                    "sizes", "192x192",
                    "type", "image/png",
                    "purpose", "any maskable"
                ));
                icons.add(Map.of(
                    "src", frontendOrigin + "/icon-512.png",
                    "sizes", "512x512",
                    "type", "image/png",
                    "purpose", "any maskable"
                ));
            }

            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("name", name);
            manifest.put("short_name", shortName);
            manifest.put("description", "Tarjeta de fidelización digital");
            // start_url y scope son URLs ABSOLUTAS al frontend (no relativas)
            // para que la PWA quede asociada al dominio del frontend (no al backend).
            manifest.put("start_url", frontendOrigin + "/c/" + customerHash);
            manifest.put("scope", frontendOrigin + "/c/");
            manifest.put("display", "standalone");
            manifest.put("orientation", "portrait");
            manifest.put("background_color", backgroundColor);
            manifest.put("theme_color", themeColor);
            manifest.put("icons", icons);
            manifest.put("categories", List.of("business", "lifestyle"));

            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/manifest+json"))
                .header("Cache-Control", "public, max-age=300") // 5min — refresco rápido si cambian branding
                .header("Access-Control-Allow-Origin", frontendOrigin) // CORS: el manifest se carga desde frontend
                .body((Object) manifest);
        }).orElse(ResponseEntity.notFound().build());
    }

    /**
     * Determina el origen del frontend que está pidiendo el manifest.
     * Orden: Origin > Referer > MARKETING_FRONTEND_URL env > vacío.
     *
     * Devuelve algo como "https://bot-testing-def4.up.railway.app" sin el path.
     */
    private String resolveFrontendOrigin(String originHeader, String refererHeader) {
        // 1) Header Origin (presente en CORS)
        if (originHeader != null && !originHeader.isBlank() && !originHeader.equals("null")) {
            return originHeader.trim();
        }
        // 2) Header Referer — parsear solo scheme+host
        if (refererHeader != null && !refererHeader.isBlank()) {
            try {
                java.net.URI uri = java.net.URI.create(refererHeader.trim());
                if (uri.getScheme() != null && uri.getHost() != null) {
                    String origin = uri.getScheme() + "://" + uri.getHost();
                    if (uri.getPort() > 0 && uri.getPort() != 80 && uri.getPort() != 443) {
                        origin += ":" + uri.getPort();
                    }
                    return origin;
                }
            } catch (Exception e) {
                log.warn("[Manifest] Referer header inválido: {}", refererHeader);
            }
        }
        // 3) Fallback: env var MARKETING_FRONTEND_URL configurada en Railway.
        //    Útil para debug (abrir el endpoint directo) y para casos edge
        //    donde el browser no manda Origin ni Referer.
        if (configuredFrontendUrl != null && !configuredFrontendUrl.isBlank()) {
            String trimmed = configuredFrontendUrl.trim();
            // Quitar trailing slash si lo tiene
            if (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
            return trimmed;
        }
        // 4) Último recurso: cadena vacía. start_url quedaría relativo, lo
        //    que puede romper la instalación de la PWA en Chrome moderno.
        return "";
    }

    /**
     * Resuelve el nombre que va a aparecer en la PWA instalada.
     * Prioridad:
     *   1) loyalty_program.name si NO es el default genérico
     *   2) bot_config.brand_name si está cargado
     *   3) bot_config.bot_name como fallback
     *   4) String genérico "Tarjeta digital" si nada está cargado
     *
     * "Programa de Fidelización" es el name por default que crea el módulo
     * al inicializar, así que lo tratamos como "no configurado" y caemos al
     * brandName del bot.
     */
    private String resolveName(LoyaltyProgram program, BotConfig botConfig) {
        String programName = program.getName();
        boolean isDefault = programName == null
            || programName.isBlank()
            || "Programa de Fidelización".equalsIgnoreCase(programName.trim())
            || "Programa de fidelización".equalsIgnoreCase(programName.trim());

        if (!isDefault) {
            return programName.trim();
        }

        if (botConfig != null) {
            if (botConfig.getBrandName() != null && !botConfig.getBrandName().isBlank()) {
                return botConfig.getBrandName().trim();
            }
            if (botConfig.getBotName() != null && !botConfig.getBotName().isBlank()) {
                return botConfig.getBotName().trim();
            }
        }
        return programName != null && !programName.isBlank() ? programName : "Tarjeta digital";
    }

    /** Acorta nombre largo para short_name (max 12 chars recomendado por W3C). */
    private String shortenName(String name) {
        if (name == null) return "Tarjeta";
        String trimmed = name.trim();
        if (trimmed.length() <= 12) return trimmed;
        // Tomar primera palabra; si excede igual, cortar
        String firstWord = trimmed.split("\\s+")[0];
        if (firstWord.length() <= 12) return firstWord;
        return firstWord.substring(0, 12);
    }

    /** Detecta mime type por URL o data URI, fallback a png. */
    private String guessImageMime(String url) {
        if (url == null) return "image/png";
        // data:image/png;base64,... → extraer el mime después de "data:"
        if (url.startsWith("data:")) {
            int semi = url.indexOf(';');
            if (semi > 5) {
                return url.substring(5, semi); // "image/png" / "image/jpeg" / etc.
            }
            return "image/png";
        }
        String lower = url.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".gif")) return "image/gif";
        return "image/png";
    }
}
