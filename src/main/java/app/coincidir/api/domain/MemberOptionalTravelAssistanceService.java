package app.coincidir.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "member_optional_travel_assistance_service",
        uniqueConstraints = {
                @UniqueConstraint(name="uq_member_optional_travel_assistance", columnNames={"menu_item_id","member_id"})
        },
        indexes = {
                @Index(name="idx_member_optional_travel_assistance_member", columnList="member_id")
        }
)
@Getter
@Setter
public class MemberOptionalTravelAssistanceService {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name="menu_item_id", nullable=false,
            foreignKey=@ForeignKey(name="fk_member_optional_travel_assistance_menu_item"))
    private MemberOptionalServiceMenuItem menuItem;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name="member_id", nullable=false,
            foreignKey=@ForeignKey(name="fk_member_optional_travel_assistance_member"))
    private TravelRequest member;

    @Column(name="provider", nullable=false, length=160)
    private String provider;

    @Column(name="plan", length=160)
    private String plan;

    @Column(name="policy_number", length=160)
    private String policyNumber;

    @Column(name="emergency_phone", length=80)
    private String emergencyPhone;

    @Column(name="notes", columnDefinition = "TEXT")
    private String notes;


    @Column(name="cost", precision = 12, scale = 2)
    private BigDecimal cost;

    @Column(name="sale", precision = 12, scale = 2)
    private BigDecimal sale;

    @Column(name="created_at", nullable=false, updatable=false)
    private Instant createdAt;

    @Column(name="updated_at", nullable=false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
