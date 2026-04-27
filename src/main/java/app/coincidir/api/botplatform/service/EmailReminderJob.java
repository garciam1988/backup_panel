package app.coincidir.api.botplatform.service;

import app.coincidir.api.botplatform.domain.BotTable;
import app.coincidir.api.botplatform.domain.BotTableRecord;
import app.coincidir.api.botplatform.domain.EmailReminderSent;
import app.coincidir.api.botplatform.domain.EmailTemplate;
import app.coincidir.api.botplatform.repository.BotTableRecordRepository;
import app.coincidir.api.botplatform.repository.BotTableRepository;
import app.coincidir.api.botplatform.repository.EmailReminderSentRepository;
import app.coincidir.api.botplatform.repository.EmailTemplateRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * EmailReminderJob — corre periódicamente (cada 15 min por default) y dispara
 * recordatorios para registros cuya fecha de referencia esté próxima.
 *
 * Lógica:
 *   1. Para cada BotTable activa que tenga emailColumn + reminderDateColumn +
 *      reminderHoursBefore + un EmailTemplate "reminder" activo:
 *   2. Calcular ventana objetivo: from = now + (hoursBefore - 1h),
 *                                  to   = now + hoursBefore.
 *   3. Para cada record de la tabla cuya fecha caiga en esa ventana:
 *      a. Si NO hay marca en EmailReminderSent → mandar mail + crear marca.
 *      b. Si SÍ hay marca pero la fecha cambió desde entonces → borrar marca,
 *         mandar mail nuevo + crear marca con la fecha nueva.
 *      c. Si SÍ hay marca y la fecha es la misma → skip.
 *
 * Se ejecuta con @Scheduled a cron fijo. La granularidad es 15 minutos por
 * default. Eso significa que si configurás "24hs antes" para un evento de
 * las 21:00, el recordatorio llegará en algún momento entre las 20:45 y
 * 21:00 del día anterior. Buena para casos típicos.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailReminderJob {

    private final BotTableRepository tableRepo;
    private final BotTableRecordRepository recordRepo;
    private final EmailTemplateRepository templateRepo;
    private final EmailReminderSentRepository reminderRepo;
    private final BotTableEmailService emailService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Cron: cada 15 minutos en el minuto 0, 15, 30, 45 de cada hora. */
    @Scheduled(cron = "0 0/15 * * * *")
    @Transactional
    public void runReminders() {
        log.debug("[ReminderJob] tick");
        List<BotTable> tables = tableRepo.findByActiveTrueOrderByNameAsc();
        for (BotTable table : tables) {
            try { processTable(table); }
            catch (Exception e) { log.warn("[ReminderJob] error en tabla {}: {}", table.getSlug(), e.getMessage()); }
        }
    }

    /**
     * Procesa una tabla: revisa todos sus registros y dispara recordatorios
     * para los que están en la ventana objetivo y todavía no fueron recordados.
     *
     * Se llama también desde el endpoint manual /run-reminders del controller
     * (útil para testing sin esperar al cron).
     */
    @Transactional
    public ProcessResult processTable(BotTable table) {
        ProcessResult result = new ProcessResult();
        result.tableSlug = table.getSlug();

        // Pre-checks: tabla tiene config completa
        if (table.getEmailColumn() == null || table.getEmailColumn().isBlank()) return result;
        if (table.getReminderDateColumn() == null || table.getReminderDateColumn().isBlank()) return result;
        Integer hoursBefore = table.getReminderHoursBefore();
        if (hoursBefore == null || hoursBefore <= 0) return result;

        // Template "reminder" activo
        Optional<EmailTemplate> tplOpt = templateRepo.findByTableIdAndEvent(table.getId(), "reminder");
        if (tplOpt.isEmpty() || !Boolean.TRUE.equals(tplOpt.get().getActive())) return result;

        // Ventana objetivo
        Instant now = Instant.now();
        Instant windowEnd = now.plus(Duration.ofHours(hoursBefore));
        Instant windowStart = windowEnd.minus(Duration.ofMinutes(15));
        // Pequeño buffer adicional al inicio (5 min) por si hubo skew o el job
        // se ejecuta unos segundos tarde — preferimos mandar un poco antes a
        // perder el envío.
        windowStart = windowStart.minus(Duration.ofMinutes(5));

        log.debug("[ReminderJob] tabla={} ventana={}..{} ({}hs antes)",
                table.getSlug(), windowStart, windowEnd, hoursBefore);

        String dateCol = table.getReminderDateColumn();
        List<BotTableRecord> records = recordRepo.findByTableIdOrderByCreatedAtDesc(table.getId());

        for (BotTableRecord rec : records) {
            try {
                JsonNode data = objectMapper.readTree(rec.getDataJson());
                JsonNode dateNode = data.get(dateCol);
                if (dateNode == null || dateNode.isNull()) continue;
                String dateStr = dateNode.asText("").trim();
                if (dateStr.isEmpty()) continue;

                Instant recordDate = parseDateToInstant(dateStr);
                if (recordDate == null) continue;
                if (recordDate.isBefore(windowStart) || recordDate.isAfter(windowEnd)) continue;

                // Está en ventana — chequear marca de "ya enviado"
                Optional<EmailReminderSent> markOpt = reminderRepo
                        .findByTableIdAndRecordId(table.getId(), rec.getId());
                if (markOpt.isPresent()) {
                    EmailReminderSent mark = markOpt.get();
                    // Si la fecha del registro cambió desde el último envío,
                    // borramos la marca y volvemos a enviar.
                    if (dateStr.equals(mark.getForDate())) {
                        result.skipped++;
                        continue;
                    }
                    log.info("[ReminderJob] fecha cambió ({} → {}) en registro {} — re-enviando recordatorio",
                            mark.getForDate(), dateStr, rec.getId());
                    reminderRepo.delete(mark);
                }

                // Disparar el envío sincrónico (estamos dentro de un cron, no
                // hay request HTTP que esperar). El service ya escribe su log.
                emailService.fireEventSync(table, rec, "reminder");

                // Crear marca para no volver a enviar
                EmailReminderSent newMark = new EmailReminderSent();
                newMark.setTableId(table.getId());
                newMark.setRecordId(rec.getId());
                newMark.setForDate(dateStr);
                reminderRepo.save(newMark);
                result.sent++;

            } catch (Exception e) {
                log.warn("[ReminderJob] error en registro {} de {}: {}",
                        rec.getId(), table.getSlug(), e.getMessage());
                result.errors++;
            }
        }
        if (result.sent > 0 || result.skipped > 0) {
            log.info("[ReminderJob] tabla={} sent={} skipped={} errors={}",
                    table.getSlug(), result.sent, result.skipped, result.errors);
        }
        return result;
    }

    /**
     * Parsea las distintas formas en que la fecha puede estar guardada.
     * Soportamos:
     *   - "yyyy-MM-dd"               (date)
     *   - "yyyy-MM-ddTHH:mm:ss"      (datetime LOCAL, sin zona)
     *   - "yyyy-MM-ddTHH:mm:ssZ"     (datetime UTC, legacy)
     *   - "yyyy-MM-ddTHH:mm:ss±HH:mm" (datetime con offset)
     *
     * Para "date" sin hora, asumimos que el evento es a las 09:00 del
     * día (un default razonable — no tiene sentido mandar recordatorio
     * para "20/04" a las 00:00).
     */
    static Instant parseDateToInstant(String s) {
        if (s == null || s.isBlank()) return null;
        // datetime con zona (Z u offset)
        if (s.matches(".*[Zz]$") || s.matches(".*[+-]\\d{2}:?\\d{2}$")) {
            try { return Instant.parse(s); } catch (Exception ignored) {}
        }
        // datetime naive (sin zona) → asumimos hora local del servidor
        try {
            LocalDateTime ldt = LocalDateTime.parse(s);
            return ldt.atZone(ZoneId.systemDefault()).toInstant();
        } catch (Exception ignored) {}
        // date pura → asumimos 09:00 local
        try {
            LocalDate d = LocalDate.parse(s);
            return d.atTime(LocalTime.of(9, 0)).atZone(ZoneId.systemDefault()).toInstant();
        } catch (Exception ignored) {}
        return null;
    }

    public static class ProcessResult {
        public String tableSlug;
        public int sent;
        public int skipped;
        public int errors;
    }
}
