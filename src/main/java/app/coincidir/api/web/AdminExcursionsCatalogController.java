package app.coincidir.api.web;

import app.coincidir.api.common.exception.NotFoundException;
import app.coincidir.api.domain.ExcursionCatalog;
import app.coincidir.api.domain.TravelGroup;
import app.coincidir.api.repository.ExcursionCatalogRepository;
import app.coincidir.api.repository.PrestadorLookupDao;
import app.coincidir.api.repository.TravelGroupRepository;
import app.coincidir.api.web.dto.ExcursionCatalogItemDto;
import app.coincidir.api.web.dto.PrestadorDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@RestController
@RequestMapping("/api/admin/groups/{groupId}/excursions")
@RequiredArgsConstructor
public class AdminExcursionsCatalogController {

    private final TravelGroupRepository groupRepo;
    private final ExcursionCatalogRepository excursionCatalogRepo;
    private final PrestadorLookupDao prestadorLookup;

    @GetMapping("/catalog")
    public List<ExcursionCatalogItemDto> catalog(@PathVariable Long groupId) {
        TravelGroup group = groupRepo.findById(groupId)
                .orElseThrow(() -> new NotFoundException("Grupo no encontrado: " + groupId));

        String dest = group.getDestination() != null ? group.getDestination().trim() : "";

        if (dest.isBlank()) {
            return List.of();
        }

        // 1) match directo por descripción normalizada
        // 2) fallback: si el destino del grupo es compuesto (ej: "Ushuaia-Calafate"), buscar por cada tramo
        Map<Long, ExcursionCatalog> uniq = new LinkedHashMap<>();
        for (String candidate : expandDestinations(dest)) {
            if (candidate == null || candidate.isBlank()) continue;
            List<ExcursionCatalog> found = excursionCatalogRepo.findActiveByDestinoDescripcion(candidate);
            for (ExcursionCatalog e : found) {
                if (e != null && e.getId() != null) {
                    uniq.putIfAbsent(e.getId(), e);
                }
            }
        }

        List<ExcursionCatalog> list = new ArrayList<>(uniq.values());

        return list
                .stream()
                .map(e -> {
                    String name = (e.getNombre() != null && !e.getNombre().isBlank())
                            ? e.getNombre().trim()
                            : (e.getDescripcion() != null ? e.getDescripcion().trim() : "");
                    if (name.isBlank()) {
                        // evita opciones vacías en el combo
                        return null;
                    }

                    return new ExcursionCatalogItemDto(
                            e.getId(),
                            name,
                            e.getCostoUsd(),
                            e.getHorarioSalida() != null ? e.getHorarioSalida().toString() : null,
                            e.getHorarioRegreso() != null ? e.getHorarioRegreso().toString() : null
                    );
                })
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(ExcursionCatalogItemDto::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @GetMapping("/{excursionId}/providers")
    public List<PrestadorDto> providers(@PathVariable Long groupId, @PathVariable Long excursionId) {
        TravelGroup group = groupRepo.findById(groupId)
                .orElseThrow(() -> new NotFoundException("Grupo no encontrado: " + groupId));

        ExcursionCatalog excursion = excursionCatalogRepo.findById(excursionId)
                .orElseThrow(() -> new NotFoundException("Excursión no encontrada: " + excursionId));

        String dest = group.getDestination() != null ? group.getDestination().trim() : "";
        if (!dest.isBlank()) {
            boolean ok = false;
            for (String candidate : expandDestinations(dest)) {
                if (candidate == null || candidate.isBlank()) continue;
                Long c = excursionCatalogRepo.countExcursionInDestino(excursionId, candidate);
                if (c != null && c > 0) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La excursión no corresponde al destino del grupo");
            }
        }

        return prestadorLookup.findIdNameByExcursionId(excursionId)
                .stream()
                .map(p -> new PrestadorDto(p.id(), p.nombre()))
                .toList();
    }

    private List<String> expandDestinations(String dest) {
        if (dest == null) return List.of();
        String base = dest.trim();
        if (base.isBlank()) return List.of();

        // normalizaciones y variantes comunes
        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.add(base);

        String normalizedDash = base.replace("–", "-").replace("—", "-");
        out.add(normalizedDash);
        out.add(normalizedDash.replace(" - ", "-"));
        out.add(normalizedDash.replace("-", " - "));

        // si es un destino compuesto (ej: "Ushuaia-Calafate" o "Ushuaia / Calafate"), agregar cada tramo
        String[] parts = normalizedDash.split("[-/|]+");
        for (String p : parts) {
            String s = (p == null) ? "" : p.trim();
            if (!s.isBlank()) out.add(s);
        }

        return new ArrayList<>(out);
    }

}
