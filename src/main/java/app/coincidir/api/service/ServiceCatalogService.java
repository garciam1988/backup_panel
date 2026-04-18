package app.coincidir.api.service;

import app.coincidir.api.repository.ServiceDefinitionRepository;
import app.coincidir.api.web.dto.ServiceDefinitionDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ServiceCatalogService {

    private final ServiceDefinitionRepository serviceRepo;

    @Transactional(readOnly = true)
    public List<ServiceDefinitionDto> listActiveServices() {
        return serviceRepo.findByActiveTrueOrderByNameAsc()
                .stream()
                .map(ServiceDefinitionDto::fromEntity)
                .toList();
    }
}
