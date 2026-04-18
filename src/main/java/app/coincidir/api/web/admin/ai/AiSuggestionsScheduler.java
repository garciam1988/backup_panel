package app.coincidir.api.web.admin.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiSuggestionsScheduler {

    private final AiSuggestionsService service;

    /** Una vez al día a las 03:00 AM */
    @Scheduled(cron = "0 0 3 * * *")
    public void run() {
        log.info("[AiSuggestionsScheduler] Disparo programado diario 03:00 AM");
        service.runAnalysisAsync();
    }
}
