package app.coincidir.api.repository;

import app.coincidir.api.domain.Language;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LanguageRepository extends JpaRepository<Language, Long> {
    Optional<Language> findByCode(String code);
    Optional<Language> findByCodeIgnoreCase(String code);
    boolean existsByCodeIgnoreCase(String code);
    List<Language> findAllByOrderByDisplayOrderAscNameAsc();
    List<Language> findAllByEnabledTrueOrderByDisplayOrderAscNameAsc();
    Optional<Language> findFirstByIsDefaultTrue();
}
