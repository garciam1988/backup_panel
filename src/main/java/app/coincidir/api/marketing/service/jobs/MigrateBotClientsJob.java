package app.coincidir.api.marketing.service.jobs;

import app.coincidir.api.botplatform.domain.BotTable;
import app.coincidir.api.botplatform.domain.BotTableRecord;
import app.coincidir.api.botplatform.repository.BotTableRecordRepository;
import app.coincidir.api.botplatform.repository.BotTableRepository;
import app.coincidir.api.marketing.service.LoyaltyCustomerService;
import app.coincidir.api.marketing.util.PhoneNormalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MigrateBotClientsJob — Migración inicial one-shot de clientes históricos
 * del bot al módulo Marketing.
 *
 * Recorre todos los BotTableRecord de las tablas que tienen phoneColumn
 * configurado y crea/reactiva un loyalty_customer por cada uno. Es idempotente
 * (puede correrse múltiples veces sin duplicar clientes), pero típicamente se
 * ejecuta UNA vez después de habilitar el módulo Marketing en un cliente que
 * ya tenía datos en el bot.
 *
 * Diferencias con BotRecordToLoyaltyListener (que sincroniza online):
 *   - Se ejecuta manualmente (no por evento)
 *   - Usa source="migration" en vez de "bot-reservation" → NO dispara
 *     CustomerEnrolledEvent → NO genera welcome bonus para los históricos.
 *     Esto evita que un cliente que reservó hace 1 año reciba mensaje
 *     "¡Bienvenido!" al activarse Marketing hoy.
 *   - Devuelve un resumen detallado para mostrar al admin (total, enrolados,
 *     skipped, errores) — útil para auditar la migración.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MigrateBotClientsJob {

    private final BotTableRepository tableRepo;
    private final BotTableRecordRepository recordRepo;
    private final LoyaltyCustomerService loyaltyCustomerService;
    private final ObjectMapper objectMapper;

    /**
     * NO usamos @Transactional aquí: queremos que cada enrolamiento sea
     * su propia transacción (la del enrollOrReactivate). Si un record falla,
     * solo se revierte ese, no toda la migración.
     */
    public Map<String, Object> run() {
        Instant start = Instant.now();
        int total = 0, enrolled = 0, reactivated = 0, skipped = 0, errors = 0;
        Map<String, Integer> byTable = new LinkedHashMap<>();

        // Solo tablas con phoneColumn configurado (las que participan de la sync)
        List<BotTable> tables = tableRepo.findAll().stream()
                .filter(t -> Boolean.TRUE.equals(t.getActive()))
                .filter(t -> t.getPhoneColumn() != null && !t.getPhoneColumn().isBlank())
                .toList();

        log.info("[MigrateBotClients] iniciando migración: {} tabla(s) elegibles", tables.size());

        for (BotTable table : tables) {
            int tableEnrolled = 0;
            List<BotTableRecord> records = recordRepo.findByTableIdOrderByCreatedAtDesc(table.getId());

            for (BotTableRecord rec : records) {
                total++;
                try {
                    JsonNode data = objectMapper.readTree(rec.getDataJson());
                    String rawPhone = readJsonString(data, table.getPhoneColumn());
                    if (rawPhone == null || rawPhone.isBlank()) {
                        skipped++;
                        continue;
                    }

                    String normalizedPhone = PhoneNormalizer.normalize(rawPhone);
                    if (normalizedPhone == null) {
                        skipped++;
                        continue;
                    }

                    String email = table.getEmailColumn() != null
                        ? readJsonString(data, table.getEmailColumn())
                        : null;

                    String[] nameParts = extractName(data);
                    String firstName = nameParts[0];
                    String lastName = nameParts[1];

                    if (firstName == null || firstName.isBlank()) {
                        // No podemos crear sin firstName (es required)
                        skipped++;
                        continue;
                    }

                    LoyaltyCustomerService.EnrollInput input = new LoyaltyCustomerService.EnrollInput(
                        normalizedPhone,
                        firstName,
                        lastName,
                        email,
                        null,                        // birthDate
                        null,                        // branchId
                        "migration",                 // source — NO dispara welcome bonus
                        table.getSlug(),
                        rec.getId()
                    );

                    LoyaltyCustomerService.EnrollResult result =
                        loyaltyCustomerService.enrollOrReactivate(input);

                    if (result.alreadyExisted()) {
                        reactivated++;
                    } else {
                        enrolled++;
                    }
                    tableEnrolled++;

                } catch (Exception e) {
                    errors++;
                    log.warn("[MigrateBotClients] error procesando record {}: {}",
                        rec.getId(), e.getMessage());
                }
            }

            byTable.put(table.getSlug(), tableEnrolled);
            log.info("[MigrateBotClients] tabla '{}': {} clientes procesados",
                table.getSlug(), tableEnrolled);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalRecords", total);
        result.put("enrolledNew", enrolled);
        result.put("reactivatedOrUpdated", reactivated);
        result.put("skipped", skipped);
        result.put("errors", errors);
        result.put("byTable", byTable);
        result.put("durationMs", java.time.Duration.between(start, Instant.now()).toMillis());

        log.info("[MigrateBotClients] terminado: total={}, new={}, reactivated={}, skipped={}, errors={}",
            total, enrolled, reactivated, skipped, errors);

        return result;
    }

    private String[] extractName(JsonNode data) {
        String firstName = null, lastName = null;

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
            }
        }

        return new String[] { firstName, lastName };
    }

    private String readJsonString(JsonNode node, String field) {
        if (node == null || field == null) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }

    private String normalizeColName(String s) {
        if (s == null) return "";
        return s.toLowerCase().trim()
                .replace("á", "a").replace("é", "e").replace("í", "i")
                .replace("ó", "o").replace("ú", "u").replace("ñ", "n");
    }
}
