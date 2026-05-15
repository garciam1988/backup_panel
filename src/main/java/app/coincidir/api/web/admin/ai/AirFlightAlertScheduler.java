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
    // ─────────────────────────────────────────────────────────────────────────
    // DESACTIVADO — 2026-05-13
    //
    // Este job corría diariamente a las 08:00 AM (cron "0 0 8 * * *") y para
    // cada vuelo emitido en los próximos 72hs hacía una llamada a Claude
    // (modelo claude-opus-4-6, el más caro). Se desactiva para detener el
    // consumo de tokens junto con AiSuggestionsScheduler (ya comentado el
    // 11/05).
    //
    // La lógica del service (AirFlightAlertService.runAsync) queda intacta y
    // puede dispararse manualmente desde el admin si se necesita on-demand.
    //
    // Para reactivar: descomentar la línea @Scheduled de abajo. Si se
    // reactiva, considerar primero migrar a haiku para reducir costo y/o
    // limitar el número de vuelos por corrida.
    // ─────────────────────────────────────────────────────────────────────────
    // @Scheduled(cron = "0 0 8 * * *")
    public void run() {
        log.info("[AirFlightAlertScheduler] Disparo programado diario 08:00");
        service.runAsync();
    }
}
