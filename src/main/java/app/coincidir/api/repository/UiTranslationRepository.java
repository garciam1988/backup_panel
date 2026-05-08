package app.coincidir.api.repository;

import app.coincidir.api.domain.UiTranslation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UiTranslationRepository extends JpaRepository<UiTranslation, Long> {
    Optional<UiTranslation> findByUiTextIdAndLanguageCode(Long uiTextId, String languageCode);
    List<UiTranslation> findByLanguageCode(String languageCode);
    List<UiTranslation> findByUiTextId(Long uiTextId);
    void deleteByLanguageCode(String languageCode);
    void deleteByUiTextId(Long uiTextId);
}
