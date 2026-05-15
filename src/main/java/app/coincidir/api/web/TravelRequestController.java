package app.coincidir.api.web;

import app.coincidir.api.domain.TravelRequest;
import app.coincidir.api.repository.TravelRequestRepository;
import app.coincidir.api.service.ExportService;
import app.coincidir.api.service.StatsService;
import app.coincidir.api.service.TravelRequestService;
import app.coincidir.api.web.dto.TravelRequestCreateDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/requests")
@RequiredArgsConstructor
@CrossOrigin(origins = "http://localhost:3000")
public class TravelRequestController {
    private final TravelRequestRepository repo;
    private final TravelRequestService service;

    private final ExportService exportService;
    private final StatsService statsService;


    @PostMapping
    public ResponseEntity<TravelRequest> create(@RequestBody TravelRequestCreateDto dto) throws Exception {

        var b = TravelRequest.builder()
                .destination(dto.destination())
                .datePresetId(dto.datePresetId())
                .whenLabel(dto.whenLabel())
                .sharedRoom(Boolean.TRUE.equals(dto.sharedRoom()))
                .luggageCount(dto.luggageCount() == null ? 0 : dto.luggageCount())
                .includesTours(Boolean.TRUE.equals(dto.includesTours()))
                .travelAssistance(Boolean.TRUE.equals(dto.travelAssistance()))
                .companionPreference(dto.companionPreference() == null ? null : dto.companionPreference())
                .ageMin(dto.ageMin())
                .ageMax(dto.ageMax())
                .paxMin(dto.paxMin())
                .paxMax(dto.paxMax())
                .smokeFree(Boolean.TRUE.equals(dto.smokeFree()))
                .name(dto.name())
                .email(dto.email())
                .phone(dto.phone())
                .province(dto.province())
                // Dejamos de guardar estos campos porque ya no se usan en el front:
                // .locality(dto.locality())
                // .postalCode(dto.postalCode())
                // .birthDate(dto.birthDate())
                // .city(dto.city())
                .tz(dto.tz())
                .gender(dto.gender());

        // Travelers (totales / adultos / menores)
        if (dto.travelers() != null) {
            Integer total  = dto.travelers().total()  != null ? dto.travelers().total()  : 1;
            Integer adults = dto.travelers().adults() != null ? dto.travelers().adults() : 1;
            Integer minors = dto.travelers().minors() != null ? dto.travelers().minors() : 0;

            b.travelersTotal(total)
                    .travelersAdults(adults)
                    .travelersMinors(minors);

        } else {
            // valores por defecto por compatibilidad
            b.travelersTotal(1)
                    .travelersAdults(1)
                    .travelersMinors(0);
        }

        TravelRequest toSave = b.build();
        TravelRequest saved = service.saveAndNotify(toSave);
        return ResponseEntity.ok(saved);
    }



    @GetMapping
    public List<TravelRequest> getAll() {
        return repo.findAll();
    }

    @GetMapping("/{id}")
    public TravelRequest getById(@PathVariable("id") Long id) {
        return repo.findById(id).orElseThrow();
    }

    @GetMapping("/stats")
    public String stats(@RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        LocalDateTime f = from != null ? LocalDateTime.parse(from + "T00:00:00") : null;
        LocalDateTime t = to != null ? LocalDateTime.parse(to + "T23:59:59") : null;
        long total = repo.findAllByDateRange(f, t).size();
        return "{ \"totalRequests\": " + total + " }";
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(name = "format", defaultValue = "csv") String format) throws Exception {
        byte[] file = exportService.export(format);
        String filename = "coincidir_export_" + System.currentTimeMillis() + "." + format;
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=" + filename)
                .body(file);
    }

}
