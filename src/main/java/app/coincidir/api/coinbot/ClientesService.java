package app.coincidir.api.coinbot;

import app.coincidir.api.botplatform.domain.BotTable;
import app.coincidir.api.botplatform.domain.BotTableRecord;
import app.coincidir.api.botplatform.repository.BotTableRecordRepository;
import app.coincidir.api.botplatform.repository.BotTableRepository;
import app.coincidir.api.domain.ConversationLog;
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
 * ClientesService — agrupa ConversationLog + BotTableRecord (reservas) por cliente
 * y calcula las métricas para el módulo /admin > Clientes.
 *
 * Identidad de cliente: combinación de nombre+apellido+(email O teléfono).
 * Solo se devuelven clientes que tengan al menos 1 RESERVA REAL (un BotTableRecord
 * en alguna tabla con emailColumn configurada que matchee con sus datos).
 *
 * El matching es case-insensitive, normalizado (sin tildes, sin espacios extra).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClientesService {

    private final ConversationLogRepository convRepo;
    private final BotTableRepository tableRepo;
    private final BotTableRecordRepository recordRepo;
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

        // Construir DTO por cliente — SOLO si tiene al menos 1 reserva real
        List<ClienteDto> out = new ArrayList<>();
        Set<String> processedKeys = new HashSet<>();

        // Primero: clientes que aparecen en reservas (haya o no conversación)
        for (Map.Entry<String, List<ReservaInfo>> e : reservasByKey.entrySet()) {
            String key = e.getKey();
            if (processedKeys.contains(key)) continue;
            processedKeys.add(key);

            List<ReservaInfo> reservas = e.getValue();
            List<ConversationLog> convs = convsByKey.getOrDefault(key, Collections.emptyList());
            ClienteDto dto = buildClienteDto(key, convs, reservas);
            if (dto != null) out.add(dto);
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
        public Integer mensajesPromedio;      // por conversación
        public Integer duracionPromedioMin;   // por conversación
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
