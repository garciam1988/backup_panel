package app.coincidir.api.marketing.dto;

import app.coincidir.api.marketing.domain.*;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * MarketingDtos — DTOs públicos del módulo Marketing.
 *
 * Para no inflar el árbol con un archivo por DTO, los agrupamos acá como
 * records nested. Cada controller importa los que necesita.
 *
 * Convención:
 *   - *Request: lo que llega al endpoint (body POST/PUT).
 *   - *Response: lo que devuelve el endpoint.
 *   - *Summary:  versión liviana para listados.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MarketingDtos {

    private MarketingDtos() {}

    // ── PROGRAMA ─────────────────────────────────────────────────────────

    public record ProgramDto(
        Long id,
        String name,
        String description,
        Boolean stampsEnabled,
        Boolean pointsEnabled,
        Boolean cashbackEnabled,
        Integer stampsRequired,
        String stampsRewardText,
        Boolean stampsResetOnRedeem,
        BigDecimal pointsPerCurrency,
        Integer pointsExpiryDays,
        BigDecimal cashbackPercentage,
        BigDecimal cashbackMinPurchase,
        Integer cashbackExpiryDays,
        BigDecimal cashbackMaxPerPurchase,
        String identificationMethods,
        LoyaltyProgram.MultiBranchMode multiBranchMode,
        String cardDesignJson,
        Boolean active,
        Instant createdAt,
        Instant updatedAt
    ) {
        public static ProgramDto fromEntity(LoyaltyProgram p) {
            return new ProgramDto(p.getId(), p.getName(), p.getDescription(),
                p.getStampsEnabled(), p.getPointsEnabled(), p.getCashbackEnabled(),
                p.getStampsRequired(), p.getStampsRewardText(), p.getStampsResetOnRedeem(),
                p.getPointsPerCurrency(), p.getPointsExpiryDays(),
                p.getCashbackPercentage(), p.getCashbackMinPurchase(),
                p.getCashbackExpiryDays(), p.getCashbackMaxPerPurchase(),
                p.getIdentificationMethods(), p.getMultiBranchMode(),
                p.getCardDesignJson(), p.getActive(),
                p.getCreatedAt(), p.getUpdatedAt());
        }
    }

    // ── REWARD ───────────────────────────────────────────────────────────

    public record RewardDto(
        Long id,
        Long programId,
        String name,
        String description,
        String imageUrl,
        LoyaltyReward.RewardType rewardType,
        Integer costStamps,
        Integer costPoints,
        BigDecimal costCashback,
        Instant validFrom,
        Instant validUntil,
        Integer stockTotal,
        Integer stockRemaining,
        Integer maxPerCustomer,
        String validDaysOfWeek,
        String validHoursJson,
        String branchRestrictions,
        Boolean active,
        Integer displayOrder
    ) {
        public static RewardDto fromEntity(LoyaltyReward r) {
            return new RewardDto(r.getId(), r.getProgramId(), r.getName(),
                r.getDescription(), r.getImageUrl(), r.getRewardType(),
                r.getCostStamps(), r.getCostPoints(), r.getCostCashback(),
                r.getValidFrom(), r.getValidUntil(),
                r.getStockTotal(), r.getStockRemaining(), r.getMaxPerCustomer(),
                r.getValidDaysOfWeek(), r.getValidHoursJson(), r.getBranchRestrictions(),
                r.getActive(), r.getDisplayOrder());
        }
    }

    // ── CUSTOMER ─────────────────────────────────────────────────────────

    public record CustomerDto(
        Long id,
        String customerHash,
        String phone,
        String email,
        String firstName,
        String lastName,
        LocalDate birthDate,
        String reservationTableSlug,
        Long reservationRecordId,
        Instant enrolledAt,
        String enrolledSource,
        String enrolledBranch,
        Instant lastActivityAt,
        Integer totalVisits,
        Boolean acceptsWhatsapp,
        Boolean acceptsEmail,
        Boolean acceptsPush,
        Boolean active
    ) {
        public static CustomerDto fromEntity(LoyaltyCustomer c) {
            return new CustomerDto(c.getId(), c.getCustomerHash(), c.getPhone(),
                c.getEmail(), c.getFirstName(), c.getLastName(), c.getBirthDate(),
                c.getReservationTableSlug(), c.getReservationRecordId(),
                c.getEnrolledAt(), c.getEnrolledSource(), c.getEnrolledBranch(),
                c.getLastActivityAt(), c.getTotalVisits(),
                c.getAcceptsWhatsapp(), c.getAcceptsEmail(), c.getAcceptsPush(),
                c.getActive());
        }
    }

    public record EnrollRequest(
        String phone,
        String firstName,
        String lastName,
        String email,
        LocalDate birthDate,
        String branchId,
        String source,
        String reservationTableSlug,
        Long reservationRecordId
    ) {}

    public record EnrollResponse(
        Long customerId,
        String customerHash,
        String pwaUrl,
        boolean alreadyEnrolled
    ) {}

    // ── CARD ─────────────────────────────────────────────────────────────

    public record CardDto(
        Long customerId,
        Long programId,
        Integer currentStamps,
        Integer currentPoints,
        BigDecimal cashbackBalance,
        Integer lifetimeStamps,
        Integer lifetimePoints,
        BigDecimal lifetimeCashback,
        String tierCode,
        BigDecimal tierProgress,
        Instant lastStampAt,
        Instant lastRedeemAt
    ) {
        public static CardDto fromEntity(LoyaltyCard c) {
            if (c == null) return null;
            return new CardDto(c.getCustomerId(), c.getProgramId(),
                c.getCurrentStamps(), c.getCurrentPoints(), c.getCashbackBalance(),
                c.getLifetimeStamps(), c.getLifetimePoints(), c.getLifetimeCashback(),
                c.getTierCode(), c.getTierProgress(),
                c.getLastStampAt(), c.getLastRedeemAt());
        }
    }

    /** Vista pública de la tarjeta para la PWA del cliente. */
    public record PublicCardView(
        ProgramDto program,
        CustomerDto customer,
        CardDto card,
        List<RewardDto> availableRewards,
        List<TransactionDto> recentTransactions
    ) {}

    // ── TRANSACTION ──────────────────────────────────────────────────────

    public record TransactionDto(
        Long id,
        String transactionType,
        Integer stampsDelta,
        Integer pointsDelta,
        BigDecimal cashbackDelta,
        String branchId,
        BigDecimal purchaseAmount,
        String source,
        String notes,
        Instant createdAt
    ) {
        public static TransactionDto fromEntity(LoyaltyTransaction t) {
            return new TransactionDto(t.getId(), t.getTransactionType(),
                t.getStampsDelta(), t.getPointsDelta(), t.getCashbackDelta(),
                t.getBranchId(), t.getPurchaseAmount(),
                t.getSource(), t.getNotes(), t.getCreatedAt());
        }
    }

    /** Request del staff/bot/admin para sumar stamps/puntos/cashback. */
    public record EarnRequest(
        String customerHash,       // identificación por hash (PWA scan)
        String phone,              // o por teléfono (mozo ingresa)
        Integer stamps,            // cantidad a sumar (default 1 si null)
        Integer points,
        BigDecimal cashback,
        BigDecimal purchaseAmount,
        String branchId,
        String source,             // 'staff_scan','customer_qr','bot','admin_manual','auto_reservation'
        String reservationTableSlug,
        Long reservationRecordId,
        String notes
    ) {}

    public record EarnResponse(
        Long transactionId,
        String customerHash,
        Integer currentStamps,
        Integer currentPoints,
        BigDecimal cashbackBalance,
        Integer stampsToReward,    // cuántos le faltan para el premio principal
        String message             // "¡Sumaste 1 estampilla! Te faltan 7 para tu roll gratis"
    ) {}

    // ── REDEMPTION ───────────────────────────────────────────────────────

    public record RedemptionDto(
        Long id,
        Long customerId,
        Long rewardId,
        String rewardName,
        String redemptionCode,
        Integer stampsCost,
        Integer pointsCost,
        BigDecimal cashbackCost,
        LoyaltyRedemption.Status status,
        Instant requestedAt,
        Instant expiresAt,
        Instant redeemedAt,
        String redeemedBranch
    ) {}

    public record RedeemRequest(Long rewardId) {}

    public record ValidateRedemptionRequest(
        String redemptionCode,
        String branchId
    ) {}

    // ── SEGMENT ──────────────────────────────────────────────────────────

    public record SegmentDto(
        Long id,
        String name,
        String description,
        String criteriaJson,
        Integer estimatedSize,
        Instant lastComputedAt,
        Boolean active
    ) {
        public static SegmentDto fromEntity(MarketingSegment s) {
            return new SegmentDto(s.getId(), s.getName(), s.getDescription(),
                s.getCriteriaJson(), s.getEstimatedSize(), s.getLastComputedAt(),
                s.getActive());
        }
    }

    public record SegmentPreviewResponse(
        Integer matchCount,
        List<CustomerDto> sample
    ) {}

    // ── CAMPAIGN ─────────────────────────────────────────────────────────

    public record CampaignDto(
        Long id,
        String name,
        String description,
        Long segmentId,
        String targetFilterJson,
        String channelsJson,
        String messageWhatsapp,
        String messageEmailSubject,
        String messageEmailBody,
        String messagePushTitle,
        String messagePushBody,
        String ctaUrl,
        Long couponId,
        MarketingCampaign.ScheduleType scheduleType,
        Instant scheduledAt,
        String recurrenceConfigJson,
        String triggerConfigJson,
        MarketingCampaign.Status status,
        Integer totalTargeted,
        Integer totalSent,
        Integer totalDelivered,
        Integer totalOpened,
        Integer totalClicked,
        Integer totalConverted,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt
    ) {
        public static CampaignDto fromEntity(MarketingCampaign c) {
            return new CampaignDto(c.getId(), c.getName(), c.getDescription(),
                c.getSegmentId(), c.getTargetFilterJson(), c.getChannelsJson(),
                c.getMessageWhatsapp(), c.getMessageEmailSubject(), c.getMessageEmailBody(),
                c.getMessagePushTitle(), c.getMessagePushBody(),
                c.getCtaUrl(), c.getCouponId(),
                c.getScheduleType(), c.getScheduledAt(),
                c.getRecurrenceConfigJson(), c.getTriggerConfigJson(),
                c.getStatus(),
                c.getTotalTargeted(), c.getTotalSent(), c.getTotalDelivered(),
                c.getTotalOpened(), c.getTotalClicked(), c.getTotalConverted(),
                c.getCreatedAt(), c.getStartedAt(), c.getCompletedAt());
        }
    }

    // ── COUPON ───────────────────────────────────────────────────────────

    public record CouponDto(
        Long id,
        String code,
        String name,
        String description,
        Coupon.DiscountType discountType,
        BigDecimal discountValue,
        String freeItemRef,
        BigDecimal minPurchase,
        BigDecimal maxDiscount,
        Instant validFrom,
        Instant validUntil,
        String validDaysOfWeekJson,
        String validBranchesJson,
        Coupon.UsageType usageType,
        Integer maxUsesTotal,
        Integer maxUsesPerCustomer,
        Integer currentUses,
        Coupon.Source source,
        Long campaignId,
        Boolean active
    ) {
        public static CouponDto fromEntity(Coupon c) {
            return new CouponDto(c.getId(), c.getCode(), c.getName(), c.getDescription(),
                c.getDiscountType(), c.getDiscountValue(), c.getFreeItemRef(),
                c.getMinPurchase(), c.getMaxDiscount(),
                c.getValidFrom(), c.getValidUntil(),
                c.getValidDaysOfWeekJson(), c.getValidBranchesJson(),
                c.getUsageType(), c.getMaxUsesTotal(), c.getMaxUsesPerCustomer(),
                c.getCurrentUses(), c.getSource(), c.getCampaignId(), c.getActive());
        }
    }

    public record CouponApplyRequest(
        String code,
        Long customerId,
        BigDecimal purchaseAmount,
        String branchId
    ) {}

    public record CouponApplyResponse(
        Boolean accepted,
        BigDecimal discountApplied,
        String reasonIfRejected
    ) {}
}
