package app.coincidir.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "air_flight_alert", indexes = {
        @Index(name = "idx_afa_group", columnList = "group_id"),
        @Index(name = "idx_afa_menu_item", columnList = "menu_item_id, unique_key"),
        @Index(name = "idx_afa_has_issue", columnList = "has_issue")
})
@Getter
@Setter
public class AirFlightAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(name = "menu_item_id", nullable = false)
    private Long menuItemId;

    /** Clave única para upsert: menuItemId-flightNumber-date */
    @Column(name = "unique_key", length = 120)
    private String uniqueKey;

    @Column(name = "group_destination", length = 255)
    private String groupDestination;

    @Column(name = "menu_item_display_name", length = 200)
    private String menuItemDisplayName;

    @Column(name = "flight_number", length = 30)
    private String flightNumber;

    @Column(name = "airline", length = 100)
    private String airline;

    @Column(name = "departure_date")
    private LocalDate departureDate;

    @Column(name = "departure_time", length = 10)
    private String departureTime;

    @Column(name = "origin", length = 255)
    private String origin;

    @Column(name = "destination", length = 255)
    private String destination;

    /** Status devuelto por AeroDataBox: Unknown, Expected, EnRoute, Departed, Delayed, Approaching, Arrived, Canceled, Diverted, CanceledUncertain */
    @Column(name = "aviation_status", length = 50)
    private String aviationStatus;

    @Column(name = "aviation_delay_minutes")
    private Integer aviationDelayMinutes;

    /** Indica si AeroDataBox devolvió datos para este vuelo */
    @Column(name = "aviation_data_found", nullable = false)
    private boolean aviationDataFound = false;

    /** OK | WARNING | ERROR */
    @Column(name = "ai_severity", length = 20)
    private String aiSeverity;

    @Column(name = "ai_summary", columnDefinition = "TEXT")
    private String aiSummary;

    @Column(name = "ai_suggestion", columnDefinition = "TEXT")
    private String aiSuggestion;

    @Column(name = "has_issue", nullable = false)
    private boolean hasIssue = false;

    /** true = operador hizo clic en "Visto"; se resetea si el issue se resuelve o escala */
    @Column(name = "dismissed")
    private Boolean dismissed = false;

    /** true = operador hizo clic en "No mostrar más"; nunca vuelve a aparecer aunque escale */
    @Column(name = "ignored_permanently")
    private Boolean ignoredPermanently = false;

    /** Hora real de salida reportada por AeroDataBox (HH:mm) para comparar con sistema */
    @Column(name = "av_actual_dep_time", length = 10)
    private String avActualDepTime;

    /** Hora real de arribo reportada por AeroDataBox (HH:mm) */
    @Column(name = "av_actual_arr_time", length = 10)
    private String avActualArrTime;

    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
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
