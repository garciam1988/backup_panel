package app.coincidir.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * Tabla de relación entre transfer_locations y transfer_points.
 * Una location (ej: "Aeropuerto") puede tener N puntos (ej: "Ezeiza", "Aeroparque").
 */
@Entity
@Table(
    name = "locations_x_points",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uq_locations_x_points",
            columnNames = {"transfer_location_id", "transfer_point_id"}
        )
    },
    indexes = {
        @Index(name = "idx_lxp_location_id", columnList = "transfer_location_id"),
        @Index(name = "idx_lxp_point_id",    columnList = "transfer_point_id")
    }
)
@Getter
@Setter
public class LocationXPoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK a transfer_locations.id */
    @Column(name = "transfer_location_id", nullable = false)
    private Long transferLocationId;

    /** FK a transfer_points.id */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "transfer_point_id",
        nullable = false,
        foreignKey = @ForeignKey(name = "fk_lxp_transfer_point")
    )
    private TransferPoint transferPoint;
}
