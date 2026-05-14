package app.coincidir.api.marketing.service;

import app.coincidir.api.marketing.util.PhoneNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * TwilioWhatsAppService — envía mensajes WhatsApp vía la REST API de Twilio.
 *
 * Twilio expone un endpoint REST simple para mandar mensajes:
 *   POST https://api.twilio.com/2010-04-01/Accounts/{SID}/Messages.json
 *   Auth: Basic {base64(SID:TOKEN)}
 *   Body (form): From, To, Body
 *
 * No usamos la lib oficial de Twilio (twilio-java) para evitar una
 * dependencia más en el pom — su API REST es trivial y RestTemplate
 * ya viene con Spring. Si en el futuro necesitamos features avanzadas
 * (Media URLs, templates HSM, status callbacks, etc), evaluamos sumar
 * la lib oficial.
 *
 * Configuración: tres env vars en application.yml
 *   coincidir.twilio.account-sid
 *   coincidir.twilio.auth-token
 *   coincidir.twilio.whatsapp-from  (formato: "whatsapp:+541112345678")
 *
 * Si las 3 no están presentes, el servicio se considera "no configurado"
 * y los llamados retornan un Result con accepted=false y la razón.
 * Esto permite que el módulo Marketing siga funcionando en entornos sin
 * Twilio (los mensajes quedan en QUEUED y se loguea el motivo).
 *
 * Sobre la ventana de 24h de WhatsApp Business:
 *   Meta exige que los mensajes outbound fuera de la ventana de 24h
 *   desde la última interacción del usuario usen "Message Templates"
 *   (HSM) pre-aprobados. Para el MVP del módulo Marketing, mandamos
 *   mensajes simples y dejamos que Twilio responda con error si está
 *   fuera de ventana. El error queda en error_message del NotificationLog
 *   y el operador puede investigar caso por caso. Implementar templates
 *   queda para una iteración futura cuando el cliente del sushi tenga
 *   templates aprobados con Meta.
 */
@Service
@Slf4j
public class TwilioWhatsAppService {

    private final String accountSid;
    private final String authToken;
    private final String whatsappFrom;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public TwilioWhatsAppService(
        @Value("${coincidir.twilio.account-sid:}") String accountSid,
        @Value("${coincidir.twilio.auth-token:}") String authToken,
        @Value("${coincidir.twilio.whatsapp-from:}") String whatsappFrom
    ) {
        this.accountSid = accountSid;
        this.authToken = authToken;
        this.whatsappFrom = whatsappFrom;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();

        if (isConfigured()) {
            log.info("TwilioWhatsAppService configurado. From={}", whatsappFrom);
        } else {
            log.warn("TwilioWhatsAppService NO configurado (faltan env vars TWILIO_*). " +
                "Los mensajes WhatsApp quedarán en QUEUED.");
        }
    }

    /** Indica si las 3 credenciales necesarias están presentes. */
    public boolean isConfigured() {
        return accountSid != null && !accountSid.isBlank()
            && authToken != null && !authToken.isBlank()
            && whatsappFrom != null && !whatsappFrom.isBlank();
    }

    /**
     * Envía un mensaje WhatsApp.
     *
     * @param toPhoneE164  número del destinatario en formato E.164 (ej "+541112345678")
     * @param body         texto del mensaje (sin formato — Twilio lo envía como texto plano)
     * @return resultado con messageSid si fue OK, o errorReason si falló
     */
    public Result send(String toPhoneE164, String body) {
        if (!isConfigured()) {
            return Result.failure("Twilio no configurado (faltan TWILIO_ACCOUNT_SID / TWILIO_AUTH_TOKEN / TWILIO_WHATSAPP_FROM)");
        }
        if (toPhoneE164 == null || toPhoneE164.isBlank()) {
            return Result.failure("Teléfono destino vacío");
        }
        if (body == null || body.isBlank()) {
            return Result.failure("Body vacío");
        }

        // Normalizar a formato E.164 (ej. "11 5555-5555" → "+541155555555").
        // Twilio rechaza con error críptico si el formato es inválido, así
        // que mejor catchearlo acá con un mensaje claro.
        String normalized = PhoneNormalizer.normalize(toPhoneE164);
        if (normalized == null || !normalized.matches("^\\+[1-9]\\d{6,14}$")) {
            return Result.failure("Número de teléfono inválido (formato E.164): " + toPhoneE164);
        }

        // Normalizamos el número a formato "whatsapp:+541112345678"
        String to = "whatsapp:" + normalized;

        String url = String.format("https://api.twilio.com/2010-04-01/Accounts/%s/Messages.json", accountSid);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set(HttpHeaders.AUTHORIZATION, basicAuthHeader(accountSid, authToken));

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("From", whatsappFrom);
        form.add("To", to);
        form.add("Body", body);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(form, headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
            String responseBody = response.getBody();
            JsonNode json = objectMapper.readTree(responseBody == null ? "{}" : responseBody);
            String sid = json.path("sid").asText(null);
            String status = json.path("status").asText("unknown");
            log.info("WhatsApp enviado: to={}, sid={}, status={}", to, sid, status);
            return Result.success(sid, status);
        } catch (HttpClientErrorException e) {
            // Twilio devuelve 4xx con un JSON {code, message, more_info, status}
            String errorBody = e.getResponseBodyAsString();
            String message;
            try {
                JsonNode err = objectMapper.readTree(errorBody);
                message = err.path("message").asText(errorBody);
            } catch (Exception parseErr) {
                message = errorBody;
            }
            log.warn("Error de Twilio enviando WhatsApp a {}: {}", to, message);
            return Result.failure("Twilio: " + message);
        } catch (RestClientException e) {
            log.error("Error de red enviando WhatsApp a {}: {}", to, e.getMessage());
            return Result.failure("Red: " + e.getMessage());
        } catch (Exception e) {
            log.error("Error inesperado enviando WhatsApp a {}", to, e);
            return Result.failure("Inesperado: " + e.getMessage());
        }
    }

    private String basicAuthHeader(String user, String pass) {
        String raw = user + ":" + pass;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    // ── Result type ──────────────────────────────────────────────────────

    public record Result(boolean accepted, String messageSid, String status, String errorReason) {
        public static Result success(String sid, String status) {
            return new Result(true, sid, status, null);
        }
        public static Result failure(String reason) {
            return new Result(false, null, null, reason);
        }
    }
}
