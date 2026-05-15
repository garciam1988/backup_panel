package app.coincidir.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "travel_destination")
@Getter
@Setter
public class TravelDestination {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Código interno que se guarda en travel_request.destination
     * ej: "bariloche", "mendoza"
     */
    @Column(nullable = false, unique = true, length = 50)
    private String code;

    /** Nombre amigable para mostrar en combos. */
    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "sort_order")
    private Integer sortOrder;
}
