package app.coincidir.api.botplatform.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * BranchSmartTablesLayout — diseño del salón guardado por sucursal.
 *
 * Una fila por sucursal (índice único sobre branch_id). El JSON contiene
 * el shape { version, room: {...}, items: [...] } que antes vivía en
 * localStorage del navegador.
 *
 * @see app.coincidir.api.botplatform.controller.SmartTablesLayoutController
 */
@Entity
@Table(name = "branch_smart_tables_layout")
@Getter @Setter
public class BranchSmartTablesLayout {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "branch_id", nullable = false, unique = true)
    private Long branchId;

    /**
     * JSON serializado del layout. Shape esperado:
     * <pre>
     * {
     *   "version": 1,
     *   "room": { "widthM": 8, "lengthM": 10, "shape": "rect", ... },
     *   "items": [ { "id": "...", "kind": "round4", "x": 1.2, "z": -0.3, ... }, ... ]
     * }
     * </pre>
     * NO validamos el shape en backend — lo tratamos como un blob opaco.
     * El frontend es la única source of truth sobre el formato.
     */
    @Lob
    @Column(name = "layout_json", nullable = false, columnDefinition = "LONGTEXT")
    private String layoutJson;

    /** Username del último editor. Útil para auditoría básica. */
    @Column(name = "updated_by", length = 120)
    private String updatedBy;

    /**
     * Optimistic locking manual. No usamos @Version de JPA porque queremos
     * controlar la lógica desde el controller (devolver 409 con mensaje).
     */
    @Column(name = "version", nullable = false)
    private Long version = 1L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (version == null) version = 1L;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
