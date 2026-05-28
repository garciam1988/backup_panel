package app.coincidir.api.service.backups;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * ReservasBackupConfig — configuración y estado del job de backup de reservas.
 *
 * Singleton (id=1): hay un solo job de backup de reservas. Lo modelamos como
 * tabla (no como properties) para que sea configurable desde el AdminPanel
 * (módulo "Cron Jobs") sin redeploy, y para guardar el resultado de la última
 * corrida (historial simple: solo la última, sin tabla de runs aparte).
 *
 * Mañana, si se suman más jobs, esto se puede generalizar a una tabla con N
 * filas + un "type". Por ahora, concreto y simple.
 */
@Entity
@Table(name = "reservas_backup_config")
@Data
public class ReservasBackupConfig {

    @Id
    private Long id = 1L;

    /** Si está activo, el scheduler corre el backup. */
    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    /** Intervalo entre corridas, en minutos. */
    @Column(name = "interval_minutes", nullable = false)
    private Integer intervalMinutes = 15;

    /** Slug de la tabla de reservas en "Tablas del bot". */
    @Column(name = "table_slug", nullable = false, length = 60)
    private String tableSlug = "reservas";

    /** Nombre EXACTO de la columna de fecha en el schema de la tabla. */
    @Column(name = "date_column", nullable = false, length = 120)
    private String dateColumn = "fecha y hora reserva";

    /** Nombre del archivo en Git (se sobreescribe en cada corrida). */
    @Column(name = "filename", nullable = false, length = 120)
    private String filename = "reservas-hoy.xlsx";

    // ── Estado de la última corrida (historial simple) ──────────────────

    /** Cuándo corrió por última vez (cualquier resultado). */
    @Column(name = "last_run_at")
    private Instant lastRunAt;

    /** Resultado de la última corrida: "OK" | "ERROR" | null (nunca corrió). */
    @Column(name = "last_run_status", length = 20)
    private String lastRunStatus;

    /** Mensaje de la última corrida (filas exportadas, o el error). */
    @Column(name = "last_run_message", length = 500)
    private String lastRunMessage;

    /** Cuántas filas (reservas de hoy) tuvo el último backup exitoso. */
    @Column(name = "last_run_rows")
    private Integer lastRunRows;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PreUpdate @PrePersist
    void touch() { updatedAt = Instant.now(); }
}
