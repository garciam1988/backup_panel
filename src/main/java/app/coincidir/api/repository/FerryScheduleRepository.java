package app.coincidir.api.repository;

import app.coincidir.api.domain.FerrySchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FerryScheduleRepository extends JpaRepository<FerrySchedule, Long> {

    /**
     * Busca horarios por prestador + ruta bus + ruta ferry.
     *
     * Lógica para busOrigin/busDestination:
     *  - Si el parámetro llega vacío ("") → la fila debe tener bus_origin IS NULL (ruta directa sin bus).
     *  - Si el parámetro llega no vacío    → la fila debe tener bus_origin = ese valor (case-insensitive).
     *
     * Se usa native SQL para evitar inconsistencias de JPQL con COALESCE y parámetros string/NULL.
     */
    @Query(value =
        "SELECT * FROM ferry_schedules " +
        "WHERE activo = 1 " +
        "  AND LOWER(TRIM(provider)) = LOWER(TRIM(:provider)) " +
        "  AND LOWER(TRIM(ferry_origin)) = LOWER(TRIM(:ferryOrigin)) " +
        "  AND LOWER(TRIM(ferry_destination)) = LOWER(TRIM(:ferryDestination)) " +
        "  AND ( " +
        "    (:busOrigin = '' AND (bus_origin IS NULL OR TRIM(bus_origin) = '')) " +
        "    OR " +
        "    (:busOrigin != '' AND LOWER(TRIM(bus_origin)) = LOWER(TRIM(:busOrigin))) " +
        "  ) " +
        "  AND ( " +
        "    (:busDestination = '' AND (bus_destination IS NULL OR TRIM(bus_destination) = '')) " +
        "    OR " +
        "    (:busDestination != '' AND LOWER(TRIM(bus_destination)) = LOWER(TRIM(:busDestination))) " +
        "  ) " +
        "ORDER BY ferry_departure_time ASC",
        nativeQuery = true)
    List<FerrySchedule> findByRouteParams(
            @Param("provider") String provider,
            @Param("busOrigin") String busOrigin,
            @Param("busDestination") String busDestination,
            @Param("ferryOrigin") String ferryOrigin,
            @Param("ferryDestination") String ferryDestination
    );
}
