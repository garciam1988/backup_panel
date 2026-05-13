package app.coincidir.api.marketing.service;

import app.coincidir.api.marketing.domain.LoyaltyCard;
import app.coincidir.api.marketing.domain.LoyaltyCustomer;
import app.coincidir.api.marketing.domain.LoyaltyRedemption;
import app.coincidir.api.marketing.domain.LoyaltyReward;
import app.coincidir.api.marketing.repository.LoyaltyCardRepository;
import app.coincidir.api.marketing.repository.LoyaltyRedemptionRepository;
import app.coincidir.api.marketing.repository.LoyaltyRewardRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * LoyaltyRewardService — CRUD del catálogo de premios + validación de
 * elegibilidad para canje.
 *
 * canValidate() consolida TODAS las reglas que pueden bloquear un canje:
 *   1. Reward activo y no borrado
 *   2. Dentro de su ventana de vigencia (validFrom/validUntil)
 *   3. Stock disponible (stockRemaining > 0 si stockTotal definido)
 *   4. Día de la semana válido (validDaysOfWeek)
 *   5. Hora del día válida (validHoursJson)
 *   6. Sucursal permitida (branchRestrictions)
 *   7. Cliente no superó maxPerCustomer
 *   8. Cliente tiene saldo suficiente (stamps/points/cashback)
 *
 * Devuelve un EligibilityResult que dice OK o por qué no. El frontend usa
 * esto para mostrar el motivo de bloqueo en la PWA.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoyaltyRewardService {

    private final LoyaltyRewardRepository rewardRepo;
    private final LoyaltyRedemptionRepository redemptionRepo;
    private final LoyaltyCardRepository cardRepo;
    private final LoyaltyProgramService programService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<LoyaltyReward> listForAdmin(Long programId) {
        return rewardRepo.findByProgramIdAndDeletedAtIsNullOrderByDisplayOrderAscIdAsc(programId);
    }

    public List<LoyaltyReward> listAvailableNow(Long programId) {
        return rewardRepo.findCurrentlyAvailable(programId, Instant.now());
    }

    public Optional<LoyaltyReward> findById(Long id) {
        return rewardRepo.findByIdAndDeletedAtIsNull(id);
    }

    @Transactional
    public LoyaltyReward create(LoyaltyReward r) {
        if (r.getProgramId() == null) {
            r.setProgramId(programService.getActiveProgram().getId());
        }
        validate(r);
        if (r.getStockTotal() != null && r.getStockRemaining() == null) {
            r.setStockRemaining(r.getStockTotal());
        }
        return rewardRepo.save(r);
    }

    @Transactional
    public LoyaltyReward update(Long id, LoyaltyReward update) {
        LoyaltyReward r = rewardRepo.findByIdAndDeletedAtIsNull(id)
            .orElseThrow(() -> new IllegalArgumentException("Reward no encontrado: " + id));

        if (update.getName() != null)        r.setName(update.getName());
        if (update.getDescription() != null) r.setDescription(update.getDescription());
        if (update.getImageUrl() != null)    r.setImageUrl(update.getImageUrl());
        if (update.getRewardType() != null)  r.setRewardType(update.getRewardType());
        if (update.getCostStamps() != null)  r.setCostStamps(update.getCostStamps());
        if (update.getCostPoints() != null)  r.setCostPoints(update.getCostPoints());
        if (update.getCostCashback() != null) r.setCostCashback(update.getCostCashback());
        if (update.getValidFrom() != null)   r.setValidFrom(update.getValidFrom());
        if (update.getValidUntil() != null)  r.setValidUntil(update.getValidUntil());
        if (update.getStockTotal() != null)  r.setStockTotal(update.getStockTotal());
        if (update.getStockRemaining() != null) r.setStockRemaining(update.getStockRemaining());
        if (update.getMaxPerCustomer() != null)  r.setMaxPerCustomer(update.getMaxPerCustomer());
        if (update.getValidDaysOfWeek() != null) r.setValidDaysOfWeek(update.getValidDaysOfWeek());
        if (update.getValidHoursJson() != null)  r.setValidHoursJson(update.getValidHoursJson());
        if (update.getBranchRestrictions() != null) r.setBranchRestrictions(update.getBranchRestrictions());
        if (update.getActive() != null)       r.setActive(update.getActive());
        if (update.getDisplayOrder() != null) r.setDisplayOrder(update.getDisplayOrder());

        validate(r);
        return rewardRepo.save(r);
    }

    @Transactional
    public void softDelete(Long id) {
        LoyaltyReward r = rewardRepo.findByIdAndDeletedAtIsNull(id)
            .orElseThrow(() -> new IllegalArgumentException("Reward no encontrado: " + id));
        r.setDeletedAt(Instant.now());
        r.setActive(false);
        rewardRepo.save(r);
    }

    private void validate(LoyaltyReward r) {
        if (r.getName() == null || r.getName().isBlank())
            throw new IllegalArgumentException("Nombre del reward es requerido");
        if (r.getRewardType() == null)
            throw new IllegalArgumentException("rewardType es requerido");

        switch (r.getRewardType()) {
            case STAMPS -> {
                if (r.getCostStamps() == null || r.getCostStamps() <= 0)
                    throw new IllegalArgumentException("Para reward tipo STAMPS, cost_stamps debe ser > 0");
            }
            case POINTS -> {
                if (r.getCostPoints() == null || r.getCostPoints() <= 0)
                    throw new IllegalArgumentException("Para reward tipo POINTS, cost_points debe ser > 0");
            }
            case CASHBACK -> {
                if (r.getCostCashback() == null || r.getCostCashback().signum() <= 0)
                    throw new IllegalArgumentException("Para reward tipo CASHBACK, cost_cashback debe ser > 0");
            }
            case FREE -> { /* sin costo */ }
        }
    }

    /**
     * Evalúa si un cliente puede canjear un reward AHORA.
     */
    public EligibilityResult checkEligibility(LoyaltyCustomer customer, LoyaltyReward reward, String branchId) {
        Instant now = Instant.now();

        if (Boolean.FALSE.equals(reward.getActive()) || reward.getDeletedAt() != null) {
            return EligibilityResult.no("Premio no disponible");
        }
        if (reward.getValidFrom() != null && now.isBefore(reward.getValidFrom())) {
            return EligibilityResult.no("Aún no comenzó la vigencia del premio");
        }
        if (reward.getValidUntil() != null && now.isAfter(reward.getValidUntil())) {
            return EligibilityResult.no("El premio venció");
        }
        if (reward.getStockTotal() != null && reward.getStockRemaining() != null
            && reward.getStockRemaining() <= 0) {
            return EligibilityResult.no("Sin stock disponible");
        }
        if (!matchesDayOfWeek(reward.getValidDaysOfWeek())) {
            return EligibilityResult.no("No es un día válido para canjear");
        }
        if (!matchesHourRange(reward.getValidHoursJson())) {
            return EligibilityResult.no("Fuera del horario válido");
        }
        if (branchId != null && !matchesBranch(reward.getBranchRestrictions(), branchId)) {
            return EligibilityResult.no("Premio no disponible en esta sucursal");
        }
        if (reward.getMaxPerCustomer() != null) {
            int previous = redemptionRepo.countByCustomerIdAndRewardIdAndStatusIn(
                customer.getId(), reward.getId(),
                List.of(LoyaltyRedemption.Status.PENDING, LoyaltyRedemption.Status.REDEEMED));
            if (previous >= reward.getMaxPerCustomer()) {
                return EligibilityResult.no("Ya canjeaste este premio el máximo de veces");
            }
        }

        LoyaltyCard card = cardRepo.findByCustomerId(customer.getId()).orElse(null);
        if (card == null) return EligibilityResult.no("Cliente sin tarjeta");

        switch (reward.getRewardType()) {
            case STAMPS -> {
                if (card.getCurrentStamps() < reward.getCostStamps())
                    return EligibilityResult.no("Te faltan estampillas");
            }
            case POINTS -> {
                if (card.getCurrentPoints() < reward.getCostPoints())
                    return EligibilityResult.no("Te faltan puntos");
            }
            case CASHBACK -> {
                if (card.getCashbackBalance().compareTo(reward.getCostCashback()) < 0)
                    return EligibilityResult.no("Te falta cashback");
            }
            case FREE -> { /* gratis, OK */ }
        }

        return EligibilityResult.yes();
    }

    private boolean matchesDayOfWeek(String json) {
        if (json == null || json.isBlank()) return true;
        try {
            JsonNode arr = objectMapper.readTree(json);
            if (!arr.isArray() || arr.size() == 0) return true;
            String today = LocalDate.now().getDayOfWeek().name().substring(0, 3); // MON, TUE...
            for (JsonNode n : arr) {
                if (n.asText("").toUpperCase().equals(today)) return true;
            }
            return false;
        } catch (Exception e) { return true; }
    }

    private boolean matchesHourRange(String json) {
        if (json == null || json.isBlank()) return true;
        try {
            JsonNode n = objectMapper.readTree(json);
            String from = n.path("from").asText("");
            String to = n.path("to").asText("");
            if (from.isEmpty() || to.isEmpty()) return true;
            LocalTime now = LocalDateTime.now(ZoneId.of("America/Argentina/Buenos_Aires")).toLocalTime();
            LocalTime f = LocalTime.parse(from);
            LocalTime t = LocalTime.parse(to);
            if (f.isBefore(t)) return !now.isBefore(f) && !now.isAfter(t);
            return !now.isBefore(f) || !now.isAfter(t);
        } catch (Exception e) { return true; }
    }

    private boolean matchesBranch(String json, String branchId) {
        if (json == null || json.isBlank()) return true;
        try {
            JsonNode arr = objectMapper.readTree(json);
            if (!arr.isArray() || arr.size() == 0) return true;
            for (JsonNode n : arr) {
                if (n.asText("").equals(branchId)) return true;
            }
            return false;
        } catch (Exception e) { return true; }
    }

    /** Decrementa stockRemaining si aplica. Idempotente: si stockTotal es null no hace nada. */
    @Transactional
    public void decrementStock(Long rewardId) {
        LoyaltyReward r = rewardRepo.findById(rewardId).orElseThrow();
        if (r.getStockTotal() != null && r.getStockRemaining() != null && r.getStockRemaining() > 0) {
            r.setStockRemaining(r.getStockRemaining() - 1);
            rewardRepo.save(r);
        }
    }

    /** Restituye stock (ej: cuando un canje expira). */
    @Transactional
    public void incrementStock(Long rewardId) {
        LoyaltyReward r = rewardRepo.findById(rewardId).orElseThrow();
        if (r.getStockTotal() != null && r.getStockRemaining() != null) {
            r.setStockRemaining(r.getStockRemaining() + 1);
            rewardRepo.save(r);
        }
    }

    public record EligibilityResult(boolean eligible, String reason) {
        public static EligibilityResult yes() { return new EligibilityResult(true, null); }
        public static EligibilityResult no(String reason) { return new EligibilityResult(false, reason); }
    }
}
