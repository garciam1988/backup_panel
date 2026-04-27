package app.coincidir.api.apiusage.controller;

import app.coincidir.api.apiusage.domain.ApiPricing;
import app.coincidir.api.apiusage.domain.UsageBudget;
import app.coincidir.api.apiusage.repository.ApiPricingRepository;
import app.coincidir.api.apiusage.repository.UsageBudgetRepository;
import app.coincidir.api.apiusage.service.UsageStatsService;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
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
 */
@RestController
@RequestMapping("/api/admin/usage")
@RequiredArgsConstructor
public class UsageDashboardController {

    private final UsageStatsService statsService;
    private final ApiPricingRepository pricingRepo;
    private final UsageBudgetRepository budgetRepo;

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
