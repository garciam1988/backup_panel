// app/coincidir/api/domain/TravelGroup.java
package app.coincidir.api.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import jakarta.persistence.Convert;
import java.util.LinkedHashMap;
import java.util.Map;


@Entity @Table(name = "travel_group")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TravelGroup {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    // Aumentado para soportar estados más largos (ej: PENDIENTE_CONCILIACION)
    @Column(nullable = false, length = 50)
    private GroupStatus status = GroupStatus.FORMED;

    private String destination;
    @Column(name="when_label")
    private String whenLabel;
    @Column(name="companion_preference")
    private String companionPreference;
    @Column(name = "smoke_free")
    private boolean smokeFree;
    @Column(name="size_target")
    private int sizeTarget;

    // Etiqueta visible en la grilla/popup
    @Column(name = "travel_date_label")
    private String travelDateLabel;

    @Column(name = "departure_month", length = 20)
    private String departureMonth;

    // nuevo: fechas concretas del viaje para el grupo
    @Column(name = "travel_start_date")
    private java.time.LocalDate travelStartDate;

    @Column(name = "travel_end_date")
    private java.time.LocalDate travelEndDate;

    @Column(name = "departure_year")
    private Integer departureYear;

    // Resumen de preferencias comunes
    @Convert(converter = MapStringConverter.class)
    @Column(name = "common_prefs_json", columnDefinition = "LONGTEXT")
    private Map<String,String> commonPrefs;

    @Column(name="created_at")
    private Instant createdAt;
    @Column(name="age_bucket")
    private String ageBucket;

    @Column(name = "auto_search_added")
    private int autoSearchAdded;

    @Builder.Default
    @Column(name = "auto_search_enabled", nullable = false)
    private boolean autoSearchEnabled = false;


    @Builder.Default
    @Column(name = "operation_confirmed", nullable = false)
    private boolean operationConfirmed = false;


    @Column(name = "seller_user_id")
    private Long sellerUserId;

    @OneToMany(mappedBy = "group", fetch = FetchType.LAZY)
    private List<TravelRequest> members = new ArrayList<>();

    public String getAgeBucket() { return ageBucket; }
    public void setAgeBucket(String ageBucket) { this.ageBucket = ageBucket; }

    // TravelGroup.java
    @PrePersist
    public void prePersist() {
        if (status == null) status = GroupStatus.OPEN;
        if (createdAt == null) createdAt = Instant.now();
        if (commonPrefs == null) commonPrefs = new LinkedHashMap<>();

    }

    public int getAutoSearchAdded() {
        return autoSearchAdded;
    }
    public void setAutoSearchAdded(int autoSearchAdded) {
        this.autoSearchAdded = autoSearchAdded;
    }


}
