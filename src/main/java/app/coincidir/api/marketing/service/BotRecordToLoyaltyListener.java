package app.coincidir.api.marketing.service;

import app.coincidir.api.botplatform.domain.BotTable;
import app.coincidir.api.botplatform.domain.BotTableRecord;
import app.coincidir.api.botplatform.service.BotTableChangeEvent;
import app.coincidir.api.marketing.util.PhoneNormalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Iterator;

/**
 * BotRecordToLoyaltyListener — Sincronización push: bot → Marketing.
 *
 * Cuando el bot inserta un nuevo BotTableRecord (típicamente una reserva),
 * este listener crea/reactiva automáticamente un loyalty_customer en el
 * módulo Marketing usando los datos del cliente (nombre, email, teléfono).
 *
 * Reglas:
 *   - SOLO se ejecuta si la tabla tiene phoneColumn configurada. Sin
 *     teléfono no podemos enrolar (es la clave de dedup en loyalty_customer).
 *   - SOLO escucha eventos "created" (no "updated", para no spamear).
 *   - Es resiliente: si falla por cualquier motivo (teléfono inválido,
 *     nombre faltante, etc.) logueamos warning y seguimos. NUNCA tiramos
 *     excepción que rompa la transacción del bot.
 *   - Usa source="bot-reservation" para distinguir de "self-enroll"
 *     (que dispara welcome bonus). El welcome bonus se dispara también
 *     desde acá vía CustomerEnrolledEvent — comportamiento intencional:
 *     queremos premiar a quien reserva por primera vez.
 *   - @TransactionalEventListener(AFTER_COMMIT): nos aseguramos que el
 *     INSERT del BotTableRecord se haya confirmado antes de enrolar.
 *     Si la transacción del bot revierte, no enrolamos al cliente.
 *   - @Async: el enrolamiento corre en otro thread para no bloquear la
 *     respuesta del bot al cliente.
 *
 * Lookup de columnas: se usa una combinación de:
 *   1. phoneColumn declarado en la BotTable (preferido, explícito)
 *   2. emailColumn declarado en la BotTable
 *   3. Detección por convención de nombre para firstName/lastName
 *      (mismo algoritmo que ClientesService)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BotRecordToLoyaltyListener {

    private final LoyaltyCustomerService loyaltyCustomerService;
    private final ObjectMapper objectMapper;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBotRecordCreated(BotTableChangeEvent event) {
        if (!"created".equals(event.event)) return;

        BotTable table = event.table;
        BotTableRecord record = event.record;

        // Sin phoneColumn no podemos enrolar — la tabla no participa
        // de la sincronización con Marketing.
        if (table.getPhoneColumn() == null || table.getPhoneColumn().isBlank()) {
            return;
        }

        try {
            JsonNode data = objectMapper.readTree(record.getDataJson());
            String rawPhone = readJsonString(data, table.getPhoneColumn());
            if (rawPhone == null || rawPhone.isBlank()) {
                log.debug("[Marketing sync] record {} sin teléfono en columna '{}', skip",
                    record.getId(), table.getPhoneColumn());
                return;
            }

            String normalizedPhone = PhoneNormalizer.normalize(rawPhone);
            if (normalizedPhone == null) {
                log.warn("[Marketing sync] teléfono inválido en record {}: '{}'",
                    record.getId(), rawPhone);
                return;
            }

            String email = table.getEmailColumn() != null
                ? readJsonString(data, table.getEmailColumn())
                : null;

            // Extraer nombre por convención (mismo enfoque que ClientesService)
            String[] nameParts = extractName(data);
            String firstName = nameParts[0];
            String lastName = nameParts[1];

            if (firstName == null || firstName.isBlank()) {
                log.debug("[Marketing sync] record {} sin nombre detectable, skip",
                    record.getId());
                return;
            }

            LoyaltyCustomerService.EnrollInput input = new LoyaltyCustomerService.EnrollInput(
                normalizedPhone,
                firstName,
                lastName,
                email,
                null,                        // birthDate: no la pide el bot por defecto
                null,                        // branchId
                "bot-reservation",           // source — dispara welcome bonus
                table.getSlug(),             // reservationTableSlug (ej: "reservas")
                record.getId()               // reservationRecordId
            );

            LoyaltyCustomerService.EnrollResult result =
                loyaltyCustomerService.enrollOrReactivate(input);

            if (result.alreadyExisted()) {
                log.info("[Marketing sync] cliente existente reactivado/actualizado: phone={} record={}",
                    normalizedPhone, record.getId());
            } else {
                log.info("[Marketing sync] cliente nuevo enrolado: phone={} record={}",
                    normalizedPhone, record.getId());
            }
        } catch (Exception e) {
            // NUNCA propagamos: una falla en Marketing no debe romper el bot.
            log.warn("[Marketing sync] error procesando record {}: {}",
                record.getId(), e.getMessage(), e);
        }
    }

    /**
     * Extrae [firstName, lastName] del data_json buscando columnas por
     * convención de nombre. Soporta "nombre y apellido" como un único campo
     * (lo splittea por el primer espacio).
     */
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
