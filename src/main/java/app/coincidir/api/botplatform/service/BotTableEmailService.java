package app.coincidir.api.botplatform.service;

import app.coincidir.api.botplatform.domain.BotTable;
import app.coincidir.api.botplatform.domain.BotTableRecord;
import app.coincidir.api.botplatform.domain.EmailLog;
import app.coincidir.api.botplatform.domain.EmailTemplate;
import app.coincidir.api.botplatform.repository.EmailLogRepository;
import app.coincidir.api.botplatform.repository.EmailReminderSentRepository;
import app.coincidir.api.botplatform.repository.EmailTemplateRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BotTableEmailService — envía emails transaccionales cuando se crean/modifican/cancelan
 * registros en una BotTable. Reusa el JavaMailSender configurado en el backend
 * (info@yes-traveluy.com vía Railway Pro plan SMTP).
 *
 * Eventos soportados: "created" | "updated" | "cancelled" | "reminder".
 *
 * Rate limiting:
 *   - Max 1 email por minuto al MISMO destinatario (evita spam si el bot revoluciona).
 *   - Max 100 emails por día (cap global del bot, defensa última).
 *
 * Si no hay template para el evento, no falla — simplemente no envía.
 * Si el rate limit pega, registra un log con ok=false y "rate-limited" como error.
 *
 * @Async: el envío corre en thread separado para no bloquear la respuesta del bot.
 * @Transactional: para que la lectura del template + log queden en una sola tx.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BotTableEmailService {

    private final EmailTemplateRepository templateRepo;
    private final EmailLogRepository logRepo;
    private final EmailReminderSentRepository reminderRepo;
    private final JavaMailSender mailSender;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Self-injection (lazy para evitar ciclo en construcción) — necesario para
     * que la invocación a fireEvent() desde el @TransactionalEventListener pase
     * por el proxy de Spring. Si llamáramos directamente a fireEvent (this.fireEvent),
     * Spring NO aplica @Async ni @Transactional (self-invocation). Resultado: el
     * envío corría sincrónicamente y SIN transacción, por lo que JPA no podía
     * leer el template ni escribir el EmailLog → el evento quedaba mudo.
     */
    private BotTableEmailService self;

    @Autowired
    public void setSelf(@Lazy BotTableEmailService self) {
        this.self = self;
    }

    @Value("${coincidir.mail-from:YES Travel <info@yes-traveluy.com>}")
    private String defaultMailFrom;

    @Value("${coincidir.bot-table-email.daily-cap:100}")
    private int dailyCap;

    @Value("${coincidir.bot-table-email.per-recipient-cooldown-seconds:10}")
    private int perRecipientCooldownSeconds;

    /** Patrón para placeholders {{nombre}}. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_ \\-]+)\\s*\\}\\}");

    /**
     * Listener del evento BotTableChangeEvent. Escucha en POST_COMMIT (default
     * de @TransactionalEventListener) para que solo dispare emails si la
     * transacción que cambió el registro efectivamente comiteó. Si la tx hace
     * rollback, el email NO se manda — lo correcto.
     *
     * El método @Async vive en este mismo bean para no abrir otro service.
     * Spring resuelve la combinación de @TransactionalEventListener + @Async
     * sin problema.
     */
    @org.springframework.transaction.event.TransactionalEventListener
    public void onBotTableChange(BotTableChangeEvent ev) {
        // Si el registro se canceló (delete) o se actualizó (update con
        // posible cambio de fecha), borramos la marca de "ya recordado"
        // para que el ReminderJob pueda volver a evaluarlo desde cero.
        if (ev.record != null && ev.record.getId() != null
                && ("cancelled".equals(ev.event) || "updated".equals(ev.event))) {
            try {
                int n = reminderRepo.deleteByRecordId(ev.record.getId());
                if (n > 0) log.debug("[BotTableEmail] borré {} marca(s) de recordatorio del registro {} ({})",
                        n, ev.record.getId(), ev.event);
            } catch (Exception e) {
                log.warn("[BotTableEmail] no pude borrar marca de recordatorio: {}", e.getMessage());
            }
        }
        // El método dentro hace @Async → corre en thread aparte sin bloquear.
        // Se invoca a través del proxy (self) para que Spring aplique @Async y
        // @Transactional correctamente — una llamada this.fireEvent(...) no las
        // aplicaría por self-invocation.
        self.fireEvent(ev.table, ev.record, ev.event);
    }

    /**
     * Dispara un email en función del evento que ocurrió en la tabla.
     * Es @Async — la llamada vuelve inmediatamente y el envío corre aparte.
     */
    @Async
    @Transactional
    public void fireEvent(BotTable table, BotTableRecord record, String event) {
        try {
            fireEventSync(table, record, event);
        } catch (Exception e) {
            log.warn("[BotTableEmail] error en fireEvent table={} event={}: {}",
                    table.getSlug(), event, e.getMessage());
        }
    }

    /** Versión sincrónica (útil para tests + para el job de recordatorios). */
    @Transactional
    public void fireEventSync(BotTable table, BotTableRecord record, String event) {
        if (table == null || record == null || event == null) return;

        // 1) Verificar columna de email en la tabla
        String emailCol = table.getEmailColumn();
        if (emailCol == null || emailCol.isBlank()) {
            log.info("[BotTableEmail][SKIP] tabla={} sin emailColumn — no se envía mail (evento {})",
                    table.getSlug(), event);
            writeLog(table, record, null, event, null, false,
                    "skip: la tabla no tiene columna email configurada");
            return;
        }

        // 2) Buscar template para este evento
        Optional<EmailTemplate> opt = templateRepo.findByTableIdAndEvent(table.getId(), event);
        if (opt.isEmpty()) {
            log.info("[BotTableEmail][SKIP] tabla={} no tiene template para evento '{}' — no se envía mail",
                    table.getSlug(), event);
            writeLog(table, record, null, event, null, false,
                    "skip: no hay template configurado para evento '" + event + "'");
            return;
        }
        EmailTemplate tpl = opt.get();
        if (!Boolean.TRUE.equals(tpl.getActive())) {
            log.info("[BotTableEmail][SKIP] tabla={} template '{}' está apagado — no se envía mail",
                    table.getSlug(), event);
            writeLog(table, record, tpl, event, null, false,
                    "skip: template '" + event + "' está marcado como inactivo");
            return;
        }

        // 3) Extraer email del registro
        JsonNode data;
        try { data = objectMapper.readTree(record.getDataJson()); }
        catch (Exception e) {
            writeLog(table, record, tpl, event, null, false, "data_json inválido");
            return;
        }
        JsonNode emailNode = data.get(emailCol);
        String recipient = emailNode == null || emailNode.isNull() ? null : emailNode.asText("").trim();
        if (recipient == null || recipient.isBlank() || !isValidEmail(recipient)) {
            log.info("[BotTableEmail][SKIP] tabla={} record={} columna='{}' valor='{}' — email inválido o vacío",
                    table.getSlug(), record.getId(), emailCol, recipient);
            writeLog(table, record, tpl, event, recipient, false,
                    "skip: email inválido o vacío en columna '" + emailCol + "' del registro");
            return;
        }

        // 4) Rate limit — daily cap global
        long sentToday = logRepo.countSince(Instant.now().minus(Duration.ofDays(1)));
        if (sentToday >= dailyCap) {
            writeLog(table, record, tpl, event, recipient, false,
                    "rate-limited: daily cap " + dailyCap + " alcanzado");
            log.warn("[BotTableEmail] daily cap alcanzado ({}/{}) — bloqueando envío a {}",
                    sentToday, dailyCap, recipient);
            return;
        }

        // 5) Rate limit — cooldown por destinatario
        long sentToRecipient = logRepo.countByRecipientSince(recipient,
                Instant.now().minus(Duration.ofSeconds(perRecipientCooldownSeconds)));
        if (sentToRecipient > 0) {
            writeLog(table, record, tpl, event, recipient, false,
                    "rate-limited: cooldown por destinatario activo");
            log.info("[BotTableEmail] cooldown activo para {} — skip", recipient);
            return;
        }

        // 6) Render del template
        String subject = renderTemplate(tpl.getSubject(), data, table);
        String body = renderTemplate(tpl.getBodyHtml(), data, table);

        // 7) Construir y enviar
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(msg, false, "UTF-8");

            // From: si el template tiene fromDisplayName, usamos eso con el email default.
            //   "Brasas Argentinas <info@yes-traveluy.com>"
            // Si no, usamos el default tal cual.
            String from = defaultMailFrom;
            if (tpl.getFromDisplayName() != null && !tpl.getFromDisplayName().isBlank()) {
                String email = extractEmailAddress(defaultMailFrom);
                from = tpl.getFromDisplayName().trim() + " <" + email + ">";
            }
            h.setFrom(from);
            h.setTo(recipient);

            // Reply-To del cliente (si está configurado en el template)
            if (tpl.getReplyTo() != null && !tpl.getReplyTo().isBlank() && isValidEmail(tpl.getReplyTo().trim())) {
                h.setReplyTo(tpl.getReplyTo().trim());
            }

            h.setSubject(subject);
            h.setText(body, true);
            mailSender.send(msg);
            writeLog(table, record, tpl, event, recipient, true, null);
            log.info("[BotTableEmail] enviado: tabla={} record={} evento={} a={}",
                    table.getSlug(), record.getId(), event, recipient);
        } catch (Exception ex) {
            String cause = ex.getCause() != null ? ex.getCause().getMessage() : "";
            String detail = ex.getMessage() + (cause.isEmpty() ? "" : " | " + cause);
            writeLog(table, record, tpl, event, recipient, false, detail);
            log.warn("[BotTableEmail] fallo envío a {}: {}", recipient, detail);
        }
    }

    /** Renderiza placeholders {{campo}} con datos del registro. */
    String renderTemplate(String template, JsonNode data, BotTable table) {
        if (template == null) return "";
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String key = m.group(1).trim();
            String value = resolvePlaceholder(key, data, table);
            // Escape minimal para HTML (<, >, &) — los emails son HTML
            value = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    private String resolvePlaceholder(String key, JsonNode data, BotTable table) {
        // Variables especiales
        if ("_id".equals(key)) {
            return data.has("_id") ? data.get("_id").asText() : "";
        }
        if ("_botName".equals(key)) {
            // Usa el name del bot como fallback. Para versión más completa podríamos
            // inyectar el BotConfig pero por ahora con table.name alcanza.
            return table.getName() != null ? table.getName() : "";
        }
        if ("_date".equals(key)) {
            return LocalDateTime.now(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
        }
        // Campo del registro
        JsonNode v = data.get(key);
        if (v == null || v.isNull()) return "";
        if (v.isBoolean()) return v.asBoolean() ? "Sí" : "No";
        return v.asText();
    }

    /**
     * Envío de TEST: usa los datos del record para renderizar placeholders pero
     * manda el mail a la casilla que pasa el admin (no a la del registro).
     * Saltea el rate limiting porque es disparado manualmente desde admin.
     * Igual queda registrado en email_log con event="test" para auditoría.
     */
    @Transactional
    public TestSendResult sendTestToAddress(BotTable table, BotTableRecord record,
                                            EmailTemplate tpl, String toOverride) {
        TestSendResult res = new TestSendResult();
        if (toOverride == null || !isValidEmail(toOverride.trim())) {
            res.ok = false;
            res.error = "Email destinatario inválido";
            return res;
        }
        String recipient = toOverride.trim();

        // Render del template con los datos del record (si lo hay)
        JsonNode data;
        try {
            data = record != null && record.getDataJson() != null
                    ? objectMapper.readTree(record.getDataJson())
                    : objectMapper.createObjectNode();
        } catch (Exception e) {
            res.ok = false;
            res.error = "data_json inválido en el record";
            return res;
        }

        String subject = renderTemplate(tpl.getSubject(), data, table);
        String body = renderTemplate(tpl.getBodyHtml(), data, table);

        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(msg, false, "UTF-8");

            String from = defaultMailFrom;
            if (tpl.getFromDisplayName() != null && !tpl.getFromDisplayName().isBlank()) {
                String email = extractEmailAddress(defaultMailFrom);
                from = tpl.getFromDisplayName().trim() + " <" + email + ">";
            }
            h.setFrom(from);
            h.setTo(recipient);

            if (tpl.getReplyTo() != null && !tpl.getReplyTo().isBlank() && isValidEmail(tpl.getReplyTo().trim())) {
                h.setReplyTo(tpl.getReplyTo().trim());
            }

            // Le agregamos un prefijo "[TEST]" al subject para que el admin
            // distinga el mail real de los de prueba en su casilla.
            h.setSubject("[TEST] " + subject);
            h.setText(body, true);
            mailSender.send(msg);
            writeLog(table, record, tpl, "test", recipient, true, null);
            log.info("[BotTableEmail] TEST enviado: tabla={} a={}", table.getSlug(), recipient);
            res.ok = true;
            res.recipient = recipient;
            return res;
        } catch (Exception ex) {
            String cause = ex.getCause() != null ? ex.getCause().getMessage() : "";
            String detail = ex.getMessage() + (cause.isEmpty() ? "" : " | " + cause);
            writeLog(table, record, tpl, "test", recipient, false, detail);
            res.ok = false;
            res.error = detail;
            return res;
        }
    }

    public static class TestSendResult {
        public boolean ok;
        public String recipient;
        public String error;
    }

    private void writeLog(BotTable table, BotTableRecord record, EmailTemplate tpl,
                          String event, String recipient, boolean ok, String error) {
        try {
            EmailLog l = new EmailLog();
            l.setTableId(table != null ? table.getId() : null);
            l.setRecordId(record != null ? record.getId() : null);
            l.setTemplateId(tpl != null ? tpl.getId() : null);
            l.setEvent(event);
            l.setRecipient(recipient);
            l.setSubject(tpl != null ? truncate(tpl.getSubject(), 300) : null);
            l.setOk(ok);
            l.setError(truncate(error, 500));
            logRepo.save(l);
        } catch (Exception e) {
            log.warn("[BotTableEmail] no pude escribir el log: {}", e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    /** Validación mínima de email — formato local@domain.tld. Public porque
     *  también lo usa BotTableAdminController para validar el replyTo al guardar
     *  un template. */
    public static boolean isValidEmail(String s) {
        if (s == null) return false;
        return s.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    }

    /** Extrae "info@yes-traveluy.com" de "YES Travel <info@yes-traveluy.com>". */
    private static String extractEmailAddress(String fromHeader) {
        if (fromHeader == null) return "";
        Matcher m = Pattern.compile("<([^>]+)>").matcher(fromHeader);
        if (m.find()) return m.group(1).trim();
        return fromHeader.trim();
    }
}
