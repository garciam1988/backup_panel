package app.coincidir.api.repository;

import app.coincidir.api.domain.UserProfileData;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserProfileDataRepository extends JpaRepository<UserProfileData, Long> {
    Optional<UserProfileData> findByEmail(String email);
}
