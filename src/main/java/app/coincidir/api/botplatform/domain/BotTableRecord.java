package app.coincidir.api.botplatform.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * BotTableRecord — un registro (fila) de una BotTable. Los valores van como
 * JSON en data_json para no requerir migrations cuando se agregan columnas.
 *
 * Ej: para una tabla "Reservas" con columnas {nombre, fecha, comensales}:
 *   data_json = {"nombre":"Juan","fecha":"2026-04-26","comensales":4}
 *
 * Las búsquedas a nivel SQL quedan limitadas (escaneo full + filtro JSON
 * en MySQL 5.7+). Para tablas con miles de filas hay que pensar índices
 * sobre paths específicos del JSON, pero por ahora 1k-10k filas funciona OK.
 */
@Entity
@Table(name = "bot_table_record", indexes = {
    @Index(name = "idx_record_table", columnList = "table_id"),
    @Index(name = "idx_record_created", columnList = "created_at"),
    @Index(name = "idx_record_branch", columnList = "branch_id"),
    @Index(name = "idx_record_table_branch", columnList = "table_id, branch_id"),
})
@Data
public class BotTableRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "table_id", nullable = false)
    private Long tableId;

    /**
     * Sucursal a la que pertenece este registro. NULL solo para data legacy
     * pre-Bloque-3 — el bootstrap rellena esos casos con la branch default
     * al arrancar la app. Para records nuevos creados después del Bloque 3,
     * SIEMPRE se setea con el branch del BranchContext del request.
     *
     * El filtrado por branch lo hace BotTableService al consultar — el bot
     * solo ve los records de la branch del contexto. Los listados del admin
     * también respetan esto (un gerente solo ve records de sus branches).
     */
    @Column(name = "branch_id")
    private Long branchId;

    @Column(name = "data_json", columnDefinition = "JSON", nullable = false)
    private String dataJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Origen del registro: "bot" (creado por el bot vía chat) o "admin"
     * (creado/editado desde /admin) o "import" (cargado vía import Excel/CSV).
     */
    @Column(name = "source", length = 20)
    private String source;

    /**
     * ID de la sesión del chat que creó el registro (si fue creado por el bot).
     * Sirve para que las reglas proactivas sepan a qué sesión enviar el mensaje
     * cuando se dispara un trigger. NULL si fue creado desde /admin o import.
     */
    @Column(name = "session_id", length = 120)
    private String sessionId;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (source == null) source = "admin";
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
}
