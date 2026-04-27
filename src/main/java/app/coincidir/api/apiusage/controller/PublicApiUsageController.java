package app.coincidir.api.apiusage.controller;

import app.coincidir.api.apiusage.domain.ApiUsageLog;
import app.coincidir.api.apiusage.service.ApiUsageService;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * PublicApiUsageController — endpoint público (sin auth) que el frontend
 * usa para reportar cada llamada que hace a Anthropic, OpenAI, ElevenLabs.
 *
 * Decisión: es un endpoint público porque las API keys de esas APIs ya
 * están en el frontend (VITE_*_API_KEY). La seguridad acá NO es crítica;
 * lo peor que puede hacer un atacante es ensuciar las estadísticas con
 * logs falsos. No expone datos sensibles.
 *
 * Future-proof: si en el futuro se proxia las APIs por backend, este
 * endpoint puede quedar deprecado y el logging se hace internamente.
 */
@Slf4j
@RestController
@RequestMapping("/api/public/api-usage")
@RequiredArgsConstructor
public class PublicApiUsageController {

    private final ApiUsageService usageService;

    @PostMapping
    public ReportResponse report(@RequestBody UsageReportRequest req) {
        ReportResponse resp = new ReportResponse();
        try {
            if (req == null || req.provider == null || req.provider.isBlank()) {
                resp.ok = false; resp.error = "provider requerido";
                return resp;
            }
            ApiUsageLog log = new ApiUsageLog();
            log.setProvider(req.provider);
            log.setModel(req.model);
            log.setInputTokens(req.inputTokens);
            log.setOutputTokens(req.outputTokens);
            log.setCacheReadTokens(req.cacheReadTokens);
            log.setCacheWriteTokens(req.cacheWriteTokens);
            log.setAudioSeconds(req.audioSeconds);
            log.setCharacters(req.characters);
            log.setFeature(req.feature);
            log.setSessionId(req.sessionId);
            log.setError(req.error);

            ApiUsageLog saved = usageService.recordUsage(log);
            resp.ok = true;
            resp.id = saved.getId();
            return resp;
        } catch (Exception e) {
            log.warn("[ApiUsage] error reportando uso: {}", e.getMessage());
            resp.ok = false;
            resp.error = e.getMessage();
            return resp;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class UsageReportRequest {
        public String provider;
        public String model;
        public Integer inputTokens;
        public Integer outputTokens;
        public Integer cacheReadTokens;
        public Integer cacheWriteTokens;
        public Integer audioSeconds;
        public Integer characters;
        public String feature;
        public String sessionId;
        public String error;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ReportResponse {
        public Boolean ok;
        public Long id;
        public String error;
    }
}
