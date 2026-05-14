package app.coincidir.api.coinbot;

import app.coincidir.api.botplatform.domain.BotTable;
import app.coincidir.api.botplatform.domain.BotTableRecord;
import app.coincidir.api.botplatform.repository.BotTableRecordRepository;
import app.coincidir.api.botplatform.repository.BotTableRepository;
import app.coincidir.api.domain.ConversationLog;
import app.coincidir.api.marketing.domain.LoyaltyCustomer;
import app.coincidir.api.marketing.repository.LoyaltyCustomerRepository;
import app.coincidir.api.marketing.util.PhoneNormalizer;
import app.coincidir.api.repository.ConversationLogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ClientesService — agrupa ConversationLog + BotTableRecord (reservas) +
 * loyalty_customer (módulo Marketing) por cliente y calcula las métricas
 * para el módulo /admin > Clientes.
 *
 * Identidad de cliente: combinación de nombre+apellido+(email O teléfono).
 * Devuelve clientes que tengan:
 *   - Al menos 1 RESERVA REAL (un BotTableRecord en alguna tabla con
 *     emailColumn configurada que matchee con sus datos), o
 *   - Estén enrolados en el módulo Marketing (loyalty_customer activo),
 *     incluso si todavía no reservaron.
 *
 * El matching es case-insensitive, normalizado (sin tildes, sin espacios extra).
 * Los clientes loyalty se matchean con los existentes por teléfono normalizado
 * (E.164). Si un loyalty_customer ya está cubierto por una reserva, se enriquece
 * el DTO existente con la info del programa; si no, se agrega como cliente nuevo
 * con source="MARKETING_ONLY".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClientesService {

    private final ConversationLogRepository convRepo;
    private final BotTableRepository tableRepo;
    private final BotTableRecordRepository recordRepo;
    private final LoyaltyCustomerRepository loyaltyRepo;
    private final ObjectMapper objectMapper;

    /**
     * Devuelve la lista completa de clientes con reserva real, sin paginar.
     * El controller se encarga del filtrado por texto y la paginación.
     *
     * El cómputo se hace en memoria — para volúmenes de 10k clientes esto está bien.
     * Si crece a 100k+ habría que mover el matching a SQL.
     */
    @Transactional(readOnly = true)
    public List<ClienteDto> listAll() {
        List<ConversationLog> conversations = convRepo.findAll();
        List<BotTableRecord> reservations = loadAllReservations();

        // Indexar reservas por clave de cliente para lookup rápido
        Map<String, List<ReservaInfo>> reservasByKey = new HashMap<>();
        for (BotTableRecord rec : reservations) {
            ReservaInfo info = parseReservaRecord(rec);
            if (info == null || info.clienteKey == null) continue;
            reservasByKey.computeIfAbsent(info.clienteKey, k -> new ArrayList<>()).add(info);
        }

        // Agrupar conversaciones por clave de cliente
        Map<String, List<ConversationLog>> convsByKey = new HashMap<>();
        for (ConversationLog c : conversations) {
            String key = buildClientKey(
                    c.getClientFirstName(),
                    c.getClientLastName(),
                    extractEmailFromExtra(c.getClientExtraJson()),
                    extractPhoneFromExtra(c.getClientExtraJson())
            );
            if (key == null) continue;
            convsByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
        }

        // Construir DTO por cliente — primero los que tienen al menos 1 reserva real
        List<ClienteDto> out = new ArrayList<>();
        Set<String> processedKeys = new HashSet<>();
        // Tracking de phones que ya están cubiertos por alguna reserva, para no
        // duplicar clientes al sumar loyalty_customer abajo.
        Set<String> coveredPhones = new HashSet<>();

        for (Map.Entry<String, List<ReservaInfo>> e : reservasByKey.entrySet()) {
            String key = e.getKey();
            if (processedKeys.contains(key)) continue;
            processedKeys.add(key);

            List<ReservaInfo> reservas = e.getValue();
            List<ConversationLog> convs = convsByKey.getOrDefault(key, Collections.emptyList());
            ClienteDto dto = buildClienteDto(key, convs, reservas);
            if (dto != null) {
                dto.source = "BOT";  // tiene reserva real del bot
                out.add(dto);

                // Marcar el phone normalizado como cubierto para el merge con loyalty
                if (dto.telefono != null) {
                    String np = PhoneNormalizer.normalize(dto.telefono);
                    if (np != null) coveredPhones.add(np);
                }
            }
        }

        // ── Reverso: incluir loyalty_customer del módulo Marketing ──────────
        // Para cada cliente loyalty:
        //   - Si su phone (normalizado) ya está cubierto por una reserva del
        //     bot → enriquecemos el DTO existente con loyaltyEnrolled=true.
        //   - Si NO está cubierto → agregamos un cliente nuevo con
        //     source="MARKETING_ONLY".
        try {
            List<LoyaltyCustomer> loyalty = loyaltyRepo.findByDeletedAtIsNullOrderByEnrolledAtDesc();
            for (LoyaltyCustomer lc : loyalty) {
                String np = lc.getPhone(); // ya está en E.164
                if (np == null) continue;

                if (coveredPhones.contains(np)) {
                    // Ya existe como cliente del bot — enriquecer
                    for (ClienteDto existing : out) {
                        String ep = existing.telefono != null
                            ? PhoneNormalizer.normalize(existing.telefono) : null;
                        if (np.equals(ep)) {
                            existing.loyaltyEnrolled = true;
                            existing.loyaltyCustomerHash = lc.getCustomerHash();
                            // Si el cliente del bot no tenía email pero el loyalty sí, completar
                            if ((existing.email == null || existing.email.isBlank()) && lc.getEmail() != null) {
                                existing.email = lc.getEmail();
                            }
                            existing.source = "BOT_AND_MARKETING";
                            break;
                        }
                    }
                } else {
                    // Marketing-only: agregar como cliente nuevo
                    ClienteDto dto = new ClienteDto();
                    dto.clienteKey = "loyalty:" + lc.getCustomerHash();
                    dto.nombre = lc.getFirstName();
                    dto.apellido = lc.getLastName();
                    dto.email = lc.getEmail();
                    dto.telefono = lc.getPhone();
                    dto.reservasCount = 0;
                    dto.conversacionesCount = 0;
                    dto.firstSeenAt = lc.getEnrolledAt();
                    dto.lastSeenAt = lc.getLastActivityAt() != null
                        ? lc.getLastActivityAt() : lc.getEnrolledAt();
                    dto.loyaltyEnrolled = true;
                    dto.loyaltyCustomerHash = lc.getCustomerHash();
                    dto.source = "MARKETING_ONLY";
                    out.add(dto);
                    coveredPhones.add(np);
                }
            }
        } catch (Exception e) {
            // Si Marketing falla por cualquier motivo, NO romper la vista Clientes.
            log.warn("[Clientes] Error mergeando loyalty_customer: {}", e.getMessage());
        }

        // Ordenar por última actividad descendente (más reciente primero)
        out.sort((a, b) -> {
            Instant ai = a.lastSeenAt != null ? a.lastSeenAt : Instant.EPOCH;
            Instant bi = b.lastSeenAt != null ? b.lastSeenAt : Instant.EPOCH;
            return bi.compareTo(ai);
        });
        return out;
    }

    /**
     * Devuelve métricas agregadas globales (sin filtros) para el header del módulo.
     */
    @Transactional(readOnly = true)
    public StatsDto stats() {
        List<ClienteDto> all = listAll();
        StatsDto s = new StatsDto();
        s.totalClientes = all.size();
        s.totalReservas = all.stream().mapToInt(c -> c.reservasCount).sum();
        s.totalConversaciones = all.stream().mapToInt(c -> c.conversacionesCount).sum();
        s.clientesRecurrentes = (int) all.stream().filter(c -> c.reservasCount >= 2).count();

        // Distribución de dispositivos
        Map<String, Integer> deviceCount = new HashMap<>();
        for (ClienteDto c : all) {
            if (c.dispositivoPrincipal != null) {
                deviceCount.merge(c.dispositivoPrincipal, 1, Integer::sum);
            }
        }
        s.dispositivos = deviceCount;

        // Promedio reservas por cliente
        s.promedioReservasPorCliente = all.isEmpty() ? 0.0
                : (double) s.totalReservas / all.size();

        return s;
    }

    /**
     * Devuelve el detalle completo de un cliente: sus conversaciones y sus reservas.
     */
    @Transactional(readOnly = true)
    public ClienteDetalleDto detail(String clienteKey) {
        if (clienteKey == null || clienteKey.isBlank()) return null;

        // Si la key es de un cliente loyalty-only (sin reservas), buscar directo
        if (clienteKey.startsWith("loyalty:")) {
            String hash = clienteKey.substring("loyalty:".length());
            return loyaltyRepo.findAll().stream()
                    .filter(lc -> hash.equals(lc.getCustomerHash()))
                    .findFirst()
                    .map(lc -> {
                        ClienteDetalleDto out = new ClienteDetalleDto();
                        ClienteDto dto = new ClienteDto();
                        dto.clienteKey = clienteKey;
                        dto.nombre = lc.getFirstName();
                        dto.apellido = lc.getLastName();
                        dto.email = lc.getEmail();
                        dto.telefono = lc.getPhone();
                        dto.reservasCount = 0;
                        dto.conversacionesCount = 0;
                        dto.firstSeenAt = lc.getEnrolledAt();
                        dto.lastSeenAt = lc.getLastActivityAt() != null
                            ? lc.getLastActivityAt() : lc.getEnrolledAt();
                        dto.loyaltyEnrolled = true;
                        dto.loyaltyCustomerHash = lc.getCustomerHash();
                        dto.source = "MARKETING_ONLY";
                        out.cliente = dto;
                        out.reservas = Collections.emptyList();
                        out.conversaciones = Collections.emptyList();
                        return out;
                    })
                    .orElse(null);
        }

        List<BotTableRecord> reservations = loadAllReservations();
        List<ConversationLog> conversations = convRepo.findAll();

        List<ReservaInfo> reservas = new ArrayList<>();
        for (BotTableRecord rec : reservations) {
            ReservaInfo info = parseReservaRecord(rec);
            if (info != null && clienteKey.equals(info.clienteKey)) {
                reservas.add(info);
            }
        }
        if (reservas.isEmpty()) return null;

        List<ConversationLog> convs = new ArrayList<>();
        for (ConversationLog c : conversations) {
            String key = buildClientKey(
                    c.getClientFirstName(),
                    c.getClientLastName(),
                    extractEmailFromExtra(c.getClientExtraJson()),
                    extractPhoneFromExtra(c.getClientExtraJson())
            );
            if (clienteKey.equals(key)) convs.add(c);
        }

        ClienteDto resumen = buildClienteDto(clienteKey, convs, reservas);
        if (resumen == null) return null;

        ClienteDetalleDto out = new ClienteDetalleDto();
        out.cliente = resumen;
        out.reservas = reservas.stream().map(r -> {
            ReservaSummaryDto rd = new ReservaSummaryDto();
            rd.id = r.recordId;
            rd.tableSlug = r.tableSlug;
            rd.fechaReserva = r.fechaReserva;
            rd.dataJson = r.dataJson;
            rd.createdAt = r.createdAt;
            return rd;
        }).sorted((a, b) -> {
            Instant ai = a.createdAt != null ? a.createdAt : Instant.EPOCH;
            Instant bi = b.createdAt != null ? b.createdAt : Instant.EPOCH;
            return bi.compareTo(ai);
        }).toList();
        out.conversaciones = convs.stream().map(c -> {
            ConvSummaryDto cd = new ConvSummaryDto();
            cd.id = c.getId();
            cd.startedAt = c.getStartedAt();
            cd.endedAt = c.getEndedAt();
            cd.messageCount = c.getMessageCount();
            cd.deviceType = c.getDeviceType();
            cd.deviceOs = c.getDeviceOs();
            cd.closedReason = c.getClosedReason();
            return cd;
        }).sorted((a, b) -> {
            Instant ai = a.startedAt != null ? a.startedAt : Instant.EPOCH;
            Instant bi = b.startedAt != null ? b.startedAt : Instant.EPOCH;
            return bi.compareTo(ai);
        }).toList();
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Carga todos los registros de las tablas que tengan emailColumn configurada
     * (asumimos que esas son las tablas "de cliente" con datos personales).
     */
    private List<BotTableRecord> loadAllReservations() {
        List<BotTable> allTables = tableRepo.findAll();
        List<BotTable> clientTables = allTables.stream()
                .filter(t -> Boolean.TRUE.equals(t.getActive()))
                .filter(t -> t.getEmailColumn() != null && !t.getEmailColumn().isBlank())
                .toList();

        List<BotTableRecord> all = new ArrayList<>();
        for (BotTable t : clientTables) {
            all.addAll(recordRepo.findByTableIdOrderByCreatedAtDesc(t.getId()));
        }
        // También devolvemos qué tabla viene cada record (para mostrar en el detalle)
        // Pero como el record solo tiene tableId, esto se resuelve en parseReservaRecord
        // que mira la tabla por ID.
        return all;
    }

    /**
     * Parsea un BotTableRecord buscando los datos del cliente (nombre, apellido,
     * email, teléfono) según las columnas configuradas en la tabla.
     */
    private ReservaInfo parseReservaRecord(BotTableRecord rec) {
        BotTable table = tableRepo.findById(rec.getTableId()).orElse(null);
        if (table == null) return null;

        JsonNode data;
        try { data = objectMapper.readTree(rec.getDataJson()); }
        catch (Exception e) { return null; }

        String email = readJsonString(data, table.getEmailColumn());

        // Detectar columnas de nombre / apellido / teléfono por convención de nombre
        String firstName = null, lastName = null, phone = null;
        Iterator<String> fieldNames = data.fieldNames();
        while (fieldNames.hasNext()) {
            String fn = fieldNames.next();
            String norm = normalizeColName(fn);
            if (firstName == null && (norm.equals("nombre") || norm.equals("first_name") || norm.equals("firstname"))) {
                firstName = readJsonString(data, fn);
            } else if (lastName == null && (norm.equals("apellido") || norm.equals("last_name") || norm.equals("lastname") || norm.equals("surname"))) {
                lastName = readJsonString(data, fn);
            } else if (firstName == null && lastName == null
                    && (norm.equals("nombre_y_apellido") || norm.equals("nombre y apellido")
                        || norm.equals("nombrecompleto") || norm.equals("nombre_completo")
                        || norm.equals("full_name") || norm.equals("fullname"))) {
                String full = readJsonString(data, fn);
                if (full != null && !full.isBlank()) {
                    String[] parts = full.trim().split("\\s+", 2);
                    firstName = parts[0];
                    lastName  = parts.length > 1 ? parts[1] : null;
                }
            } else if (phone == null && (norm.contains("telefono") || norm.contains("celular")
                    || norm.contains("phone") || norm.contains("mobile") || norm.equals("tel"))) {
                phone = readJsonString(data, fn);
            }
        }

        ReservaInfo info = new ReservaInfo();
        info.recordId = rec.getId();
        info.tableSlug = table.getSlug();
        info.tableName = table.getName();
        info.firstName = firstName;
        info.lastName = lastName;
        info.email = email;
        info.phone = phone;
        info.dataJson = rec.getDataJson();
        info.createdAt = rec.getCreatedAt();
        info.clienteKey = buildClientKey(firstName, lastName, email, phone);

        // Buscar fecha de reserva en alguna columna de tipo datetime/date
        for (Iterator<String> it = data.fieldNames(); it.hasNext(); ) {
            String fn = it.next();
            String norm = normalizeColName(fn);
            if (norm.contains("fecha") || norm.equals("date") || norm.contains("datetime")) {
                String v = readJsonString(data, fn);
                if (v != null && v.matches("^\\d{4}-\\d{2}-\\d{2}.*")) {
                    info.fechaReserva = v;
                    break;
                }
            }
        }
        return info;
    }

    private ClienteDto buildClienteDto(String key, List<ConversationLog> convs, List<ReservaInfo> reservas) {
        ClienteDto dto = new ClienteDto();
        dto.clienteKey = key;
        dto.reservasCount = reservas.size();
        dto.conversacionesCount = convs.size();

        // Datos personales: priorizamos los más recientes (de reservas, fallback a conv)
        ReservaInfo latestRes = reservas.stream()
                .max(Comparator.comparing(r -> r.createdAt != null ? r.createdAt : Instant.EPOCH))
                .orElse(null);

        if (latestRes != null) {
            dto.nombre = latestRes.firstName;
            dto.apellido = latestRes.lastName;
            dto.email = latestRes.email;
            dto.telefono = latestRes.phone;
        }

        // Si la conversación tiene mejores datos, los usamos
        ConversationLog latestConv = convs.stream()
                .max(Comparator.comparing(c -> c.getEndedAt() != null ? c.getEndedAt() : Instant.EPOCH))
                .orElse(null);
        if (latestConv != null) {
            if (dto.nombre == null) dto.nombre = latestConv.getClientFirstName();
            if (dto.apellido == null) dto.apellido = latestConv.getClientLastName();
            if (dto.email == null) dto.email = extractEmailFromExtra(latestConv.getClientExtraJson());
            if (dto.telefono == null) dto.telefono = extractPhoneFromExtra(latestConv.getClientExtraJson());
        }

        // Primer/último contacto: tomamos el min/max entre conversaciones y reservas
        Instant first = null, last = null;
        for (ConversationLog c : convs) {
            if (c.getStartedAt() != null && (first == null || c.getStartedAt().isBefore(first))) first = c.getStartedAt();
            if (c.getEndedAt()   != null && (last  == null || c.getEndedAt().isAfter(last)))    last  = c.getEndedAt();
        }
        for (ReservaInfo r : reservas) {
            if (r.createdAt != null) {
                if (first == null || r.createdAt.isBefore(first)) first = r.createdAt;
                if (last  == null || r.createdAt.isAfter(last))   last  = r.createdAt;
            }
        }
        dto.firstSeenAt = first;
        dto.lastSeenAt = last;

        // Dispositivo más usado en las conversaciones
        Map<String, Integer> deviceFreq = new HashMap<>();
        for (ConversationLog c : convs) {
            if (c.getDeviceType() != null) {
                deviceFreq.merge(c.getDeviceType(), 1, Integer::sum);
            }
        }
        dto.dispositivoPrincipal = deviceFreq.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

        // OS más usado
        Map<String, Integer> osFreq = new HashMap<>();
        for (ConversationLog c : convs) {
            if (c.getDeviceOs() != null) {
                osFreq.merge(c.getDeviceOs(), 1, Integer::sum);
            }
        }
        dto.sistemaOperativo = osFreq.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

        // Navegador y device detail más recientes (de la última conversación)
        ConversationLog mostRecentConv = convs.stream()
                .max(Comparator.comparing(c -> c.getStartedAt() != null ? c.getStartedAt() : Instant.EPOCH))
                .orElse(null);
        if (mostRecentConv != null) {
            // Browser puede venir en deviceBrowser ya parseado por el front,
            // o lo extraemos del userAgent como fallback.
            dto.navegador = mostRecentConv.getDeviceBrowser();
            if (dto.navegador == null || dto.navegador.isBlank()) {
                dto.navegador = parseBrowserFromUA(mostRecentConv.getUserAgent());
            }
            // Device detail: heurística sobre el UA (modelo de mobile o versión OS)
            dto.dispositivoDetalle = parseDeviceDetailFromUA(mostRecentConv.getUserAgent());

            // Geo: del registro más reciente que la tenga (algunas viejas pueden no tener)
            ConversationLog convWithGeo = convs.stream()
                    .filter(c -> c.getGeoCountry() != null || c.getGeoCity() != null)
                    .max(Comparator.comparing(c -> c.getStartedAt() != null ? c.getStartedAt() : Instant.EPOCH))
                    .orElse(mostRecentConv);
            dto.geoPais        = convWithGeo.getGeoCountry();
            dto.geoPaisCodigo  = convWithGeo.getGeoCountryCode();
            dto.geoProvincia   = convWithGeo.getGeoRegion();
            dto.geoCiudad      = convWithGeo.getGeoCity();
        }

        // Mensajes promedio por charla
        if (!convs.isEmpty()) {
            double avg = convs.stream()
                    .filter(c -> c.getMessageCount() != null)
                    .mapToInt(ConversationLog::getMessageCount)
                    .average().orElse(0);
            dto.mensajesPromedio = (int) Math.round(avg);

            // Duración promedio de charla en minutos
            double avgDur = convs.stream()
                    .filter(c -> c.getStartedAt() != null && c.getEndedAt() != null)
                    .mapToLong(c -> Math.max(0, (c.getEndedAt().getEpochSecond() - c.getStartedAt().getEpochSecond())))
                    .average().orElse(0);
            dto.duracionPromedioMin = (int) Math.round(avgDur / 60.0);
        }

        return dto;
    }

    private String readJsonString(JsonNode node, String field) {
        if (node == null || field == null) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        if (v.isTextual()) return v.asText().trim();
        return v.asText().trim();
    }

    private String normalizeColName(String s) {
        if (s == null) return "";
        return s.toLowerCase().trim()
                .replace("á", "a").replace("é", "e").replace("í", "i")
                .replace("ó", "o").replace("ú", "u").replace("ñ", "n");
    }

    /**
     * Construye una clave de identidad del cliente. La clave es estable: si
     * el mismo cliente vuelve a charlar/reservar con los mismos datos, va a
     * generar la misma clave y se cuenta como recurrente.
     *
     * Formato: "<nombre_normalizado>|<apellido_normalizado>|<email_o_tel>"
     * Si no hay nombre+apellido, retornamos null (cliente anónimo, no se cuenta).
     * Si no hay ni email ni teléfono, también retornamos null.
     */
    String buildClientKey(String firstName, String lastName, String email, String phone) {
        String fn = norm(firstName);
        String ln = norm(lastName);
        if (fn.isEmpty() || ln.isEmpty()) return null;

        String contact;
        String em = norm(email);
        String ph = normPhone(phone);
        if (!em.isEmpty()) contact = "e:" + em;
        else if (!ph.isEmpty()) contact = "p:" + ph;
        else return null;

        return fn + "|" + ln + "|" + contact;
    }

    private String norm(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase()
                .replace("á", "a").replace("é", "e").replace("í", "i")
                .replace("ó", "o").replace("ú", "u").replace("ñ", "n")
                .replaceAll("\\s+", " ");
    }

    /** Normaliza teléfono: solo dígitos, ignorando + y prefijos comunes. */
    private String normPhone(String s) {
        if (s == null) return "";
        String digits = s.replaceAll("[^0-9]", "");
        // Sacar prefijo de país argentino si está
        if (digits.startsWith("549")) digits = digits.substring(3);
        else if (digits.startsWith("54")) digits = digits.substring(2);
        if (digits.startsWith("0")) digits = digits.substring(1);
        if (digits.startsWith("15")) digits = digits.substring(2);
        return digits;
    }

    private String extractEmailFromExtra(String extraJson) {
        if (extraJson == null || extraJson.isBlank()) return null;
        try {
            JsonNode n = objectMapper.readTree(extraJson);
            JsonNode email = n.get("email");
            if (email == null) email = n.get("mail");
            return email != null && !email.isNull() ? email.asText() : null;
        } catch (Exception e) { return null; }
    }

    private String extractPhoneFromExtra(String extraJson) {
        if (extraJson == null || extraJson.isBlank()) return null;
        try {
            JsonNode n = objectMapper.readTree(extraJson);
            JsonNode v = n.get("telefono");
            if (v == null) v = n.get("phone");
            if (v == null) v = n.get("tel");
            return v != null && !v.isNull() ? v.asText() : null;
        } catch (Exception e) { return null; }
    }

    /**
     * Parsea un User-Agent string para extraer el navegador con versión.
     * Heurística simple — para algo más serio usar yauaa o ua-parser, pero
     * para el módulo Clientes alcanza con esto.
     *
     * Ejemplos:
     *   "Mozilla/5.0 ... Chrome/120.0.0.0 Safari/537.36" → "Chrome 120"
     *   "Mozilla/5.0 ... Firefox/121.0"                  → "Firefox 121"
     *   "Mozilla/5.0 ... Version/17.2 Safari/605.1.15"   → "Safari 17"
     */
    private String parseBrowserFromUA(String ua) {
        if (ua == null || ua.isBlank()) return null;

        // Edge antes que Chrome (Edge incluye "Chrome" en su UA)
        java.util.regex.Matcher m;
        m = java.util.regex.Pattern.compile("Edg/(\\d+)").matcher(ua);
        if (m.find()) return "Edge " + m.group(1);

        m = java.util.regex.Pattern.compile("OPR/(\\d+)|Opera/(\\d+)").matcher(ua);
        if (m.find()) return "Opera " + (m.group(1) != null ? m.group(1) : m.group(2));

        m = java.util.regex.Pattern.compile("Firefox/(\\d+)").matcher(ua);
        if (m.find()) return "Firefox " + m.group(1);

        m = java.util.regex.Pattern.compile("Chrome/(\\d+)").matcher(ua);
        if (m.find()) return "Chrome " + m.group(1);

        // Safari (no Chrome, no Edge): mirar Version/X
        if (ua.contains("Safari/") && !ua.contains("Chrome/")) {
            m = java.util.regex.Pattern.compile("Version/(\\d+)").matcher(ua);
            if (m.find()) return "Safari " + m.group(1);
            return "Safari";
        }
        return null;
    }

    /**
     * Heurística para extraer un detalle más rico del dispositivo desde el UA.
     * Devuelve cosas como "iPhone 15 Pro", "Galaxy S24", "Windows 11", "macOS 14".
     * Si no puede determinar, devuelve null.
     */
    private String parseDeviceDetailFromUA(String ua) {
        if (ua == null || ua.isBlank()) return null;

        java.util.regex.Matcher m;

        // iPhone con modelo (raramente expone el modelo exacto, suele decir solo "iPhone")
        if (ua.contains("iPhone")) {
            m = java.util.regex.Pattern.compile("CPU iPhone OS (\\d+)_(\\d+)").matcher(ua);
            if (m.find()) return "iPhone (iOS " + m.group(1) + "." + m.group(2) + ")";
            return "iPhone";
        }
        if (ua.contains("iPad")) {
            m = java.util.regex.Pattern.compile("CPU OS (\\d+)_(\\d+)").matcher(ua);
            if (m.find()) return "iPad (iOS " + m.group(1) + "." + m.group(2) + ")";
            return "iPad";
        }

        // Android: el modelo suele aparecer entre paréntesis después de "Linux; Android X;"
        m = java.util.regex.Pattern.compile("Android (\\d+)[^;]*; ([^)]+?)(?: Build|\\))").matcher(ua);
        if (m.find()) {
            String version = m.group(1);
            String model = m.group(2).trim();
            // Limpieza: el modelo a veces tiene "wv" (webview) o cosas raras
            model = model.replaceAll("\\bwv\\b", "").replaceAll("\\s+", " ").trim();
            if (model.isEmpty() || model.equalsIgnoreCase("K") || model.length() < 2) {
                return "Android " + version;
            }
            return model + " (Android " + version + ")";
        }
        if (ua.contains("Android")) {
            m = java.util.regex.Pattern.compile("Android (\\d+)").matcher(ua);
            if (m.find()) return "Android " + m.group(1);
            return "Android";
        }

        // Windows: "Windows NT 10.0" → Windows 10/11 (no se puede distinguir desde UA)
        if (ua.contains("Windows NT 10.0")) return "Windows 10/11";
        if (ua.contains("Windows NT 6.3")) return "Windows 8.1";
        if (ua.contains("Windows NT 6.2")) return "Windows 8";
        if (ua.contains("Windows NT 6.1")) return "Windows 7";

        // macOS: "Mac OS X 14_2_1" → macOS 14
        m = java.util.regex.Pattern.compile("Mac OS X (\\d+)[._](\\d+)").matcher(ua);
        if (m.find()) {
            int major = Integer.parseInt(m.group(1));
            // macOS pasó de 10.x a 11+ (Big Sur)
            if (major >= 11) return "macOS " + major;
            return "macOS 10." + m.group(2);
        }

        if (ua.contains("Linux")) return "Linux";
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────
    // DTOs (públicos para el controller)
    // ─────────────────────────────────────────────────────────────────────

    public static class ClienteDto {
        public String clienteKey;
        public String nombre;
        public String apellido;
        public String email;
        public String telefono;
        public int reservasCount;
        public int conversacionesCount;
        public Instant firstSeenAt;
        public Instant lastSeenAt;
        public String dispositivoPrincipal;   // mobile / desktop / tablet
        public String sistemaOperativo;       // Windows / Android / iOS / etc.
        public String navegador;              // Chrome / Safari / Firefox / Edge — extraído del UA
        public String dispositivoDetalle;     // "Windows 11", "iPhone 15", "Galaxy S24" — heurístico
        public String geoPais;                // "Argentina"
        public String geoPaisCodigo;          // "AR" — para mostrar bandera
        public String geoProvincia;           // "Buenos Aires"
        public String geoCiudad;              // "Pilar"
        public Integer mensajesPromedio;      // por conversación
        public Integer duracionPromedioMin;   // por conversación

        /** Origen del cliente: "BOT" (solo bot), "MARKETING_ONLY" (solo módulo
         *  Marketing, todavía no reservó), "BOT_AND_MARKETING" (las dos cosas).
         *  El frontend muestra un badge según este valor. */
        public String source;

        /** True si el cliente está enrolado en el programa de fidelización. */
        public boolean loyaltyEnrolled;

        /** Hash del loyalty_customer (para linkear desde /admin al módulo
         *  Marketing). Null si loyaltyEnrolled=false. */
        public String loyaltyCustomerHash;
    }

    public static class StatsDto {
        public int totalClientes;
        public int totalReservas;
        public int totalConversaciones;
        public int clientesRecurrentes;       // reservasCount >= 2
        public double promedioReservasPorCliente;
        public Map<String, Integer> dispositivos;
    }

    public static class ClienteDetalleDto {
        public ClienteDto cliente;
        public List<ReservaSummaryDto> reservas;
        public List<ConvSummaryDto> conversaciones;
    }

    public static class ReservaSummaryDto {
        public Long id;
        public String tableSlug;
        public String fechaReserva;
        public String dataJson;
        public Instant createdAt;
    }

    public static class ConvSummaryDto {
        public Long id;
        public Instant startedAt;
        public Instant endedAt;
        public Integer messageCount;
        public String deviceType;
        public String deviceOs;
        public String closedReason;
    }

    /** Wrapper interno para acumular datos de una reserva ya parseados. */
    private static class ReservaInfo {
        Long recordId;
        String tableSlug;
        String tableName;
        String firstName, lastName, email, phone;
        String fechaReserva;
        String dataJson;
        Instant createdAt;
        String clienteKey;
    }
}
