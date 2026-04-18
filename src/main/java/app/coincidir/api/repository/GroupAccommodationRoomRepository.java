package app.coincidir.api.repository;

import app.coincidir.api.domain.GroupAccommodationRoom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface GroupAccommodationRoomRepository extends JpaRepository<GroupAccommodationRoom, Long> {

    List<GroupAccommodationRoom> findByAccommodationService_IdOrderByRoomNumberAsc(Long accommodationServiceId);

    @Modifying
    @Query("DELETE FROM GroupAccommodationRoom r WHERE r.accommodationService.id = :accommodationServiceId")
    void deleteByAccommodationService_Id(@Param("accommodationServiceId") Long accommodationServiceId);
}
