package app.coincidir.api.web;

import app.coincidir.api.repository.TravelDestinationRepository;
import app.coincidir.api.web.dto.DestinationDto;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
public class ConfigController {

    private final TravelDestinationRepository destinationRepo;

    @GetMapping("/destinations")
    public List<DestinationDto> listDestinations() {
        return destinationRepo.findByActiveTrueOrderBySortOrderAscNameAsc()
                .stream()
                .map(d -> new DestinationDto(d.getCode(), d.getName()))
                .toList();
    }
}
