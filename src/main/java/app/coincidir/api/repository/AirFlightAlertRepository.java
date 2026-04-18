package app.coincidir.api.repository;

import app.coincidir.api.domain.AirFlightAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface AirFlightAlertRepository extends JpaRepository<AirFlightAlert, Long> {
    Optional<AirFlightAlert> findByUniqueKey(String uniqueKey);
    @Query("SELECT a FROM AirFlightAlert a WHERE (a.ignoredPermanently = false OR a.ignoredPermanently IS NULL) ORDER BY a.departureDate ASC, a.departureTime ASC")
    List<AirFlightAlert> findAllVisible();

    @Query("SELECT a FROM AirFlightAlert a WHERE a.hasIssue = true " +
           "AND (a.dismissed = false OR a.dismissed IS NULL) " +
           "AND (a.ignoredPermanently = false OR a.ignoredPermanently IS NULL) " +
           "ORDER BY a.departureDate ASC, a.departureTime ASC")
    List<AirFlightAlert> findActiveIssues();

    @Query("SELECT COUNT(a) FROM AirFlightAlert a WHERE a.hasIssue = true " +
           "AND (a.dismissed = false OR a.dismissed IS NULL) " +
           "AND (a.ignoredPermanently = false OR a.ignoredPermanently IS NULL)")
    long countActiveIssues();

    @Modifying
    @Transactional
    @Query("UPDATE AirFlightAlert a SET a.dismissed = false WHERE a.dismissed IS NULL")
    void fixNullDismissed();

    void deleteByMenuItemId(Long menuItemId);
}
