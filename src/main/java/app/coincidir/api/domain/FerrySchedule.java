package app.coincidir.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalTime;

@Entity
@Table(
        name = "ferry_schedules",
        indexes = {
                @Index(name = "idx_ferry_sch_provider", columnList = "provider"),
                @Index(name = "idx_ferry_sch_route", columnList = "ferry_origin, ferry_destination")
        }
)
@Getter
@Setter
public class FerrySchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider", nullable = false, length = 255)
    private String provider;

    @Column(name = "bus_origin", length = 255)
    private String busOrigin;

    @Column(name = "bus_destination", length = 255)
    private String busDestination;

    @Column(name = "ferry_origin", nullable = false, length = 255)
    private String ferryOrigin;

    @Column(name = "ferry_destination", nullable = false, length = 255)
    private String ferryDestination;

    @Column(name = "bus_departure_time")
    private LocalTime busDepartureTime;

    @Column(name = "bus_arrival_time")
    private LocalTime busArrivalTime;

    @Column(name = "ferry_departure_time", nullable = false)
    private LocalTime ferryDepartureTime;

    @Column(name = "ferry_arrival_time", nullable = false)
    private LocalTime ferryArrivalTime;

    @Column(name = "activo", nullable = false)
    private boolean activo = true;
}
