package app.coincidir.api.marketing.service;

import app.coincidir.api.marketing.domain.LoyaltyEarnRule;
import app.coincidir.api.marketing.repository.LoyaltyEarnRuleRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * EarnRuleEvaluator — Aplica las reglas dinámicas sobre un cálculo base de
 * earn. Se invoca desde LoyaltyTransactionService (cuando una compra/manual
 * earn ocurre) o desde un job de triggered bonuses (cumpleaños, enrolamiento).
 *
 * Política de acumulación:
 *   1. Primero aplica TODOS los BONUS que matchean (sumas absolutas).
 *   2. Después aplica TODOS los MULTIPLIER que matchean (factores).
 *   3. Resultado = (base + bonus) * multiplier_total
 *
 * Esto da resultados intuitivos: "+1 estampilla base, +1 cumple (bonus),
 * x2 martes (multi)" → (1+1) * 2 = 4 estampillas. Si tenés 2 multipliers
 * (martes 2x + VIP 1.5x), la multiplicación es 2 * 1.5 = 3.
 *
 * NOTA: Solo se evalúan reglas con trigger compatible:
 *   - trigger="purchase": cuando viene de una compra normal
 *   - trigger="enrollment": cuando un cliente se acaba de enrolar
 *   - trigger="birthday": cuando dispara el TriggeredCampaignJob (bonus cumple)
 *   - trigger="manual": cuando un mozo carga manualmente desde Staff
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EarnRuleEvaluator {

    private final LoyaltyEarnRuleRepository ruleRepo;
    private final LoyaltyProgramService programService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Datos del contexto de un earn para evaluar reglas. */
    public record EarnContext(
        Long customerId,
        BigDecimal purchaseAmount,    // null si no es por compra
        String branchId,              // null si no informado
        String trigger,               // "purchase" | "enrollment" | "birthday" | "manual"
        Instant when                  // momento del earn (default: now)
    ) {
        public EarnContext withWhen(Instant w) {
            return new EarnContext(customerId, purchaseAmount, branchId, trigger, w);
        }
    }

    /** Resultado: deltas a aplicar + log de reglas que matchearon. */
    public record EvalResult(
        int stamps,
        int points,
        BigDecimal cashback,
        List<AppliedRule> appliedRules
    ) {
        public boolean isEmpty() {
            return stamps == 0 && points == 0
                && (cashback == null || cashback.compareTo(BigDecimal.ZERO) == 0);
        }
    }

    /** Registro de una regla que aplicó (para auditoría en applied_rules_json). */
    public record AppliedRule(Long ruleId, String name, LoyaltyEarnRule.RuleType type, String summary) {}

    /**
     * Evalúa las reglas y devuelve los DELTAS adicionales sobre el cálculo
     * base. NO incluye el cálculo base (eso lo hace el caller).
     *
     * Ejemplo:
     *   base = 1 estampilla (porque purchase amount > 0)
     *   evaluate() devuelve: stamps=+3 (porque hay bonus +1 cumple + 2x martes
     *                                    que multiplica el (base+bonus): (1+1)*2 = 4
     *                                    → DELTA es 4-1 = 3)
     *   total final = base (1) + delta (3) = 4
     *
     * Para que el caller no tenga que repetir esa lógica, calculamos
     * directamente sobre los valores que el caller nos pasa como "base".
     */
    public EvalResult evaluate(EarnContext context,
                               int baseStamps, int basePoints, BigDecimal baseCashback) {
        Instant now = context.when() != null ? context.when() : Instant.now();
        Long programId = programService.getActiveProgram().getId();

        List<LoyaltyEarnRule> rules = ruleRepo.findActiveAt(programId, now);
        if (rules.isEmpty()) return new EvalResult(0, 0, BigDecimal.ZERO, Collections.emptyList());

        // Filtrar reglas que matchean este contexto
        List<LoyaltyEarnRule> applicable = new ArrayList<>();
        for (LoyaltyEarnRule r : rules) {
            if (matches(r, context, now)) applicable.add(r);
        }
        if (applicable.isEmpty()) return new EvalResult(0, 0, BigDecimal.ZERO, Collections.emptyList());

        BigDecimal cashbackBase = baseCashback == null ? BigDecimal.ZERO : baseCashback;

        // ── 1) Aplicar BONUS (sumas absolutas) ─────────────────────────
        int stamps  = baseStamps;
        int points  = basePoints;
        BigDecimal cashback = cashbackBase;
        List<AppliedRule> appliedLog = new ArrayList<>();

        for (LoyaltyEarnRule r : applicable) {
            if (r.getRuleType() != LoyaltyEarnRule.RuleType.BONUS) continue;
            int dS = 0, dP = 0;
            BigDecimal dC = BigDecimal.ZERO;
            if (r.getTarget() == LoyaltyEarnRule.Target.STAMPS || r.getTarget() == LoyaltyEarnRule.Target.ALL) {
                if (r.getBonusStamps() != null) dS = r.getBonusStamps();
            }
            if (r.getTarget() == LoyaltyEarnRule.Target.POINTS || r.getTarget() == LoyaltyEarnRule.Target.ALL) {
                if (r.getBonusPoints() != null) dP = r.getBonusPoints();
            }
            if (r.getTarget() == LoyaltyEarnRule.Target.CASHBACK || r.getTarget() == LoyaltyEarnRule.Target.ALL) {
                if (r.getBonusCashback() != null) dC = r.getBonusCashback();
            }
            if (dS == 0 && dP == 0 && dC.compareTo(BigDecimal.ZERO) == 0) continue;
            stamps   += dS;
            points   += dP;
            cashback  = cashback.add(dC);
            appliedLog.add(new AppliedRule(r.getId(), r.getName(), r.getRuleType(),
                summarizeBonus(dS, dP, dC)));
        }

        // ── 2) Aplicar MULTIPLIERS (factores acumulativos) ─────────────
        BigDecimal multiStamps  = BigDecimal.ONE;
        BigDecimal multiPoints  = BigDecimal.ONE;
        BigDecimal multiCash    = BigDecimal.ONE;
        for (LoyaltyEarnRule r : applicable) {
            if (r.getRuleType() != LoyaltyEarnRule.RuleType.MULTIPLIER) continue;
            if (r.getMultiplierValue() == null) continue;
            BigDecimal m = r.getMultiplierValue();
            boolean apliedAny = false;
            if (r.getTarget() == LoyaltyEarnRule.Target.STAMPS || r.getTarget() == LoyaltyEarnRule.Target.ALL) {
                multiStamps = multiStamps.multiply(m);
                apliedAny = true;
            }
            if (r.getTarget() == LoyaltyEarnRule.Target.POINTS || r.getTarget() == LoyaltyEarnRule.Target.ALL) {
                multiPoints = multiPoints.multiply(m);
                apliedAny = true;
            }
            if (r.getTarget() == LoyaltyEarnRule.Target.CASHBACK || r.getTarget() == LoyaltyEarnRule.Target.ALL) {
                multiCash = multiCash.multiply(m);
                apliedAny = true;
            }
            if (apliedAny) {
                appliedLog.add(new AppliedRule(r.getId(), r.getName(), r.getRuleType(),
                    "x" + m.stripTrailingZeros().toPlainString() + " " + r.getTarget()));
            }
        }

        int finalStamps = multiStamps.multiply(BigDecimal.valueOf(stamps)).setScale(0, RoundingMode.HALF_UP).intValue();
        int finalPoints = multiPoints.multiply(BigDecimal.valueOf(points)).setScale(0, RoundingMode.HALF_UP).intValue();
        BigDecimal finalCashback = cashback.multiply(multiCash).setScale(2, RoundingMode.HALF_UP);

        // DELTA sobre lo que el caller ya iba a aplicar
        int deltaStamps   = finalStamps - baseStamps;
        int deltaPoints   = finalPoints - basePoints;
        BigDecimal deltaC = finalCashback.subtract(cashbackBase);

        return new EvalResult(deltaStamps, deltaPoints, deltaC, appliedLog);
    }

    // ── Evaluación de condiciones ─────────────────────────────────────────

    /**
     * Indica si la regla aplica al contexto dado. La regla aplica si:
     *   - trigger coincide (si la regla especifica trigger)
     *   - todas las condiciones del JSON pasan
     */
    private boolean matches(LoyaltyEarnRule rule, EarnContext ctx, Instant when) {
        JsonNode conditions;
        try {
            conditions = objectMapper.readTree(rule.getConditionsJson() == null ? "{}" : rule.getConditionsJson());
        } catch (Exception e) {
            log.warn("Regla {} con conditions_json inválido, skip: {}", rule.getId(), e.getMessage());
            return false;
        }

        // trigger
        // Si la regla NO especifica trigger, asumimos que solo aplica a contextos
        // de compra/manual (no a enrollment/birthday). Esto evita que reglas
        // genéricas se dupliquen al evaluarse desde el EarnBonusService y luego
        // desde el record() del TransactionService.
        String requiredTrigger = textOrNull(conditions, "trigger");
        if (requiredTrigger == null) {
            // Default: solo matchea purchase o manual.
            if (!"purchase".equalsIgnoreCase(ctx.trigger()) && !"manual".equalsIgnoreCase(ctx.trigger())) {
                return false;
            }
        } else if (!requiredTrigger.equalsIgnoreCase(ctx.trigger())) {
            return false;
        }

        // daysOfWeek
        Set<Integer> days = readIntArray(conditions, "daysOfWeek");
        if (!days.isEmpty()) {
            // Calcular día actual en zona del programa (asumimos UTC-3 ARG; podríamos
            // hacer esto configurable a futuro)
            DayOfWeek dow = when.atZone(ZoneId.of("America/Argentina/Buenos_Aires")).getDayOfWeek();
            int dayNum = dow.getValue(); // lunes=1 ... domingo=7
            if (!days.contains(dayNum)) return false;
        }

        // branchIds
        Set<String> branches = readStringArray(conditions, "branchIds");
        if (!branches.isEmpty()) {
            if (ctx.branchId() == null || !branches.contains(ctx.branchId())) return false;
        }

        // minPurchase / maxPurchase
        BigDecimal minP = readDecimal(conditions, "minPurchase");
        BigDecimal maxP = readDecimal(conditions, "maxPurchase");
        if (minP != null || maxP != null) {
            // Si hay regla de monto pero no hay compra (ej. trigger=birthday), no aplica
            if (ctx.purchaseAmount() == null) return false;
            if (minP != null && ctx.purchaseAmount().compareTo(minP) < 0) return false;
            if (maxP != null && ctx.purchaseAmount().compareTo(maxP) > 0) return false;
        }

        return true;
    }

    // ── Helpers de parseo de JSON ────────────────────────────────────────

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText();
        return s.isBlank() ? null : s;
    }

    private static BigDecimal readDecimal(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        try { return new BigDecimal(v.asText()); }
        catch (Exception e) { return null; }
    }

    private static Set<Integer> readIntArray(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isArray()) return Collections.emptySet();
        Set<Integer> out = new HashSet<>();
        v.forEach(n -> { try { out.add(n.asInt()); } catch (Exception ignored) {} });
        return out;
    }

    private static Set<String> readStringArray(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isArray()) return Collections.emptySet();
        Set<String> out = new HashSet<>();
        v.forEach(n -> { if (n.isTextual()) out.add(n.asText()); });
        return out;
    }

    private static String summarizeBonus(int s, int p, BigDecimal c) {
        List<String> parts = new ArrayList<>();
        if (s != 0) parts.add((s > 0 ? "+" : "") + s + " STAMPS");
        if (p != 0) parts.add((p > 0 ? "+" : "") + p + " POINTS");
        if (c.compareTo(BigDecimal.ZERO) != 0) parts.add((c.signum() > 0 ? "+" : "") + c.toPlainString() + " CASHBACK");
        return String.join(", ", parts);
    }

    /** Serializa el log de reglas a JSON para guardar en la transacción. */
    public String serializeApplied(List<AppliedRule> applied) {
        if (applied == null || applied.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(applied);
        } catch (Exception e) {
            return null;
        }
    }
}
