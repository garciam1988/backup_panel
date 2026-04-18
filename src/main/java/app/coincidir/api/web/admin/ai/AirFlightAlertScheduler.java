package app.coincidir.api.web.admin.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AirFlightAlertScheduler {

    private final AirFlightAlertService service;

    /** Una vez al día a las 8:00 AM */
    @Scheduled(cron = "0 0 8 * * *")
    public void run() {
        log.info("[AirFlightAlertScheduler] Disparo programado diario 08:00");
        service.runAsync();
    }
}
