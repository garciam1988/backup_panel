package app.coincidir.api.audit.service;

import app.coincidir.api.audit.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/**
 * AuditLogRetentionJob — limpia logs viejos del audit_log según política
 * de retención.
 *
 * Política por defecto:
 *  - 90 días para acciones operativas comunes (cambio de estado, edición
 *    de reserva, etc.).
 *  - 1 año para acciones críticas (delete de reservas, cambios de rol/permiso,
 *    cambios en prompts, restore de backups). Estas se conservan más tiempo
 *    porque suelen ser objeto de investigación.
 *
 * Corre todas las noches a las 03:00 (hora del server). Si el job falla por
 * cualquier razón, lo logueamos y seguimos — la próxima noche reintenta.
 *
 * Para configurar otros valores de retención en el futuro, los moveríamos a
 * application.properties como audit.retention.days y audit.retention.critical.days.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditLogRetentionJob {

    private final AuditLogRepository repo;

    /**
     * Acciones que se conservan 1 año (no 90 días). Se borran luego de un año.
     * Lista intencionalmente acotada — añadir nuevas con criterio.
     */
    private static final Set<String> CRITICAL_ACTIONS = Set.of(
        "reservation.delete",
        "user.create",
        "user.delete",
        "user.update",          // cambios de rol/permisos van por acá
        "role.create",
        "role.update",
        "role.delete",
        "prompt.update",
        "prompt.activate",
        "backup.restore",
        "bot_table.delete",
        "config.update"
    );

    /** Cron: 03:00 todas las noches. */
    @Scheduled(cron = "0 0 3 * * *")
    public void cleanup() {
        try {
            // 1) Borrar logs de acciones comunes anteriores a 90 días.
            Instant ninetyDaysAgo = Instant.now().minus(90, ChronoUnit.DAYS);
            int deletedCommon = repo.deleteOlderThan(ninetyDaysAgo, CRITICAL_ACTIONS);
            log.info("[Audit retention] borrados {} logs comunes anteriores a 90 días", deletedCommon);

            // 2) Borrar logs de cualquier acción anteriores a 1 año.
            Instant oneYearAgo = Instant.now().minus(365, ChronoUnit.DAYS);
            int deletedAll = repo.deleteAllOlderThan(oneYearAgo);
            log.info("[Audit retention] borrados {} logs anteriores a 1 año", deletedAll);

        } catch (Exception e) {
            log.error("[Audit retention] error en limpieza, reintenta mañana: {}", e.getMessage(), e);
        }
    }
}
