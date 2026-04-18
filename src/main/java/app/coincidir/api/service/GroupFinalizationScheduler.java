package app.coincidir.api.service;

import app.coincidir.api.domain.GroupStatus;
import app.coincidir.api.domain.TravelGroup;
import app.coincidir.api.repository.TravelGroupRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class GroupFinalizationScheduler {

    private static final ZoneId AR_TZ = ZoneId.of("America/Argentina/Buenos_Aires");

    private final TravelGroupRepository groupRepo;

    /**
     * Marca como FINALIZED los grupos cuya fecha de regreso ya pasó.
     * Corre todos los días a las 00:10 (hora Argentina).
     */
    @Scheduled(cron = "0 10 0 * * *", zone = "America/Argentina/Buenos_Aires")
    @Transactional
    public void finalizeEndedTrips() {
        LocalDate today = LocalDate.now(AR_TZ);

        // finaliza si el viaje terminó en una fecha anterior a "hoy"
        List<GroupStatus> fromStatuses = List.of(GroupStatus.CLOSED, GroupStatus.PAID);

        List<TravelGroup> toFinalize = groupRepo.findByStatusInAndTravelEndDateBefore(fromStatuses, today);
        if (toFinalize == null || toFinalize.isEmpty()) return;

        for (TravelGroup g : toFinalize) {
            g.setStatus(GroupStatus.FINALIZED);
        }
        groupRepo.saveAll(toFinalize);

        log.info("Finalizados {} grupos (travel_end_date < {}).", toFinalize.size(), today);
    }
}
