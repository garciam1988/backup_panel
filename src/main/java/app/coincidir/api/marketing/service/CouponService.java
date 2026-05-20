package app.coincidir.api.marketing.service;

import app.coincidir.api.marketing.domain.Coupon;
import app.coincidir.api.marketing.domain.CouponUse;
import app.coincidir.api.marketing.domain.LoyaltyCard;
import app.coincidir.api.marketing.domain.LoyaltyCustomer;
import app.coincidir.api.marketing.domain.MarketingSegment;
import app.coincidir.api.marketing.repository.CouponRepository;
import app.coincidir.api.marketing.repository.CouponUseRepository;
import app.coincidir.api.marketing.repository.LoyaltyCardRepository;
import app.coincidir.api.marketing.repository.LoyaltyCustomerRepository;
import app.coincidir.api.marketing.repository.MarketingSegmentRepository;
import app.coincidir.api.marketing.util.SegmentEvaluator;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
    private final LoyaltyCustomerRepository customerRepo;
    private final LoyaltyCardRepository cardRepo;
    private final MarketingSegmentRepository segmentRepo;

    @Autowired
    private ObjectMapper objectMapper;

    public Page<Coupon> list(Pageable pageable) {
        return list(pageable, false);
    }

    /**
     * Lista cupones con paginación. Si includeArchived es false (default),
     * solo devuelve los no archivados (archived_at IS NULL).
     */
    public Page<Coupon> list(Pageable pageable, boolean includeArchived) {
        if (includeArchived) {
            return couponRepo.findAllByOrderByCreatedAtDesc(pageable);
        }
        return couponRepo.findByArchivedAtIsNullOrderByCreatedAtDesc(pageable);
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

        // Pre-resolvemos cliente + tarjeta una sola vez para evaluar segmentos.
        // Si el cliente no existe (no debería) caemos al filtrado solo por
        // usos previos.
        LoyaltyCustomer customer = customerRepo.findById(customerId).orElse(null);
        LoyaltyCard card = customer == null
            ? null
            : cardRepo.findByCustomerId(customer.getId()).orElse(null);

        // Cache de segmentos ya evaluados para este cliente. Varios cupones
        // pueden referenciar el mismo segmento → así no re-evaluamos N veces.
        java.util.Map<Long, Boolean> segmentMatchCache = new java.util.HashMap<>();
        SegmentEvaluator evaluator = new SegmentEvaluator(objectMapper);

        return base.stream()
            .filter(c -> {
                // 1) Filtro por segmento del cupón. Si el cupón tiene segmentId
                //    y el cliente NO matchea ese segmento, no lo mostramos.
                //    Si el segmento referenciado no existe / está borrado, el
                //    cupón se trata como "para todos" (huérfano benigno).
                if (c.getSegmentId() != null && customer != null) {
                    Boolean matches = segmentMatchCache.computeIfAbsent(c.getSegmentId(), sid -> {
                        MarketingSegment seg = segmentRepo.findById(sid).orElse(null);
                        if (seg == null || seg.getDeletedAt() != null) return true; // huérfano → visible
                        return evaluator.matches(seg.getCriteriaJson(), customer, card);
                    });
                    if (!Boolean.TRUE.equals(matches)) return false;
                }

                // 2) Filtro por usos previos del cliente (lógica histórica).
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

    /**
     * Lista los usos recientes de cupones de un cliente, junto con el Coupon
     * resuelto para cada uso. Devuelve pares (use, coupon) ya joinados en
     * memoria para que el caller pueda armar el DTO sin hacer N+1 queries.
     *
     * El cupón puede venir null si fue eliminado de la BD después del uso —
     * eso no debería pasar (Coupon es soft-relación) pero defendemos.
     */
    public List<CouponUseWithCoupon> recentUsesForCustomer(Long customerId) {
        List<CouponUse> uses = couponUseRepo.findByCustomerIdOrderByUsedAtDesc(customerId);
        if (uses.isEmpty()) return List.of();
        // Cargo todos los coupons referenciados en un solo query para evitar N+1.
        Set<Long> couponIds = uses.stream().map(CouponUse::getCouponId).collect(Collectors.toSet());
        Map<Long, Coupon> coupons = couponRepo.findAllById(couponIds).stream()
            .collect(Collectors.toMap(Coupon::getId, c -> c));
        return uses.stream()
            .map(u -> new CouponUseWithCoupon(u, coupons.get(u.getCouponId())))
            .toList();
    }

    /** Par de CouponUse + Coupon, listo para construir DTOs sin lookups extras. */
    public record CouponUseWithCoupon(CouponUse use, Coupon coupon) {}

    @Transactional
    public Coupon create(Coupon c) {
        if (c.getCode() == null || c.getCode().isBlank())
            throw new IllegalArgumentException("Código del cupón es requerido");
        c.setCode(c.getCode().trim().toUpperCase());
        if (couponRepo.findByCode(c.getCode()).isPresent())
            throw new IllegalArgumentException("Ya existe un cupón con ese código");
        if (c.getDiscountType() == null)
            throw new IllegalArgumentException("discountType es requerido");

        // Validar segmento si vino: tiene que existir y no estar borrado.
        if (c.getSegmentId() != null) validateSegmentExists(c.getSegmentId());

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

    private void validateSegmentExists(Long segmentId) {
        MarketingSegment seg = segmentRepo.findById(segmentId).orElse(null);
        if (seg == null || seg.getDeletedAt() != null)
            throw new IllegalArgumentException("Segmento no encontrado o eliminado: " + segmentId);
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
        // segmentId: tres casos posibles
        //   - update.segmentId == null  → no toca (Jackson no diferencia "campo
        //     ausente" de "campo null", asumimos no-touch para no romper updates
        //     parciales que vienen sin este campo).
        //   - update.segmentId == -1    → sentinel para limpiar a NULL desde el front
        //     (volver a "aplica a todos").
        //   - cualquier otro Long       → validar y setear.
        if (update.getSegmentId() != null) {
            if (update.getSegmentId() == -1L) {
                c.setSegmentId(null);
            } else {
                validateSegmentExists(update.getSegmentId());
                c.setSegmentId(update.getSegmentId());
            }
        }
        if (update.getActive() != null) c.setActive(update.getActive());
        return couponRepo.save(c);
    }

    /**
     * "Elimina" un cupón. Si nunca tuvo usos, hard delete. Si ya tiene
     * coupon_use registrados, lo archivamos: archived_at=now, active=false.
     * El cupón queda inaccesible para el cliente y desaparece del listado
     * del panel admin (salvo includeArchived=true), pero el histórico de
     * coupon_use sigue referenciándolo válidamente.
     *
     * Devuelve "deleted" o "archived" para que el front pueda mostrar el
     * mensaje correcto.
     */
    @Transactional
    public DeleteResult delete(Long id) {
        Coupon c = couponRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Cupón no encontrado"));
        int uses = couponUseRepo.countByCouponId(c.getId());
        if (uses > 0) {
            // Archivar (idempotente: si ya estaba archivado, refrescamos timestamp).
            c.setArchivedAt(Instant.now());
            c.setActive(false);
            couponRepo.save(c);
            log.info("Cupón archivado id={} code={} (tenía {} usos)", c.getId(), c.getCode(), uses);
            return new DeleteResult(false, true, uses);
        }
        couponRepo.delete(c);
        log.info("Cupón eliminado id={} code={}", c.getId(), c.getCode());
        return new DeleteResult(true, false, 0);
    }

    /** Resultado del delete: si fue hard delete, si quedó archivado, y cuántos usos tenía. */
    public record DeleteResult(boolean deleted, boolean archived, int previousUses) {}

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
        if (c.getArchivedAt() != null) return ApplyResult.rejected("Cupón archivado");
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

        // Filtro por segmento: si el cupón está restringido y el cliente no
        // matchea, rechazamos. Segmento huérfano (borrado) → tratamos como
        // "para todos" (no bloquea).
        if (c.getSegmentId() != null) {
            LoyaltyCustomer customer = customerRepo.findById(customerId).orElse(null);
            if (customer != null) {
                MarketingSegment seg = segmentRepo.findById(c.getSegmentId()).orElse(null);
                if (seg != null && seg.getDeletedAt() == null) {
                    LoyaltyCard card = cardRepo.findByCustomerId(customer.getId()).orElse(null);
                    SegmentEvaluator ev = new SegmentEvaluator(objectMapper);
                    if (!ev.matches(seg.getCriteriaJson(), customer, card)) {
                        return ApplyResult.rejected("Este cupón no aplica a este cliente");
                    }
                }
            }
        }

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
