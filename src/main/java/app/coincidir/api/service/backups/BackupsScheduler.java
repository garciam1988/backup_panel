package app.coincidir.api.service.backups;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BackupsScheduler {

    private final app.coincidir.api.web.admin.backups.BackupsService backupsService;

    // Chequea una vez por minuto si corresponde ejecutar el backup diario
    @Scheduled(fixedDelay = 60_000)
    public void tick() {
        backupsService.tryRunDailyIfDue();
    }
}
