package app.coincidir.api.botplatform.service;

import app.coincidir.api.botplatform.domain.BotTable;
import app.coincidir.api.botplatform.domain.BotTableRecord;
import app.coincidir.api.botplatform.domain.TableServiceSession;
import app.coincidir.api.botplatform.repository.TableServiceSessionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.text.Normalizer;
import java.time.Instant;
import java.util.Optional;

/**
 * TableServiceTrackerService — escucha cambios de records de BotTable
 * y mantiene actualizada la tabla {@code table_service_session}.
 *
 * Lógica:
 *   - Si el status del record mapea a "en servicio" y NO hay sesión
 *     abierta para ese record → crear una sesión nueva con startedAt=now.
 *   - Si el status mapea a "terminado" (TERMINADA / FINALIZADA / PAGADA /
 *     LIBRE) → cerrar la sesión activa con reason='completed'.
 *   - Si el status mapea a "cancelada" → cerrar con reason='cancelled'.
 *   - Si el status mapea a "reservado" (vuelve a un estado previo) →
 *     cerrar con reason='reverted'.
 *   - Si el evento es "cancelled" del BotTableChangeEvent (record
 *     eliminado) → cerrar con reason='cancelled'.
 *
 * Idempotencia: la BD tiene un índice único parcial sobre (record_id,
 * sesiones activas), así que un INSERT duplicado falla con
 * DataIntegrityViolationException — lo capturamos y dejamos pasar.
 *
 * Errores NO se propagan. El listener es best-effort: si falla, la
 * transacción del save del record igual se confirma. Loggeamos warn.
 *
 * @see BotTableChangeEvent
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TableServiceTrackerService {

    private final TableServiceSessionRepository sessionRepo;
    private final PanelBotTableService panelService;
    private final ObjectMapper objectMapper;

    /**
     * Escucha en POST_COMMIT — solo procesamos si la transacción que
     * cambió el record efectivamente comiteó. Si la tx hace rollback,
     * no hay sesión que actualizar.
     *
     * No usamos @Async porque la operación es muy chica (1-2 queries) y
     * preferimos que cualquier error que ocurra esté linkeado al request
     * original en los logs.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBotTableChange(BotTableChangeEvent ev) {
        try {
            handle(ev);
        } catch (Exception e) {
            // Best-effort: nunca rompemos el flujo del caller.
            log.warn("[TableService] Error procesando evento {} de record {}: {}",
                    ev.event,
                    ev.record != null ? ev.record.getId() : null,
                    e.getMessage());
        }
    }

    private void handle(BotTableChangeEvent ev) {
        BotTable table = ev.table;
        BotTableRecord rec = ev.record;
        if (table == null || rec == null) return;

        // Necesitamos branchId — si el record no tiene, no podemos trackear.
        Long branchId = rec.getBranchId();
        if (branchId == null) {
            log.debug("[TableService] record {} sin branchId — no se trackea sesión",
                    rec.getId());
            return;
        }

        // Caso especial: el record fue eliminado. Cerramos cualquier
        // sesión activa para él (motivo: cancelled).
        if ("cancelled".equals(ev.event)) {
            closeActiveIfAny(rec.getId(), "cancelled");
            return;
        }

        // Para "created" y "updated", inspeccionamos el status del record
        // y decidimos qué hacer.
        PanelBotTableService.CalendarCfg cfg;
        try {
            cfg = panelService.readCalendarCfg(table);
        } catch (Exception e) {
            log.debug("[TableService] no se pudo leer calendarCfg de tabla {}: {}",
                    table.getId(), e.getMessage());
            return;
        }
        if (cfg == null || cfg.statusColumn == null || cfg.statusColumn.isBlank()) {
            // La tabla no tiene una columna de status configurada — no
            // hay nada que trackear. Smart Tables solo aplica a tablas
            // con calendarConfig completo (statusColumn + tableColumn).
            return;
        }
        if (cfg.tableColumn == null || cfg.tableColumn.isBlank()) {
            return;
        }

        // Parsear el JSON del record para extraer el status y la mesa.
        String rawStatus;
        String tableLabel;
        Integer personsCount = null;
        String title = null;
        try {
            JsonNode data = objectMapper.readTree(rec.getDataJson());
            rawStatus  = textOrNull(data, cfg.statusColumn);
            tableLabel = textOrNull(data, cfg.tableColumn);
            if (cfg.personsColumn != null) {
                String pc = textOrNull(data, cfg.personsColumn);
                if (pc != null) {
                    try { personsCount = Integer.parseInt(pc.trim()); }
                    catch (NumberFormatException nfe) { /* dejar null */ }
                }
            }
            if (cfg.titleColumn != null) {
                title = textOrNull(data, cfg.titleColumn);
            }
        } catch (Exception e) {
            log.debug("[TableService] no se pudo parsear dataJson del record {}: {}",
                    rec.getId(), e.getMessage());
            return;
        }

        if (tableLabel == null || tableLabel.isBlank()) {
            // La reserva no tiene mesa asignada — no podemos trackear.
            // Si después le asignan mesa y el status cambia, vamos a
            // crear la sesión recién en ese momento.
            return;
        }

        // Mapear el status crudo a una categoría estable.
        StatusKind kind = mapStatus(rawStatus);

        switch (kind) {
            case IN_SERVICE -> openIfAbsent(branchId, rec.getId(), table.getId(),
                    tableLabel, personsCount, title);
            case COMPLETED  -> closeActiveIfAny(rec.getId(), "completed");
            case CANCELLED  -> closeActiveIfAny(rec.getId(), "cancelled");
            case RESERVED   -> closeActiveIfAny(rec.getId(), "reverted");
            case UNKNOWN    -> {
                /* Status no reconocido — no tocamos nada. La sesión
                 * activa (si la hay) sigue activa. Esto cubre el caso
                 * de tablas con statuses custom que no pasan por el
                 * mapeo estándar. */
            }
        }
    }

    private void openIfAbsent(Long branchId, Long recordId, Long botTableId,
                              String tableLabel, Integer personsCount, String title) {
        // Chequeo previo barato para evitar el roundtrip a la BD en el
        // 99% de los casos (estados que cambian muchas veces sin tocar
        // "en servicio"). Si el chequeo da que no hay sesión, intentamos
        // crear — la BD nos protege con el índice único contra carreras.
        Optional<TableServiceSession> existing = sessionRepo.findActiveByRecord(recordId);
        if (existing.isPresent()) {
            log.debug("[TableService] record {} ya tiene sesión activa {}, ignoro open",
                    recordId, existing.get().getId());
            return;
        }

        TableServiceSession s = new TableServiceSession();
        s.setBranchId(branchId);
        s.setRecordId(recordId);
        s.setBotTableId(botTableId);
        s.setTableLabel(tableLabel);
        s.setPersonsCount(personsCount);
        s.setTitle(title);
        s.setStartedAt(Instant.now());
        try {
            sessionRepo.save(s);
            log.info("[TableService] Sesión abierta: branch={} mesa={} record={} sessionId={}",
                    branchId, tableLabel, recordId, s.getId());
        } catch (DataIntegrityViolationException dive) {
            // Carrera: el chequeo dio "no hay sesión" pero entre eso y el
            // INSERT alguien insertó. El índice único nos protegió. OK.
            log.debug("[TableService] race condition al abrir sesión de record {}: ya había una",
                    recordId);
        }
    }

    private void closeActiveIfAny(Long recordId, String reason) {
        Optional<TableServiceSession> opt = sessionRepo.findActiveByRecord(recordId);
        if (opt.isEmpty()) return;
        TableServiceSession s = opt.get();
        s.setEndedAt(Instant.now());
        s.setEndedReason(reason);
        sessionRepo.save(s);
        long durationSec = (s.getEndedAt().toEpochMilli() - s.getStartedAt().toEpochMilli()) / 1000;
        log.info("[TableService] Sesión cerrada: id={} record={} mesa={} reason={} durationSec={}",
                s.getId(), recordId, s.getTableLabel(), reason, durationSec);
    }

    // ── Mapeo de status ──────────────────────────────────────────────────

    /**
     * Categorización funcional del status crudo del record. Mantenemos en
     * sincronía con el mapping del frontend (mapExplicitStatus en
     * SmartTablesSection.jsx).
     */
    private enum StatusKind {
        IN_SERVICE, RESERVED, COMPLETED, CANCELLED, UNKNOWN
    }

    private static StatusKind mapStatus(String raw) {
        if (raw == null || raw.isBlank()) return StatusKind.UNKNOWN;
        String norm = Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toUpperCase()
                .replaceAll("[_-]", " ")
                .trim();

        // Mismos sinónimos que el frontend.
        switch (norm) {
            case "EN SERVICIO":
            case "LLEGO":
            case "EN CURSO":
            case "OCUPADA":
                return StatusKind.IN_SERVICE;
            case "PENDIENTE":
            case "SENADA":
            case "CONFIRMADA":
            case "RESERVADA":
                return StatusKind.RESERVED;
            case "TERMINADA":
            case "CERRADA":
            case "FINALIZADA":
            case "PAGADA":
            case "LIBRE":
                return StatusKind.COMPLETED;
            case "CANCELADA":
            case "CANCELLED":
            case "NO SHOW":
            case "NO_SHOW":
                return StatusKind.CANCELLED;
            default:
                return StatusKind.UNKNOWN;
        }
    }

    // ── Helpers de JSON ──────────────────────────────────────────────────

    private static String textOrNull(JsonNode root, String field) {
        if (root == null || field == null) return null;
        JsonNode n = root.get(field);
        if (n == null || n.isNull()) return null;
        String s = n.asText("");
        return s.isBlank() ? null : s;
    }
}
