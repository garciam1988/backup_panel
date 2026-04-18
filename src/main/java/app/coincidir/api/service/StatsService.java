package app.coincidir.api.service;

import app.coincidir.api.domain.TravelRequest;
import app.coincidir.api.repository.TravelRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StatsService {

    private final TravelRequestRepository repo;

    public Map<String, Object> getStats(LocalDateTime from, LocalDateTime to) {
        List<TravelRequest> data = repo.findAllByDateRange(from, to);
        Map<String, Object> stats = new LinkedHashMap<>();

        stats.put("totalRequests", data.size());
        stats.put("lastRequest", data.stream()
                .map(TravelRequest::getCreatedAt)
                .max(LocalDateTime::compareTo)
                .orElse(null));

        stats.put("byDestination", countBy(data, TravelRequest::getDestination));
        stats.put("byCompanionPreference", countBy(data, TravelRequest::getCompanionPreference));
        stats.put("byDatePreset", countBy(data, TravelRequest::getDatePresetId));
        stats.put("byCity", countBy(data, TravelRequest::getCity));

        double avgMin = data.stream().mapToInt(TravelRequest::getAgeMin).average().orElse(0);
        double avgMax = data.stream().mapToInt(TravelRequest::getAgeMax).average().orElse(0);
        Map<String, Double> avgAge = new LinkedHashMap<>();
        avgAge.put("min", avgMin);
        avgAge.put("max", avgMax);
        stats.put("avgAgeRange", avgAge);

        Map<String, String> period = new LinkedHashMap<>();
        period.put("from", from != null ? from.toString() : "all");
        period.put("to", to != null ? to.toString() : "all");
        stats.put("period", period);

        return stats;
    }

    private Map<String, Long> countBy(List<TravelRequest> list, java.util.function.Function<TravelRequest, String> getter) {
        return list.stream()
                .collect(Collectors.groupingBy(
                        r -> Optional.ofNullable(getter.apply(r)).orElse("Sin dato"),
                        Collectors.counting()
                ));
    }
}
