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
 * Formato del criteria_json (canónico, el que produce el frontend actual):
 *   {
 *     "operator": "AND" | "OR",
 *     "conditions": [
 *       { "field": "<fieldName>", "operator": "<op>", "value": <any> },
 *       ...
 *     ]
 *   }
 *
 * También se acepta el formato legacy / alternativo:
 *   {
 *     "match": "all" | "any",
 *     "filters": [
 *       { "field": "<fieldName>", "op": "<op>", "value": <any> },
 *       ...
 *     ]
 *   }
 *
 * Ambos son equivalentes. La tolerancia permite no romper segmentos viejos
 * en DB ni forzar migraciones al cambiar nombres de propiedades.
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
 * Reglas de matching:
 *   - Si un field no existe (campo desconocido), el filtro devuelve false.
 *   - Si actual==null (cliente sin tarjeta, campo derivado sin dato), TODOS
 *     los comparadores numéricos (gt/gte/lt/lte) devuelven false. Antes había
 *     un bug donde lt/lte daban true por arrastrar Integer.MIN_VALUE como
 *     resultado de compareNumeric — ahora se manejan los nulls explícitamente.
 *   - Si value en el JSON viene como string ("1" en lugar de 1), igual se
 *     intenta parsear a número en comparadores numéricos. Esto tolera frontends
 *     que no hagan coerción explícita.
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

            // Tolerar ambos formatos: "match" (legacy) u "operator" (canónico actual).
            // Valores legacy: "all"/"any". Valores canónicos: "AND"/"OR".
            String combiner = root.hasNonNull("operator")
                    ? root.path("operator").asText("AND")
                    : root.path("match").asText("all");
            boolean isAny = combiner.equalsIgnoreCase("any")
                         || combiner.equalsIgnoreCase("or");

            // Tolerar ambos nombres de array: "conditions" (canónico) o "filters" (legacy).
            JsonNode conditions = root.path("conditions");
            if (!conditions.isArray()) conditions = root.path("filters");
            // Sin condiciones reales: matchea todo (es lo que esperan los callers
            // cuando no hay segmento configurado).
            if (!conditions.isArray() || conditions.size() == 0) return true;

            boolean accumulator = !isAny;

            Iterator<JsonNode> it = conditions.elements();
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

        // Tolerar ambos nombres: "operator" (canónico) u "op" (legacy).
        // OJO: el default "eq" silencioso del código viejo era un bug — si el
        // JSON estaba mal y venía sin op, todo se evaluaba como "eq" y devolvía
        // falsos. Ahora si no hay operador definido, el filtro falla (false).
        String op;
        if (f.hasNonNull("operator")) op = f.path("operator").asText("").toLowerCase();
        else if (f.hasNonNull("op"))  op = f.path("op").asText("").toLowerCase();
        else return false;
        if (op.isEmpty() || field.isEmpty()) return false;

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

    private boolean compare(Object actual, String op, JsonNode value) {
        switch (op) {
            case "eq":  return equalsLoose(actual, value);
            case "neq": return !equalsLoose(actual, value);
            // Comparadores numéricos: si no hay dato real para comparar
            // (actual==null o value vacío/no-convertible), el filtro falla.
            // Esto evita el bug histórico donde lt/lte daban true por usar
            // Integer.MIN_VALUE como sentinel.
            case "gt":  { Integer cmp = compareNumericOrNull(actual, value); return cmp != null && cmp > 0; }
            case "gte": { Integer cmp = compareNumericOrNull(actual, value); return cmp != null && cmp >= 0; }
            case "lt":  { Integer cmp = compareNumericOrNull(actual, value); return cmp != null && cmp < 0; }
            case "lte": { Integer cmp = compareNumericOrNull(actual, value); return cmp != null && cmp <= 0; }
            case "in":  return inArray(actual, value);
            case "nin": return !inArray(actual, value);
            case "contains":
                if (actual == null || !value.isTextual()) return false;
                return actual.toString().toLowerCase().contains(value.asText().toLowerCase());
            default: return false;
        }
    }

    private boolean equalsLoose(Object actual, JsonNode value) {
        if (actual == null) return value == null || value.isNull() || value.isMissingNode();
        if (value == null || value.isNull() || value.isMissingNode()) return false;
        if (value.isBoolean()) {
            if (actual instanceof Boolean b) return b.equals(value.asBoolean());
            return Boolean.valueOf(actual.toString()).equals(value.asBoolean());
        }
        if (value.isNumber() && actual instanceof Number n) {
            return Double.compare(n.doubleValue(), value.asDouble()) == 0;
        }
        // Tolerancia: si actual es Number y value vino como string numérico
        // ("5" en lugar de 5), comparar numéricamente igual.
        if (actual instanceof Number n && value.isTextual()) {
            try {
                return Double.compare(n.doubleValue(), Double.parseDouble(value.asText())) == 0;
            } catch (NumberFormatException ignored) { /* cae a string-compare */ }
        }
        return Objects.equals(actual.toString(), value.asText());
    }

    /**
     * Devuelve el resultado de Double.compare(actual, value), o null si no se
     * puede comparar numéricamente (uno de los dos es null/no-numérico).
     *
     * Devolver null en lugar de Integer.MIN_VALUE es lo que evita los falsos
     * positivos en lt/lte cuando el campo no tiene dato.
     */
    private Integer compareNumericOrNull(Object actual, JsonNode value) {
        if (actual == null || value == null || value.isNull() || value.isMissingNode()) return null;
        double a, b;
        try {
            a = (actual instanceof Number n) ? n.doubleValue() : Double.parseDouble(actual.toString());
        } catch (NumberFormatException e) { return null; }
        if (value.isNumber()) {
            b = value.asDouble();
        } else if (value.isTextual()) {
            try { b = Double.parseDouble(value.asText().trim()); }
            catch (NumberFormatException e) { return null; }
        } else {
            return null;
        }
        return Double.compare(a, b);
    }

    private boolean inArray(Object actual, JsonNode value) {
        if (actual == null || !value.isArray()) return false;
        String aStr = actual.toString();
        boolean actualIsNumber = actual instanceof Number;
        double aNum = actualIsNumber ? ((Number) actual).doubleValue() : 0d;
        for (JsonNode n : value) {
            if (n.asText().equals(aStr)) return true;
            if (actualIsNumber && n.isNumber() && Double.compare(aNum, n.asDouble()) == 0) return true;
        }
        return false;
    }
}
