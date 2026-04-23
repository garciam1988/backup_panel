package app.coincidir.api.botplatform.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PublicDataSourceController — expone /api/public/data-sources/prompt-context
 * sin autenticación para que el CoinBot (que no tiene JWT de admin) pueda
 * recuperar el texto consolidado de las fuentes y adjuntarlo al system prompt.
 *
 * Delega en DataSourceController#getPromptContext. No expone ningún otro
 * endpoint de gestión — solo lectura del contexto.
 */
@RestController
@RequestMapping("/api/public/data-sources")
@RequiredArgsConstructor
public class PublicDataSourceController {

    private final DataSourceController adminController;

    @GetMapping("/prompt-context")
    public DataSourceController.PromptContextResponse getPromptContext(
            @RequestParam(defaultValue = "40000") int maxTokens) {
        return adminController.getPromptContext(maxTokens);
    }
}
