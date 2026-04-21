package app.coincidir.api.panel;

import app.coincidir.api.domain.BotConfig;
import app.coincidir.api.repository.BotConfigRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * PanelConfigController — expone SOLO los campos de BotConfig que necesita
 * el /panel (moneda, estados, sonido, brand). Accesible con cualquier JWT
 * válido (OPERATOR o PANEL_ADMIN).
 *
 * No incluye datos sensibles del admin (prompts, keys, etc).
 */
@RestController
@RequestMapping("/api/panel/config")
@RequiredArgsConstructor
public class PanelConfigController {

    private final BotConfigRepository repo;

    @GetMapping
    @Transactional(readOnly = true)
    public PanelConfigDto get() {
        BotConfig e = repo.findById(1L).orElse(null);
        PanelConfigDto d = new PanelConfigDto();
        if (e != null) {
            d.brandName             = e.getBrandName();
            d.enabledPanels         = e.getEnabledPanels();
            d.panelOrdersConfigJson = e.getPanelOrdersConfigJson();
        }
        return d;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PanelConfigDto {
        public String brandName;
        public String enabledPanels;
        public String panelOrdersConfigJson;
    }
}
