package app.coincidir.api.botplatform.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * BotTable — tabla de datos custom definida por el admin para que el bot
 * pueda consultar, agregar, modificar y borrar registros.
 *
 * El esquema (columnas) se guarda como JSON en columns_json. Formato:
 *   [
 *     { "name": "nombre",   "type": "text",     "required": true },
 *     { "name": "fecha",    "type": "date",     "required": true },
 *     { "name": "comensales","type": "number",  "required": false },
 *     { "name": "estado",   "type": "select",   "options": ["pendiente","confirmada","cancelada"] }
 *   ]
 *
 * Tipos soportados: text, number, date, datetime, boolean, select.
 *
 * Los registros viven en BotTableRecord con el JSON de cada fila.
 */
@Entity
@Table(name = "bot_table")
@Data
public class BotTable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nombre humano (ej: "Reservas"). Se muestra en admin. */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /**
     * Slug snake_case usado por el bot como handle (ej: "reservas").
     * Único — no pueden existir dos tablas con el mismo slug.
     */
    @Column(name = "slug", nullable = false, length = 60, unique = true)
    private String slug;

    /** Descripción opcional. La lee Claude para entender qué guarda esta tabla. */
    @Column(name = "description", length = 500)
    private String description;

    /** Schema de columnas serializado en JSON. */
    @Column(name = "columns_json", columnDefinition = "TEXT", nullable = false)
    private String columnsJson;

    /** Si es false, el bot no puede operar sobre la tabla aunque exista. */
    @Column(name = "active", nullable = false)
    private Boolean active = true;

    // Flags de confirmación humana (mismo sistema que API Tools)
    @Column(name = "confirm_add", nullable = false)
    private Boolean confirmAdd = true;

    @Column(name = "confirm_update", nullable = false)
    private Boolean confirmUpdate = true;

    @Column(name = "confirm_delete", nullable = false)
    private Boolean confirmDelete = true;

    /**
     * Si es true, los registros de esta tabla se inyectan al system prompt
     * del bot como contexto (similar a Fuentes de datos). Útil para tablas
     * pequeñas que el bot consulta seguido — evita un tool call por mensaje.
     * Para tablas grandes, dejar en false y que el bot use query_records.
     */
    @Column(name = "inject_to_prompt", nullable = false)
    private Boolean injectToPrompt = false;

    /**
     * Nombre de la columna de la tabla que contiene el email del destinatario.
     * Si está vacío o la columna no existe, no se envían emails para esta tabla.
     * Ej: "email", "correo", "mail_cliente".
     */
    @Column(name = "email_column", length = 80)
    private String emailColumn;

    /**
     * Nombre de la columna que contiene la fecha de referencia del recordatorio.
     * Debe ser de tipo "date" o "datetime". Si está vacío, los recordatorios
     * automáticos están deshabilitados para esta tabla (aunque el template
     * "reminder" siga existiendo y se pueda probar manualmente).
     */
    @Column(name = "reminder_date_column", length = 80)
    private String reminderDateColumn;

    /**
     * Cuántas horas antes de la fecha de referencia mandar el recordatorio.
     * Default: 24hs. Configurable: 1, 2, 6, 12, 24, 48, 72, 168 (1 semana).
     */
    @Column(name = "reminder_hours_before")
    private Integer reminderHoursBefore = 24;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
}
