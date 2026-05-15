package app.coincidir.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(
        name = "member_optional_service_menu_item",
        indexes = {
                @Index(name = "idx_member_optional_menu_member", columnList = "member_id")
        }
)
@Getter
@Setter
public class MemberOptionalServiceMenuItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name="member_id", nullable = false,
            foreignKey = @ForeignKey(name="fk_member_optional_menu_member"))
    private TravelRequest member;

    @Enumerated(EnumType.STRING)
    @Column(name="service_code", nullable = false, length = 40)
    private MemberOptionalServiceCode serviceCode;

    @Column(name="display_name", nullable = false, length = 120)
    private String displayName;

    @Column(name="position", nullable = false)
    private Integer position;

    @Column(name="created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name="updated_at", nullable = false)
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
