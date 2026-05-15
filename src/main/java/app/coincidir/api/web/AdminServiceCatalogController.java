package app.coincidir.api.web;

import app.coincidir.api.service.ServiceCatalogService;
import app.coincidir.api.web.dto.ServiceDefinitionDto;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/services")
@RequiredArgsConstructor
public class AdminServiceCatalogController {

    private final ServiceCatalogService catalogService;

    @GetMapping
    public List<ServiceDefinitionDto> listServices() {
        return catalogService.listActiveServices();
    }
}
