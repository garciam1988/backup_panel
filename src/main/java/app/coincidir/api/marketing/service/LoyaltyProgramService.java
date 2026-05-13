package app.coincidir.api.marketing.service;

import app.coincidir.api.marketing.domain.LoyaltyProgram;
import app.coincidir.api.marketing.repository.LoyaltyProgramRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * LoyaltyProgramService — Lógica del programa singleton.
 *
 * En el MVP siempre hay UN programa con id=1 (seed inicial). Toda la lógica
 * del módulo Marketing pasa por getActiveProgram() para obtenerlo. Si por
 * alguna razón no existe (seed no corrió), getOrCreateDefault() lo crea.
 *
 * El programa tiene tres flags (stamps/points/cashback) que pueden coexistir.
 * Las validaciones de "está habilitado este tipo" se hacen leyendo estos
 * flags en cada operación de earn/redeem.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoyaltyProgramService {

    private final LoyaltyProgramRepository programRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Set<String> VALID_ID_METHODS = Set.of("customer_qr", "local_qr", "phone");

    public LoyaltyProgram getActiveProgram() {
        return programRepo.findFirstByActiveTrueOrderByIdAsc()
            .orElseGet(this::getOrCreateDefault);
    }

    /** Idempotente: si no hay programa, crea el default. */
    @Transactional
    public LoyaltyProgram getOrCreateDefault() {
        Optional<LoyaltyProgram> existing = programRepo.findById(1L);
        if (existing.isPresent()) return existing.get();

        LoyaltyProgram p = new LoyaltyProgram();
        p.setName("Programa de Fidelización");
        p.setDescription("Configurá los tipos de programa desde /marketing.");
        p.setStampsEnabled(true);
        p.setPointsEnabled(false);
        p.setCashbackEnabled(false);
        p.setStampsRequired(10);
        p.setStampsRewardText("Premio principal");
        p.setStampsResetOnRedeem(true);
        p.setIdentificationMethods("[\"customer_qr\",\"local_qr\",\"phone\"]");
        p.setMultiBranchMode(LoyaltyProgram.MultiBranchMode.GLOBAL_WITH_TRACKING);
        p.setCardDesignJson("{\"primaryColor\":\"#E63946\",\"secondaryColor\":\"#1D3557\",\"showQrOnCard\":true,\"quickActions\":[\"reserve\",\"menu\",\"rewards\",\"promos\"]}");
        p.setActive(true);
        return programRepo.save(p);
    }

    @Transactional
    public LoyaltyProgram update(Long id, LoyaltyProgram update) {
        LoyaltyProgram p = programRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Programa no encontrado: " + id));

        if (update.getName() != null) p.setName(update.getName());
        if (update.getDescription() != null) p.setDescription(update.getDescription());

        if (update.getStampsEnabled() != null)   p.setStampsEnabled(update.getStampsEnabled());
        if (update.getPointsEnabled() != null)   p.setPointsEnabled(update.getPointsEnabled());
        if (update.getCashbackEnabled() != null) p.setCashbackEnabled(update.getCashbackEnabled());

        if (update.getStampsRequired() != null)      p.setStampsRequired(update.getStampsRequired());
        if (update.getStampsRewardText() != null)    p.setStampsRewardText(update.getStampsRewardText());
        if (update.getStampsResetOnRedeem() != null) p.setStampsResetOnRedeem(update.getStampsResetOnRedeem());

        if (update.getPointsPerCurrency() != null) p.setPointsPerCurrency(update.getPointsPerCurrency());
        if (update.getPointsExpiryDays() != null)  p.setPointsExpiryDays(update.getPointsExpiryDays());

        if (update.getCashbackPercentage() != null)      p.setCashbackPercentage(update.getCashbackPercentage());
        if (update.getCashbackMinPurchase() != null)     p.setCashbackMinPurchase(update.getCashbackMinPurchase());
        if (update.getCashbackExpiryDays() != null)      p.setCashbackExpiryDays(update.getCashbackExpiryDays());
        if (update.getCashbackMaxPerPurchase() != null)  p.setCashbackMaxPerPurchase(update.getCashbackMaxPerPurchase());

        if (update.getIdentificationMethods() != null) {
            String validated = validateIdentificationMethods(update.getIdentificationMethods());
            p.setIdentificationMethods(validated);
        }

        if (update.getMultiBranchMode() != null) p.setMultiBranchMode(update.getMultiBranchMode());
        if (update.getCardDesignJson() != null)  p.setCardDesignJson(update.getCardDesignJson());

        if (update.getActive() != null) p.setActive(update.getActive());

        return programRepo.save(p);
    }

    /**
     * Valida que identification_methods sea un JSON array con valores válidos.
     * Devuelve el JSON normalizado (dedup + lowercase). Tira IllegalArgumentException
     * si el formato es inválido.
     */
    public String validateIdentificationMethods(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isArray()) throw new IllegalArgumentException("identification_methods debe ser un array");
            Set<String> dedup = new HashSet<>();
            ArrayNode out = objectMapper.createArrayNode();
            Iterator<JsonNode> it = node.elements();
            while (it.hasNext()) {
                String v = it.next().asText("").toLowerCase().trim();
                if (!VALID_ID_METHODS.contains(v))
                    throw new IllegalArgumentException("identification_methods: valor inválido '" + v + "' (válidos: " + VALID_ID_METHODS + ")");
                if (dedup.add(v)) out.add(v);
            }
            if (dedup.isEmpty()) throw new IllegalArgumentException("identification_methods no puede estar vacío");
            return objectMapper.writeValueAsString(out);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("identification_methods JSON inválido: " + e.getMessage());
        }
    }

    /** Lista los methods habilitados (parsea el JSON del programa activo). */
    public List<String> getEnabledIdMethods() {
        try {
            JsonNode node = objectMapper.readTree(getActiveProgram().getIdentificationMethods());
            java.util.List<String> out = new java.util.ArrayList<>();
            node.forEach(n -> out.add(n.asText()));
            return out;
        } catch (Exception e) {
            return List.of("customer_qr", "local_qr", "phone");
        }
    }
}
