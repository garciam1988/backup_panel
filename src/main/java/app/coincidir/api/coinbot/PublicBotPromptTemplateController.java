package app.coincidir.api.coinbot;

import app.coincidir.api.domain.BotPromptTemplate;
import app.coincidir.api.repository.BotPromptTemplateRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PublicBotPromptTemplateController — endpoint PÚBLICO (sin autenticación)
 * para que el bot que ven los visitantes anónimos pueda recuperar el texto
 * de la plantilla de prompt activa configurada por el admin.
 *
 *   GET /api/public/bot-prompt-templates/{id} → un template (solo si está activo)
 *
 * El bot ya recupera la `bot-config` pública (que incluye el activePromptTemplateId);
 * con este endpoint puede además bajar el prompt text correspondiente y armar el
 * system prompt correcto. Si este endpoint no existiera, el frontend cae al
 * ORIGINAL_PROMPT hardcodeado y el bot ignora el prompt custom del admin.
 *
 * Solo se devuelven plantillas marcadas como ACTIVAS (active=true). Las inactivas
 * son borradores y no deben filtrarse al público.
 */
@Slf4j
@RestController
@RequestMapping("/api/public/bot-prompt-templates")
@RequiredArgsConstructor
public class PublicBotPromptTemplateController {

    private final BotPromptTemplateRepository repo;

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<PublicTemplateDto> getOne(@PathVariable Long id) {
        return repo.findById(id)
                .filter(t -> Boolean.TRUE.equals(t.getActive()))
                .map(PublicTemplateDto::fromEntity)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * DTO acotado: NO exponemos `description`, `updatedAt` ni metadata interna
     * — solo lo que el frontend del bot necesita para armar el system prompt.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PublicTemplateDto {
        public Long   id;
        public String name;
        public String promptText;

        public static PublicTemplateDto fromEntity(BotPromptTemplate e) {
            PublicTemplateDto d = new PublicTemplateDto();
            d.id         = e.getId();
            d.name       = e.getName();
            d.promptText = e.getPromptText();
            return d;
        }
    }
}
