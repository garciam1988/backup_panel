package app.coincidir.api.botplatform.service;

import app.coincidir.api.botplatform.domain.BotTable;
import app.coincidir.api.botplatform.domain.BotTableRecord;
import app.coincidir.api.botplatform.domain.EmailLog;
import app.coincidir.api.botplatform.domain.EmailTemplate;
import app.coincidir.api.botplatform.repository.EmailLogRepository;
import app.coincidir.api.botplatform.repository.EmailReminderSentRepository;
import app.coincidir.api.botplatform.repository.EmailTemplateRepository;
import app.coincidir.api.email.EmailInlineImageHelper;
import app.coincidir.api.repository.BotConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    private final BotConfigRepository botConfigRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * URL base pública del backend. Se usa para construir la URL del logo
     * que se inserta en los emails como {{_logoUrl}}. Si no está configurada,
     * intenta inferir desde la request (no aplica acá porque @Async no tiene
     * request scope), o usa un fallback razonable.
     */
    @Value("${coincidir.api-base-url:}")
    private String apiBaseUrl;

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
        //
        // Enriquecemos el JSON de data con metadatos del record antes de pasarlo
        // al renderer. Esto da soporte a placeholders meta como {{_id}} (id del
        // record, usado típicamente como "número de reserva" en los emails) sin
        // tener que cambiar la firma de resolvePlaceholder. El data_json original
        // del record NO contiene `_id` porque solo guarda los campos definidos
        // en columns_json; el id vive en la columna `id` del BotTableRecord.
        JsonNode enrichedData = enrichDataWithRecordMeta(data, record);
        String subject = renderTemplate(tpl.getSubject(), enrichedData, table);
        String body = renderTemplate(tpl.getBodyHtml(), enrichedData, table);

        // 7) Construir y enviar.
        //
        // Convertimos cualquier `<img src="data:image/...">` del HTML renderizado
        // en imágenes inline referenciadas por CID, vía EmailInlineImageHelper.
        // Gmail no renderiza data URLs en el body del email (lo bloquea por
        // seguridad), así que sin esto el logo del cliente aparece como "imagen
        // rota" en Gmail web/app. Apple Mail y Outlook sí renderizan ambos
        // formatos, así que esto solo mejora; no rompe nada existente.
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            // multipart=true siempre: el helper agrega imágenes inline solo si
            // hay data URLs. Si no, queda como un MIME multipart con una sola
            // parte HTML — los clientes lo manejan idéntico al multipart=false.
            MimeMessageHelper h = new MimeMessageHelper(msg, true, "UTF-8");

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
            EmailInlineImageHelper.applyInline(h, body);
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

    /**
     * Devuelve una copia del JSON `data` enriquecida con metadatos del record
     * que NO viven dentro de data_json: id, createdAt, updatedAt. Estos meta
     * se exponen al template como placeholders con prefijo `_` (ej: `{{_id}}`).
     *
     * Por qué importa: los emails típicamente quieren mostrar un "número de
     * reserva". Antes lo guardábamos como columna manual `numero_de_reserva`
     * que el LLM rellenaba con un timestamp, pero ahora esa columna es auto
     * y el id real del record es la fuente de verdad. Sin este helper, los
     * placeholders meta caen al fallback de string vacío y el email muestra
     * "N° de reserva: #" (sin número).
     *
     * Solo modificamos un clon — el `data` original sigue intacto por si
     * algún caller lo usa más adelante. El clon usa fields prefijados con `_`
     * que no chocan con nombres de columnas reales (los nombres de columnas
     * son configurables pero por convención usamos snake_case sin guion bajo
     * inicial).
     */
    private JsonNode enrichDataWithRecordMeta(JsonNode data, BotTableRecord record) {
        if (record == null) return data;
        ObjectNode enriched;
        if (data != null && data.isObject()) {
            enriched = data.deepCopy();
        } else {
            enriched = objectMapper.createObjectNode();
        }
        if (record.getId() != null) {
            enriched.put("_id", record.getId());
        }
        if (record.getCreatedAt() != null) {
            enriched.put("_createdAt", record.getCreatedAt().toString());
        }
        if (record.getUpdatedAt() != null) {
            enriched.put("_updatedAt", record.getUpdatedAt().toString());
        }
        return enriched;
    }

    /** Renderiza placeholders {{campo}} con datos del registro. */
    String renderTemplate(String template, JsonNode data, BotTable table) {        if (template == null) return "";
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String key = m.group(1).trim();
            String value = resolvePlaceholder(key, data, table);
            // URLs no se escapan como HTML (sino "&" se vuelve "&amp;" y rompe
            // querystrings). Los demás placeholders sí se escapan para evitar
            // que un dato del usuario (ej. "<script>") se ejecute en el HTML.
            if (!isUrlPlaceholder(key)) {
                value = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Placeholders que devuelven contenido especial y por lo tanto NO deben
     * escaparse como HTML al sustituirse en el template:
     *  - _logoUrl: devuelve una URL (escapar "&" rompería querystrings).
     *  - _observacionesBlock: devuelve un bloque HTML pre-armado por el
     *    backend (escapar lo convertiría en texto plano feo).
     *
     * Para estos placeholders el caller ya garantiza la seguridad — en el
     * caso de _observacionesBlock escapamos los valores del usuario ANTES
     * de meterlos en el HTML, así no hay riesgo de XSS.
     */
    private static boolean isUrlPlaceholder(String key) {
        return "_logoUrl".equals(key) || "_observacionesBlock".equals(key);
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
        if ("_brandName".equals(key)) {
            // Nombre comercial del cliente (Brasas Argentinas, Mikhuna Nikkei).
            // Sale del bot_config.brandName (singleton id=1). Fallback al
            // botName si no está seteado.
            try {
                var cfg = botConfigRepository.findById(1L).orElse(null);
                if (cfg != null && cfg.getBrandName() != null && !cfg.getBrandName().isBlank()) {
                    return cfg.getBrandName();
                }
                if (cfg != null && cfg.getBotName() != null && !cfg.getBotName().isBlank()) {
                    return cfg.getBotName();
                }
            } catch (Exception e) {
                log.debug("No pudimos leer bot_config para _brandName: {}", e.getMessage());
            }
            return table.getName() != null ? table.getName() : "";
        }
        if ("_logoUrl".equals(key)) {
            // Estrategia: si en bot_config hay un data URL (base64), lo
            // devolvemos directo. Gmail, Outlook y Apple Mail soportan
            // data URLs en <img src>. Es el approach más portable: no
            // depende de env vars, dominios ni CORS, y el cliente de
            // email NO necesita hacer una request HTTP externa para
            // mostrar el logo (mejor privacidad y entrega más confiable).
            //
            // Si bot_config tiene una URL externa (http(s)://...), la
            // devolvemos tal cual (caso histórico).
            //
            // Si no hay logo, devolvemos string vacío (la imagen no se
            // renderiza, pero el HTML no rompe).
            try {
                var cfg = botConfigRepository.findById(1L).orElse(null);
                if (cfg != null && cfg.getLogoUrl() != null && !cfg.getLogoUrl().isBlank()) {
                    String logo = cfg.getLogoUrl();
                    // Data URL embebido — Gmail/Outlook lo soportan
                    if (logo.startsWith("data:")) return logo;
                    // URL externa http(s)://
                    if (logo.startsWith("http://") || logo.startsWith("https://")) return logo;
                }
            } catch (Exception e) {
                log.debug("No pudimos leer bot_config.logoUrl para _logoUrl: {}", e.getMessage());
            }
            // Fallback: si tenemos api-base-url, construimos URL del endpoint
            // del backend (que decodifica el data URL y lo sirve como bytes).
            if (apiBaseUrl != null && !apiBaseUrl.isBlank()) {
                return apiBaseUrl.replaceAll("/+$", "") + "/api/public/loyalty/brand-logo";
            }
            return "";
        }
        if ("_date".equals(key)) {
            return LocalDateTime.now(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
        }
        if ("_observacionesBlock".equals(key)) {
            // Bloque HTML pre-armado con la información de ocasión especial,
            // observaciones y restricciones alimentarias. Lo devolvemos como
            // string vacío si no hay nada que mostrar, así no aparece una
            // sección con labels sin valor.
            //
            // El motor de templates actual no soporta condicionales tipo
            // {{#if}}...{{/if}}, así que esta lógica vive en el backend. La
            // ventaja: el HTML del template queda 1 línea más limpia y el
            // diseño visual lo mantenemos consistente desde acá.
            return renderObservacionesBlock(data);
        }
        // Campo del registro
        JsonNode v = data.get(key);
        if (v == null || v.isNull()) return "";
        if (v.isBoolean()) return v.asBoolean() ? "Sí" : "No";
        return formatValueForDisplay(v);
    }

    /**
     * Arma el bloque HTML "Notas y preferencias" para el email, combinando
     * ocasion_especial, observaciones, y los booleanos legacy (vegetariano,
     * diabetico, celiaco) si existen.
     *
     * Devuelve "" (string vacío) si no hay NADA que mostrar — así el template
     * no renderiza una sección con labels en blanco.
     *
     * El HTML que devuelve sigue el mismo estilo del bloque "Detalles de tu
     * reserva" del email para que se vea consistente.
     */
    private String renderObservacionesBlock(JsonNode data) {
        if (data == null) return "";

        // Diccionario "amigable" de ocasiones especiales — el bot guarda
        // valores cerrados (cumpleaños, aniversario, etc) pero al cliente
        // queremos mostrarle algo lindo.
        java.util.Map<String, String> ocasionLabels = new java.util.HashMap<>();
        ocasionLabels.put("cumpleaños", "🎂 Cumpleaños");
        ocasionLabels.put("aniversario", "💕 Aniversario");
        ocasionLabels.put("cita", "💕 Cita");
        ocasionLabels.put("negocios", "💼 Reunión de negocios");
        ocasionLabels.put("despedida", "🎉 Despedida");
        ocasionLabels.put("celebracion_grupo", "✨ Celebración grupal");
        ocasionLabels.put("otra", "✨ Ocasión especial");

        java.util.List<String> rows = new java.util.ArrayList<>();

        // 1) Ocasión especial — usar diccionario para mostrar emoji + label legible.
        //    Va como fila aparte porque NO es info dietaria/operativa — es para
        //    que el restaurant prepare algo especial (decoración, torta, etc).
        String ocasion = readText(data, "ocasion_especial");
        if (ocasion != null && !ocasion.isBlank()) {
            String label = ocasionLabels.getOrDefault(ocasion.toLowerCase().trim(), "✨ " + ocasion);
            rows.add(row("Ocasión", label));
        }

        // 2) Notas unificadas — combina restricciones dietarias (booleanos
        //    legacy: vegetariano, diabetico, celiaco) con el texto libre de
        //    `observaciones`. La idea es que el restaurant tenga TODA la info
        //    operativa relevante en un solo lugar para no perderla cuando
        //    está apurado preparando la mesa.
        //
        //    Antes cada restricción era una fila propia (🥗 Vegetariano: Sí,
        //    🩺 Diabético: Sí, etc.) lo que generaba ruido visual y hacía
        //    fácil que el operador se saltee la fila de observaciones. Ahora
        //    todo va junto, prefijado con su emoji para que escanee rápido.
        //
        //    NOTA: si el bot ya escribió la restricción en `observaciones`
        //    (texto libre) Y además marcó el booleano en true, va a aparecer
        //    duplicada. Aceptamos eso a propósito — prefiero un poco de
        //    redundancia que perder un celíaco por un keyword-matching
        //    frágil.
        java.util.List<String> notaParts = new java.util.ArrayList<>();
        if (isTrue(data, "vegetariano")) notaParts.add("🥗 Vegetariano");
        if (isTrue(data, "diabetico"))   notaParts.add("🩺 Diabético");
        if (isTrue(data, "celiaco"))     notaParts.add("🌾 Celíaco");

        String obs = readText(data, "observaciones");
        if (obs != null && !obs.isBlank()) {
            notaParts.add(obs.trim());
        }

        if (!notaParts.isEmpty()) {
            // Separamos restricciones con " · " y el texto libre con un salto
            // visual. Construimos así:
            //   "🥗 Vegetariano · 🌾 Celíaco · 1 persona celíaca en el grupo..."
            // En HTML respetamos saltos de línea del texto libre con <br>.
            String combined = String.join(" · ", notaParts);
            String safe = combined.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
            rows.add(
                "<tr><td style=\"padding:6px 0;color:#666;width:42%;vertical-align:top;\">📝 Notas</td>" +
                "<td style=\"padding:6px 0;font-weight:600;line-height:1.5;\">" + safe + "</td></tr>"
            );
        }

        // Si no hay NADA para mostrar, devolvemos vacío. El template queda
        // sin la sección y el email se ve más limpio.
        if (rows.isEmpty()) return "";

        // Wrapper visual — mismo estilo que el bloque "Detalles" para mantener
        // consistencia. Card con padding y borde a la izquierda.
        StringBuilder html = new StringBuilder();
        html.append("<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background-color:#fef7ed;border-left:4px solid #f59e0b;border-radius:6px;margin-top:6px;\">");
        html.append("<tr><td style=\"padding:18px 22px;\">");
        html.append("<div style=\"font-size:11px;color:#92400e;font-weight:bold;letter-spacing:1.5px;text-transform:uppercase;margin-bottom:10px;\">✨ Notas y preferencias</div>");
        html.append("<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"font-size:14px;color:#1a1a1a;\">");
        for (String r : rows) html.append(r);
        html.append("</table>");
        html.append("</td></tr>");
        html.append("</table>");
        return html.toString();
    }

    /** Helper: fila clave/valor del bloque de observaciones. */
    private String row(String label, String value) {
        String safe = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<tr><td style=\"padding:6px 0;color:#666;width:42%;\">" + label + "</td>" +
               "<td style=\"padding:6px 0;font-weight:bold;\">" + safe + "</td></tr>";
    }

    /** Lee un campo como texto, devolviendo null si no existe o es null. */
    private String readText(JsonNode data, String key) {
        if (data == null) return null;
        JsonNode v = data.get(key);
        if (v == null || v.isNull()) return null;
        return v.asText();
    }

    /** True si el campo existe y es un boolean true (o el string "true"/"sí"). */
    private boolean isTrue(JsonNode data, String key) {
        if (data == null) return false;
        JsonNode v = data.get(key);
        if (v == null || v.isNull()) return false;
        if (v.isBoolean()) return v.asBoolean();
        if (v.isTextual()) {
            String s = v.asText().toLowerCase().trim();
            return "true".equals(s) || "sí".equals(s) || "si".equals(s) || "yes".equals(s);
        }
        return false;
    }

    /**
     * Convierte un valor del registro a string "lindo" para mostrar en el email.
     *
     * Reglas:
     *  - Números enteros disfrazados de decimal (2.0, 9.0): se muestran sin el ".0"
     *  - Strings con formato ISO datetime (2026-04-27T21:30:00): se reformatean
     *    a "27/04/2026 21:30 hs"
     *  - Strings con formato ISO date (2026-04-27): se reformatean a "27/04/2026"
     *  - Resto: se devuelve tal cual
     *
     * Esto es necesario porque el bot guarda "cantidad de personas" como number
     * (queda 2.0 en el JSON) y "fecha y hora reserva" como datetime ISO. Sin
     * este formateo, los emails quedan con texto técnico.
     */
    private String formatValueForDisplay(JsonNode v) {
        // Caso 1: número
        if (v.isNumber()) {
            double d = v.asDouble();
            // Si es un entero (2.0, 9.0, 1500.0) lo mostramos sin decimales
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return String.valueOf((long) d);
            }
            // Si tiene decimales reales (12.50), los mostramos con 2 dígitos máximo
            return String.format(java.util.Locale.US, "%.2f", d).replaceAll("0+$", "").replaceAll("\\.$", "");
        }

        String s = v.asText();
        if (s == null || s.isEmpty()) return "";

        // Caso 2: ISO datetime "2026-04-27T21:30:00" o "2026-04-27T21:30:00.000"
        // El bot guarda los datetime así porque MySQL los devuelve en formato ISO.
        if (s.matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:?\\d{2})?$")) {
            try {
                // Parseo flexible: cortamos en el primer punto/zona y dejamos solo
                // la parte hasta los segundos
                String clean = s.split("\\.")[0].replaceAll("[Z+-].*$", "");
                java.time.LocalDateTime dt = java.time.LocalDateTime.parse(clean);
                return dt.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")) + " hs";
            } catch (Exception ignore) { /* fall through */ }
        }

        // Caso 3: ISO date "2026-04-27"
        if (s.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
            try {
                java.time.LocalDate d = java.time.LocalDate.parse(s);
                return d.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
            } catch (Exception ignore) { /* fall through */ }
        }

        // Caso 4: string normal — devolver tal cual
        return s;
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

        JsonNode enrichedData = enrichDataWithRecordMeta(data, record);
        String subject = renderTemplate(tpl.getSubject(), enrichedData, table);
        String body = renderTemplate(tpl.getBodyHtml(), enrichedData, table);

        try {
            // Mismo tratamiento de data URLs → CID que en el envío real, vía
            // EmailInlineImageHelper compartido. Sin esto, los emails de
            // "Probar" no renderizan el logo en Gmail web/app.
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(msg, true, "UTF-8");

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
            EmailInlineImageHelper.applyInline(h, body);
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
