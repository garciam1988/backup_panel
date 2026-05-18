package app.coincidir.api.marketing.service;

import app.coincidir.api.marketing.domain.Coupon;
import app.coincidir.api.marketing.domain.CouponUse;
import app.coincidir.api.marketing.repository.CouponRepository;
import app.coincidir.api.marketing.repository.CouponUseRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * CouponService — CRUD de cupones + validación + aplicación.
 *
 * El método apply() es el que invoca el staff cuando un cliente quiere usar
 * un cupón en una compra. Valida todas las restricciones, registra el uso
 * en coupon_use, incrementa el contador del cupón y devuelve el monto de
 * descuento a aplicar.
 *
 * Tipos de descuento:
 *   PERCENTAGE → discount_value es 0-100. Se aplica sobre purchase_amount.
 *                Si max_discount está definido, se topea ahí.
 *   FIXED      → discount_value es el monto absoluto a descontar.
 *   FREE_ITEM  → no resta monto; la lógica de "free item" la maneja el POS.
 *                Acá solo validamos elegibilidad y registramos el uso.
 *   BOGO       → idem FREE_ITEM, el cálculo real va por afuera.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponService {

    private final CouponRepository couponRepo;
    private final CouponUseRepository couponUseRepo;

    @Autowired
    private ObjectMapper objectMapper;

    public Page<Coupon> list(Pageable pageable) {
        return couponRepo.findAllByOrderByCreatedAtDesc(pageable);
    }

    public Optional<Coupon> findById(Long id)  { return couponRepo.findById(id); }
    public Optional<Coupon> findByCode(String code) { return couponRepo.findByCode(code); }

    /**
     * Lista cupones activos ahora mismo (active=true + dentro de la ventana
     * validFrom/validUntil + sin agotar). Usado por la PWA pública del cliente
     * para mostrarle qué códigos puede mostrar al mozo en el local.
     */
    public List<Coupon> listActiveNow() {
        return couponRepo.findActiveAt(java.time.Instant.now());
    }

    /**
     * Versión filtrada por cliente: oculta los cupones que ESTE cliente ya
     * no puede usar más, considerando el tipo de uso del cupón:
     *
     *   - SINGLE_USE_GLOBAL: ya filtrado por listActiveNow() si currentUses >= 1.
     *   - SINGLE_USE_PER_CUSTOMER: oculto si este cliente ya lo usó al menos 1 vez.
     *   - MULTI_USE_PER_CUSTOMER: oculto si este cliente alcanzó maxUsesPerCustomer.
     *
     * Resultado: en cuanto el mozo aplica el cupón en el local, en el próximo
     * refresh del cliente el cupón desaparece automáticamente de su PWA si era
     * de uso único, o se mantiene si todavía le quedan usos disponibles.
     */
    public List<Coupon> listActiveNowForCustomer(Long customerId) {
        List<Coupon> base = listActiveNow();
        if (customerId == null) return base;
        return base.stream()
            .filter(c -> {
                int previous = couponUseRepo.countByCouponIdAndCustomerId(c.getId(), customerId);
                return switch (c.getUsageType()) {
                    case SINGLE_USE_GLOBAL -> true; // ya viene filtrado por findActiveAt
                    case SINGLE_USE_PER_CUSTOMER -> previous < 1;
                    case MULTI_USE_PER_CUSTOMER -> {
                        int max = c.getMaxUsesPerCustomer() == null ? Integer.MAX_VALUE : c.getMaxUsesPerCustomer();
                        yield previous < max;
                    }
                };
            })
            .toList();
    }

    @Transactional
    public Coupon create(Coupon c) {
        if (c.getCode() == null || c.getCode().isBlank())
            throw new IllegalArgumentException("Código del cupón es requerido");
        c.setCode(c.getCode().trim().toUpperCase());
        if (couponRepo.findByCode(c.getCode()).isPresent())
            throw new IllegalArgumentException("Ya existe un cupón con ese código");
        if (c.getDiscountType() == null)
            throw new IllegalArgumentException("discountType es requerido");

        // Defaults defensivos: las columnas NOT NULL tienen defaults a nivel
        // de campo Java, pero si el cliente manda explícitamente null en el
        // JSON, Jackson los pisa con null y JPA explota al insertar. Acá
        // restauramos los valores razonables si llegaron como null.
        if (c.getUsageType() == null)           c.setUsageType(Coupon.UsageType.MULTI_USE_PER_CUSTOMER);
        if (c.getMaxUsesPerCustomer() == null)  c.setMaxUsesPerCustomer(1);
        if (c.getCurrentUses() == null)         c.setCurrentUses(0);
        if (c.getSource() == null)              c.setSource(Coupon.Source.MANUAL);
        if (c.getActive() == null)              c.setActive(true);

        return couponRepo.save(c);
    }

    @Transactional
    public Coupon update(Long id, Coupon update) {
        Coupon c = couponRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Cupón no encontrado"));
        if (update.getName() != null) c.setName(update.getName());
        if (update.getDescription() != null) c.setDescription(update.getDescription());
        if (update.getDiscountType() != null) c.setDiscountType(update.getDiscountType());
        if (update.getDiscountValue() != null) c.setDiscountValue(update.getDiscountValue());
        if (update.getFreeItemRef() != null) c.setFreeItemRef(update.getFreeItemRef());
        if (update.getMinPurchase() != null) c.setMinPurchase(update.getMinPurchase());
        if (update.getMaxDiscount() != null) c.setMaxDiscount(update.getMaxDiscount());
        if (update.getValidFrom() != null) c.setValidFrom(update.getValidFrom());
        if (update.getValidUntil() != null) c.setValidUntil(update.getValidUntil());
        if (update.getValidDaysOfWeekJson() != null) c.setValidDaysOfWeekJson(update.getValidDaysOfWeekJson());
        if (update.getValidBranchesJson() != null) c.setValidBranchesJson(update.getValidBranchesJson());
        if (update.getUsageType() != null) c.setUsageType(update.getUsageType());
        if (update.getMaxUsesTotal() != null) c.setMaxUsesTotal(update.getMaxUsesTotal());
        if (update.getMaxUsesPerCustomer() != null) c.setMaxUsesPerCustomer(update.getMaxUsesPerCustomer());
        if (update.getActive() != null) c.setActive(update.getActive());
        return couponRepo.save(c);
    }

    /**
     * Valida y aplica el cupón. Si todas las restricciones pasan, registra
     * el uso e incrementa currentUses. Devuelve el monto de descuento.
     */
    @Transactional
    public ApplyResult apply(String code, Long customerId, BigDecimal purchaseAmount, String branchId, String performedBy) {
        Optional<Coupon> opt = couponRepo.findByCode(code == null ? "" : code.trim().toUpperCase());
        if (opt.isEmpty()) return ApplyResult.rejected("Cupón no encontrado");
        Coupon c = opt.get();

        Instant now = Instant.now();
        if (Boolean.FALSE.equals(c.getActive())) return ApplyResult.rejected("Cupón inactivo");
        if (c.getValidFrom() != null && now.isBefore(c.getValidFrom()))
            return ApplyResult.rejected("Aún no comenzó la vigencia");
        if (c.getValidUntil() != null && now.isAfter(c.getValidUntil()))
            return ApplyResult.rejected("Cupón vencido");
        if (c.getMaxUsesTotal() != null && c.getCurrentUses() >= c.getMaxUsesTotal())
            return ApplyResult.rejected("Cupón agotado");
        if (c.getMinPurchase() != null && (purchaseAmount == null || purchaseAmount.compareTo(c.getMinPurchase()) < 0))
            return ApplyResult.rejected("La compra no alcanza el mínimo requerido");
        if (!matchesDayOfWeek(c.getValidDaysOfWeekJson()))
            return ApplyResult.rejected("Cupón no válido hoy");
        if (branchId != null && !matchesBranch(c.getValidBranchesJson(), branchId))
            return ApplyResult.rejected("Cupón no válido en esta sucursal");

        // Usos por cliente
        int previous = couponUseRepo.countByCouponIdAndCustomerId(c.getId(), customerId);
        switch (c.getUsageType()) {
            case SINGLE_USE_GLOBAL -> {
                if (c.getCurrentUses() >= 1) return ApplyResult.rejected("Cupón ya fue usado");
            }
            case SINGLE_USE_PER_CUSTOMER -> {
                if (previous >= 1) return ApplyResult.rejected("Ya usaste este cupón");
            }
            case MULTI_USE_PER_CUSTOMER -> {
                if (previous >= c.getMaxUsesPerCustomer())
                    return ApplyResult.rejected("Alcanzaste el límite de usos");
            }
        }

        BigDecimal discount = computeDiscount(c, purchaseAmount);

        CouponUse use = new CouponUse();
        use.setCouponId(c.getId());
        use.setCustomerId(customerId);
        use.setUsedBranch(branchId);
        use.setUsedByUser(performedBy);
        use.setPurchaseAmount(purchaseAmount);
        use.setDiscountApplied(discount);
        couponUseRepo.save(use);

        c.setCurrentUses(c.getCurrentUses() + 1);
        couponRepo.save(c);

        log.info("Cupón aplicado code={} customer={} discount={}", c.getCode(), customerId, discount);
        return ApplyResult.accepted(discount);
    }

    private BigDecimal computeDiscount(Coupon c, BigDecimal purchaseAmount) {
        if (purchaseAmount == null) return BigDecimal.ZERO;
        BigDecimal d = switch (c.getDiscountType()) {
            case PERCENTAGE -> {
                if (c.getDiscountValue() == null) yield BigDecimal.ZERO;
                BigDecimal pct = c.getDiscountValue().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
                yield purchaseAmount.multiply(pct).setScale(2, RoundingMode.HALF_UP);
            }
            case FIXED -> c.getDiscountValue() == null ? BigDecimal.ZERO : c.getDiscountValue();
            case FREE_ITEM, BOGO -> BigDecimal.ZERO;
        };
        if (c.getMaxDiscount() != null && d.compareTo(c.getMaxDiscount()) > 0) d = c.getMaxDiscount();
        if (d.compareTo(purchaseAmount) > 0) d = purchaseAmount;
        return d;
    }

    private boolean matchesDayOfWeek(String json) {
        if (json == null || json.isBlank()) return true;
        try {
            JsonNode arr = objectMapper.readTree(json);
            if (!arr.isArray() || arr.size() == 0) return true;
            String today = LocalDate.now().getDayOfWeek().name().substring(0, 3);
            for (JsonNode n : arr) if (n.asText("").toUpperCase().equals(today)) return true;
            return false;
        } catch (Exception e) { return true; }
    }

    private boolean matchesBranch(String json, String branchId) {
        if (json == null || json.isBlank()) return true;
        try {
            JsonNode arr = objectMapper.readTree(json);
            if (!arr.isArray() || arr.size() == 0) return true;
            for (JsonNode n : arr) if (n.asText("").equals(branchId)) return true;
            return false;
        } catch (Exception e) { return true; }
    }

    public record ApplyResult(boolean accepted, BigDecimal discountApplied, String reasonIfRejected) {
        public static ApplyResult accepted(BigDecimal d)     { return new ApplyResult(true, d, null); }
        public static ApplyResult rejected(String reason)    { return new ApplyResult(false, BigDecimal.ZERO, reason); }
    }
}
