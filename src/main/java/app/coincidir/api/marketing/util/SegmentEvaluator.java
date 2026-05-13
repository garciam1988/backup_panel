package app.coincidir.api.marketing.util;

import app.coincidir.api.marketing.domain.LoyaltyCard;
import app.coincidir.api.marketing.domain.LoyaltyCustomer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/**
 * SegmentEvaluator — Convierte un criteria_json en un predicado evaluable
 * cliente-por-cliente.
 *
 * Por qué no traducir a JPQL: porque varios fields son derivados
 * (last_visit_days_ago, birthday_in_days) y mezclar datos de customer + card.
 * Hacerlo en código es mucho más simple y suficiente para volúmenes esperados
 * (decenas de miles de clientes por tenant).
 *
 * Formato del criteria_json:
 *   {
 *     "match": "all" | "any",
 *     "filters": [
 *       { "field": "<fieldName>", "op": "<op>", "value": <any> },
 *       ...
 *     ]
 *   }
 *
 * Operadores: eq, neq, gt, gte, lt, lte, in, nin, contains.
 *
 * Campos soportados:
 *   - Direct customer:  first_name, last_name, email, phone, birth_date,
 *                       enrolled_branch, enrolled_source, total_visits,
 *                       accepts_whatsapp, accepts_email, accepts_push, active
 *   - Direct card:      current_stamps, current_points, cashback_balance,
 *                       lifetime_stamps, lifetime_points, lifetime_cashback,
 *                       tier_code
 *   - Derived:          last_visit_days_ago, birthday_in_days,
 *                       days_since_enrollment
 *
 * Si un field no existe o el value es del tipo incorrecto, el filtro falla
 * silenciosamente (devuelve false). Esto evita explotar la evaluación entera
 * por un filtro malformado.
 */
public class SegmentEvaluator {

    private final ObjectMapper objectMapper;

    public SegmentEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public SegmentEvaluator() {
        this(new ObjectMapper());
    }

    /**
     * Evalúa si un cliente matchea el criteria_json. card puede ser null si el
     * cliente no tiene tarjeta todavía (en ese caso los filtros sobre fields
     * de card devuelven false).
     */
    public boolean matches(String criteriaJson, LoyaltyCustomer customer, LoyaltyCard card) {
        if (criteriaJson == null || criteriaJson.isBlank()) return true;
        try {
            JsonNode root = objectMapper.readTree(criteriaJson);
            String match = root.path("match").asText("all").toLowerCase();
            JsonNode filters = root.path("filters");
            if (!filters.isArray() || filters.size() == 0) return true;

            boolean isAny = match.equals("any");
            boolean accumulator = !isAny;

            Iterator<JsonNode> it = filters.elements();
            while (it.hasNext()) {
                JsonNode f = it.next();
                boolean ok = evalFilter(f, customer, card);
                if (isAny) {
                    if (ok) return true;
                } else {
                    if (!ok) return false;
                }
            }
            return accumulator;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean evalFilter(JsonNode f, LoyaltyCustomer c, LoyaltyCard card) {
        String field = f.path("field").asText("");
        String op = f.path("op").asText("eq").toLowerCase();
        JsonNode value = f.path("value");

        Object actual = readField(field, c, card);
        return compare(actual, op, value);
    }

    private Object readField(String field, LoyaltyCustomer c, LoyaltyCard card) {
        if (c == null) return null;
        return switch (field) {
            // Customer direct
            case "first_name"          -> c.getFirstName();
            case "last_name"           -> c.getLastName();
            case "email"               -> c.getEmail();
            case "phone"               -> c.getPhone();
            case "birth_date"          -> c.getBirthDate() == null ? null : c.getBirthDate().toString();
            case "enrolled_branch"     -> c.getEnrolledBranch();
            case "enrolled_source"     -> c.getEnrolledSource();
            case "total_visits"        -> c.getTotalVisits();
            case "accepts_whatsapp"    -> c.getAcceptsWhatsapp();
            case "accepts_email"       -> c.getAcceptsEmail();
            case "accepts_push"        -> c.getAcceptsPush();
            case "active"              -> c.getActive();
            // Card direct
            case "current_stamps"      -> card == null ? null : card.getCurrentStamps();
            case "current_points"      -> card == null ? null : card.getCurrentPoints();
            case "cashback_balance"    -> card == null ? null : card.getCashbackBalance();
            case "lifetime_stamps"     -> card == null ? null : card.getLifetimeStamps();
            case "lifetime_points"     -> card == null ? null : card.getLifetimePoints();
            case "lifetime_cashback"   -> card == null ? null : card.getLifetimeCashback();
            case "tier_code"           -> card == null ? null : card.getTierCode();
            // Derived
            case "last_visit_days_ago" -> daysAgo(c.getLastActivityAt());
            case "days_since_enrollment" -> daysAgo(c.getEnrolledAt());
            case "birthday_in_days"    -> birthdayInDays(c.getBirthDate());
            default                    -> null;
        };
    }

    private Long daysAgo(Instant t) {
        if (t == null) return null;
        return ChronoUnit.DAYS.between(t, Instant.now());
    }

    private Long birthdayInDays(LocalDate birth) {
        if (birth == null) return null;
        LocalDate today = LocalDate.now();
        MonthDay md = MonthDay.from(birth);
        LocalDate next = md.atYear(today.getYear());
        if (next.isBefore(today)) next = md.atYear(today.getYear() + 1);
        return ChronoUnit.DAYS.between(today, next);
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    private boolean compare(Object actual, String op, JsonNode value) {
        switch (op) {
            case "eq":  return equalsLoose(actual, value);
            case "neq": return !equalsLoose(actual, value);
            case "gt":  return compareNumeric(actual, value) > 0;
            case "gte": return compareNumeric(actual, value) >= 0;
            case "lt":  return compareNumeric(actual, value) < 0;
            case "lte": return compareNumeric(actual, value) <= 0;
            case "in":  return inArray(actual, value);
            case "nin": return !inArray(actual, value);
            case "contains":
                if (actual == null || !value.isTextual()) return false;
                return actual.toString().toLowerCase().contains(value.asText().toLowerCase());
            default: return false;
        }
    }

    private boolean equalsLoose(Object actual, JsonNode value) {
        if (actual == null) return value == null || value.isNull();
        if (value.isBoolean()) return Boolean.valueOf(actual.toString()).equals(value.asBoolean());
        if (value.isNumber() && actual instanceof Number n) {
            return Double.compare(n.doubleValue(), value.asDouble()) == 0;
        }
        return Objects.equals(actual.toString(), value.asText());
    }

    private int compareNumeric(Object actual, JsonNode value) {
        if (actual == null || !value.isNumber()) return Integer.MIN_VALUE;
        double a, b;
        try {
            a = (actual instanceof Number n) ? n.doubleValue() : Double.parseDouble(actual.toString());
            b = value.asDouble();
        } catch (Exception e) { return Integer.MIN_VALUE; }
        return Double.compare(a, b);
    }

    private boolean inArray(Object actual, JsonNode value) {
        if (actual == null || !value.isArray()) return false;
        String aStr = actual.toString();
        for (JsonNode n : value) {
            if (n.asText().equals(aStr)) return true;
        }
        return false;
    }
}
