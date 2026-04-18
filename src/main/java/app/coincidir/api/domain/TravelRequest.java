package app.coincidir.api.domain;

import app.coincidir.api.web.dto.BaggageItem;
import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import lombok.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "travel_request")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TravelRequest {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String destination; // ej: "salta"
    @Column(name = "date_preset_id")
    private String datePresetId;

    @Column(name = "when_label")
    private String whenLabel; // "15–22 Nov 2025" o "January 2025"

    @Column(name = "travel_start_date")
    private LocalDate travelStartDate;

    @Column(name = "travel_end_date")
    private LocalDate travelEndDate;

    @Column(name = "travel_date_start")
    private LocalDate travelDateStart;


    @Builder.Default
    @Column(name = "shared_room", nullable = true)
    private Boolean sharedRoom = Boolean.FALSE;

    @Builder.Default
    @Min(0)
    @Column(name = "luggage_count", nullable = true)
    private Integer luggageCount = 0;

    @Builder.Default
    @Column(name = "includes_tours", nullable = true)
    private Boolean includesTours = Boolean.FALSE;

    @Builder.Default
    @Column(name = "travel_assistance", nullable = true)
    private Boolean travelAssistance = Boolean.FALSE;

    // Sugerencia: usar EnumType.STRING con enum CompanionPreference { ANY, SAME_GENDER }
    @Column(name = "companion_preference", length = 20)
    private String companionPreference; // ANY | SAME_GENDER

    @Column(name = "age_min")
    private Integer ageMin;

    @Column(name = "age_max")
    private Integer ageMax;

    @Column(name = "pax_min")
    private Integer paxMin;

    @Column(name = "pax_max")
    private Integer paxMax;

    @Builder.Default
    @Column(name = "travelers_total", nullable = false)
    private Integer travelersTotal = 1;

    @Convert(converter = ListBaggageConverter.class)
    @Column(name = "luggage_json", columnDefinition = "LONGTEXT")
    @Builder.Default
    private List<BaggageItem> luggage = new ArrayList<>();

    @Builder.Default
    @Column(name = "travelers_adults", nullable = false)
    private Integer travelersAdults = 1;

    @Builder.Default
    @Column(name = "travelers_minors", nullable = false)
    private Integer travelersMinors = 0;

    @Builder.Default
    @Column(name = "smoke_free", nullable = false)
    private Boolean smokeFree = Boolean.FALSE;

    private String gender;

    // contacto
    private String name;
    private String email;
    private String phone;

    // documentación / ubicación
    @Column(name = "dni", length = 32)
    private String dni;

    @Column(name = "document_type", length = 20)
    private String documentType;

    // referencia a tabla paises (opcional)
    @Column(name = "country_id")
    private Long countryId;

    // redundante para facilitar reportes/compat
    @Column(name = "country", length = 100)
    private String country;

    private String city; // compat
    private String province;
    private String locality;

    @Column(name = "postal_code")
    private String postalCode;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Column(name = "document_expiry_date")
    private LocalDate documentExpiryDate;

    @Builder.Default
    @Column(name = "document_no_expiry", nullable = false)
    private Boolean documentNoExpiry = Boolean.FALSE;

    @Column(name = "document_not_applicable", nullable = false)
    private Boolean documentNotApplicable = Boolean.FALSE;

    @Column(name = "phone_country_code", length = 10)
    private String phoneCountryCode;

    // --- Seña ---

    @Column(name = "deposit_amount", precision = 10, scale = 2)
    private BigDecimal depositAmount;

    @Column(name = "deposit_payment_method", length = 32)
    private String depositPaymentMethod;

    @Column(name = "deposit_date")
    private LocalDate depositDate;

    @Column(name = "deposit_notes", columnDefinition = "TEXT")
    private String depositNotes;

    /**
     * Optional receipt (image/pdf/etc) for the deposit while the request is still unassigned.
     */
    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "deposit_receipt_blob", columnDefinition = "LONGBLOB")
    private byte[] depositReceiptBlob;

    @Column(name = "deposit_receipt_content_type", length = 255)
    private String depositReceiptContentType;

    @Column(name = "deposit_receipt_file_name", length = 255)
    private String depositReceiptFileName;

    private String tz;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RequestStatus status = RequestStatus.NEW;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id")
    private TravelGroup group;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();

        // doble defensa ante builder nulo
        if (sharedRoom == null) sharedRoom = Boolean.FALSE;
        if (documentNoExpiry == null) documentNoExpiry = Boolean.FALSE;
        if (documentNotApplicable == null) documentNotApplicable = Boolean.FALSE;
        if (includesTours == null) includesTours = Boolean.FALSE;
        if (travelAssistance == null) travelAssistance = Boolean.FALSE;
        if (smokeFree == null) smokeFree = Boolean.FALSE;

        if (luggageCount == null) luggageCount = 0;
        if (travelersTotal == null) travelersTotal = 1;
        if (travelersAdults == null) travelersAdults = 1;
        if (travelersMinors == null) travelersMinors = 0;

        if (status == null) status = RequestStatus.NEW;
    }

    public boolean isSmokeFreeSafe() {
        return Boolean.TRUE.equals(smokeFree);
    }


    public void setLuggage(List<BaggageItem> items) {
        this.luggage = (items == null) ? new ArrayList<>() : items;
    }

    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }

}
