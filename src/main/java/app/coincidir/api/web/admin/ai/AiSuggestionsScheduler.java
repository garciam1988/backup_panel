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

    // ─────────────────────────────────────────────────────────────────────────
    // DESACTIVADO — 2026-05-11
    //
    // Este job corría diariamente a las 03:00 AM (cron "0 0 3 * * *") y para
    // cada grupo activo hacía 2 llamadas a Claude (análisis con haiku-4-5 +
    // filtro con sonnet-4-6) pasándole un contexto JSON gigante. Una sola
    // corrida llegó a consumir >500k tokens de input (logs de Anthropic
    // Console del 11/05: requests req_011CavJ* entre 03:21 y 03:28 AM con
    // hasta 137k tokens por llamada), agotando el crédito de la cuenta.
    //
    // Se desactiva quitando la anotación @Scheduled. La lógica del service
    // (AiSuggestionsService.runAnalysisAsync) queda intacta y puede
    // dispararse manualmente desde el admin si se necesita on-demand.
    //
    // Para reactivar: descomentar la línea @Scheduled de abajo. Si se
    // reactiva, considerar primero limitar el contexto (máximo N grupos
    // por corrida, truncar JSON de servicios) para acotar el costo.
    // ─────────────────────────────────────────────────────────────────────────
    // @Scheduled(cron = "0 0 3 * * *")
    public void run() {
        log.info("[AiSuggestionsScheduler] Disparo programado diario 03:00 AM");
        service.runAnalysisAsync();
    }
}
