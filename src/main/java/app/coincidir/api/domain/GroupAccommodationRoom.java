package app.coincidir.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(
        name = "group_accommodation_room",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_group_accommodation_room_service_number",
                        columnNames = {"accommodation_service_id", "room_number"}
                )
        },
        indexes = {
                @Index(name = "idx_group_accommodation_room_service", columnList = "accommodation_service_id")
        }
)
@Getter
@Setter
public class GroupAccommodationRoom {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "accommodation_service_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_group_accommodation_room_service")
    )
    private GroupAccommodationService accommodationService;

    @Column(name = "room_number", nullable = false)
    private Integer roomNumber;

    @Column(name = "adults", nullable = false)
    private Integer adults;

    @Column(name = "minors", nullable = false)
    private Integer minors;


    @Column(name = "room_type", length = 60)
    private String roomType;
}
