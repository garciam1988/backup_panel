package app.coincidir.api.apiusage.controller;

import app.coincidir.api.apiusage.domain.ApiPricing;
import app.coincidir.api.apiusage.domain.UsageBudget;
import app.coincidir.api.apiusage.repository.ApiPricingRepository;
import app.coincidir.api.apiusage.repository.ApiUsageLogRepository;
import app.coincidir.api.apiusage.repository.UsageBudgetRepository;
import app.coincidir.api.apiusage.service.UsageStatsService;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * UsageDashboardController — endpoints admin para el dashboard de consumo.
 *
 *   GET  /api/admin/usage/summary                → tarjetas hoy/mes/año + breakdowns
 *   GET  /api/admin/usage/timeline?days=30       → datos del gráfico
 *   GET  /api/admin/usage/top-sessions?limit=10  → top conversaciones más caras
 *   GET  /api/admin/usage/budget-status          → estado actual vs límites + alerta
 *   GET  /api/admin/usage/budget                 → config actual de límites
 *   PUT  /api/admin/usage/budget                 → setear límites
 *   GET  /api/admin/usage/pricing                → tabla de precios
 *   PUT  /api/admin/usage/pricing/{id}           → editar fila de precios
 *   DEL  /api/admin/usage/logs?confirm=…         → reset del histórico
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/usage")
@RequiredArgsConstructor
public class UsageDashboardController {

    private final UsageStatsService statsService;
    private final ApiPricingRepository pricingRepo;
    private final UsageBudgetRepository budgetRepo;
    private final ApiUsageLogRepository usageLogRepo;

    @GetMapping("/summary")
    @Transactional(readOnly = true)
    public UsageStatsService.SummaryDto summary() {
        return statsService.summary();
    }

    @GetMapping("/timeline")
    @Transactional(readOnly = true)
    public List<UsageStatsService.TimelinePoint> timeline(@RequestParam(defaultValue = "30") int days) {
        return statsService.timeline(days);
    }

    @GetMapping("/top-sessions")
    @Transactional(readOnly = true)
    public List<UsageStatsService.TopSessionRow> topSessions(@RequestParam(defaultValue = "10") int limit) {
        return statsService.topSessions(limit);
    }

    /**
     * Estadísticas focalizadas en el costo del bot conversacional.
     *
     * Endpoint nuevo (vs. /summary que es legacy general). Devuelve un DTO
     * consolidado con todas las métricas pertinentes para evaluar:
     *   - Costo por mensaje y por conversación.
     *   - Cache hit rate.
     *   - Duración promedio y mensajes promedio.
     *   - Distribución por modelo (cuando smart_routing está activo).
     *   - Complejidad de conversaciones (por # de turnos).
     *   - Timeline diario, top sesiones, recomendaciones automáticas.
     *
     * Soporta ventanas de 7 o 30 días (otros valores caen a 7 por defecto).
     */
    @GetMapping("/bot-stats")
    @Transactional(readOnly = true)
    public UsageStatsService.BotStatsDto botStats(@RequestParam(defaultValue = "7") int days) {
        return statsService.botStats(days);
    }

    /**
     * Estado actual vs límites: devuelve si excede o está cerca de exceder
     * los umbrales. El frontend usa esto para mostrar el cartel rojo.
     */
    @GetMapping("/budget-status")
    @Transactional(readOnly = true)
    public BudgetStatus budgetStatus() {
        BudgetStatus s = new BudgetStatus();
        UsageBudget b = budgetRepo.findFirstByOrderByIdAsc();
        UsageStatsService.SummaryDto sum = statsService.summary();
        s.todayUsd = sum.todayUsd;
        s.monthUsd = sum.monthUsd;

        if (b == null || !Boolean.TRUE.equals(b.getAlertsEnabled())) {
            s.alertsEnabled = false;
            return s;
        }
        s.alertsEnabled = true;
        s.dailyLimitUsd = b.getDailyLimitUsd();
        s.monthlyLimitUsd = b.getMonthlyLimitUsd();
        s.dailyExceeded = b.getDailyLimitUsd() != null
                && s.todayUsd != null
                && s.todayUsd.compareTo(b.getDailyLimitUsd()) >= 0;
        s.monthlyExceeded = b.getMonthlyLimitUsd() != null
                && s.monthUsd != null
                && s.monthUsd.compareTo(b.getMonthlyLimitUsd()) >= 0;
        return s;
    }

    @GetMapping("/budget")
    @Transactional(readOnly = true)
    public BudgetDto getBudget() {
        UsageBudget b = budgetRepo.findFirstByOrderByIdAsc();
        if (b == null) return new BudgetDto(); // todos los campos null
        BudgetDto d = new BudgetDto();
        d.dailyLimitUsd = b.getDailyLimitUsd();
        d.monthlyLimitUsd = b.getMonthlyLimitUsd();
        d.alertsEnabled = b.getAlertsEnabled();
        return d;
    }

    @PutMapping("/budget")
    @Transactional
    public BudgetDto setBudget(@RequestBody BudgetDto dto) {
        UsageBudget b = budgetRepo.findFirstByOrderByIdAsc();
        if (b == null) b = new UsageBudget();
        b.setDailyLimitUsd(dto.dailyLimitUsd);
        b.setMonthlyLimitUsd(dto.monthlyLimitUsd);
        b.setAlertsEnabled(dto.alertsEnabled != null ? dto.alertsEnabled : true);
        budgetRepo.save(b);
        return getBudget();
    }

    @GetMapping("/pricing")
    @Transactional(readOnly = true)
    public List<PricingDto> listPricing() {
        List<PricingDto> result = new ArrayList<>();
        for (ApiPricing p : pricingRepo.findAllByOrderByProviderAscModelAsc()) {
            result.add(toDto(p));
        }
        return result;
    }

    @PutMapping("/pricing/{id}")
    @Transactional
    public PricingDto updatePricing(@PathVariable Long id, @RequestBody PricingDto dto) {
        ApiPricing p = pricingRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Precio no encontrado"));
        if (dto.label != null) p.setLabel(dto.label);
        p.setInputUsdPerM(dto.inputUsdPerM);
        p.setOutputUsdPerM(dto.outputUsdPerM);
        p.setCacheReadUsdPerM(dto.cacheReadUsdPerM);
        p.setCacheWriteUsdPerM(dto.cacheWriteUsdPerM);
        p.setAudioUsdPerMin(dto.audioUsdPerMin);
        p.setCharUsdPerM(dto.charUsdPerM);
        if (dto.active != null) p.setActive(dto.active);
        pricingRepo.save(p);
        return toDto(p);
    }

    private PricingDto toDto(ApiPricing p) {
        PricingDto d = new PricingDto();
        d.id = p.getId();
        d.provider = p.getProvider();
        d.model = p.getModel();
        d.label = p.getLabel();
        d.inputUsdPerM = p.getInputUsdPerM();
        d.outputUsdPerM = p.getOutputUsdPerM();
        d.cacheReadUsdPerM = p.getCacheReadUsdPerM();
        d.cacheWriteUsdPerM = p.getCacheWriteUsdPerM();
        d.audioUsdPerMin = p.getAudioUsdPerMin();
        d.charUsdPerM = p.getCharUsdPerM();
        d.active = p.getActive();
        d.updatedAt = p.getUpdatedAt();
        return d;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Reset del histórico de consumo
    // ─────────────────────────────────────────────────────────────────────
    //
    // Borra TODOS los registros de api_usage_log. Solo afecta el dashboard
    // (tarjetas hoy/mes/año + gráfico 30 días + top sesiones). NO toca
    // api_pricing (tarifas) ni usage_budget (límites de gasto).
    //
    // Requiere query param `confirm=BORRAR_TODO` para evitar disparos por
    // accidente. Devuelve cuántos registros se borraron.
    //
    // Curl de ejemplo:
    //   curl -X DELETE \
    //     -H "Authorization: Bearer <JWT_ADMIN>" \
    //     "https://api-production-XXXX.up.railway.app/api/admin/usage/logs?confirm=BORRAR_TODO"

    @DeleteMapping("/logs")
    @Transactional
    public Map<String, Object> resetLogs(@RequestParam(required = false) String confirm) {
        if (!"BORRAR_TODO".equals(confirm)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Para confirmar el reset, agregá ?confirm=BORRAR_TODO");
        }
        long before = usageLogRepo.count();
        usageLogRepo.deleteAllInBatch();
        log.warn("⚠️ api_usage_log reseteado: {} registros borrados", before);
        return Map.of(
                "deletedRows", before,
                "remainingRows", usageLogRepo.count(),
                "message", "Histórico de consumo reseteado. Refrescá el dashboard."
        );
    }

    // ─────────── DTOs ───────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BudgetStatus {
        public BigDecimal todayUsd;
        public BigDecimal monthUsd;
        public BigDecimal dailyLimitUsd;
        public BigDecimal monthlyLimitUsd;
        public Boolean alertsEnabled;
        public Boolean dailyExceeded;
        public Boolean monthlyExceeded;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BudgetDto {
        public BigDecimal dailyLimitUsd;
        public BigDecimal monthlyLimitUsd;
        public Boolean alertsEnabled;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PricingDto {
        public Long id;
        public String provider;
        public String model;
        public String label;
        public BigDecimal inputUsdPerM;
        public BigDecimal outputUsdPerM;
        public BigDecimal cacheReadUsdPerM;
        public BigDecimal cacheWriteUsdPerM;
        public BigDecimal audioUsdPerMin;
        public BigDecimal charUsdPerM;
        public Boolean active;
        public Instant updatedAt;
    }
}
