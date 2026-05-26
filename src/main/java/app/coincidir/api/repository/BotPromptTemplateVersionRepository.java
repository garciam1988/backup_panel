package app.coincidir.api.repository;

import app.coincidir.api.domain.BotPromptTemplateVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Repository de {@link BotPromptTemplateVersion}. Pequeño: solo necesitamos
 * listar versiones de un template, obtener una específica, calcular el
 * siguiente number, y borrar todas cuando se borra el template padre.
 */
public interface BotPromptTemplateVersionRepository
        extends JpaRepository<BotPromptTemplateVersion, Long> {

    /**
     * Devuelve todas las versiones de un template, ordenadas de la más
     * reciente a la más vieja. La UI las muestra en ese orden (timeline
     * descendente).
     */
    List<BotPromptTemplateVersion> findByTemplateIdOrderByVersionNumberDesc(Long templateId);

    /**
     * Calcula el siguiente versionNumber para un template. Usamos esto al
     * crear una versión nueva — devuelve MAX + 1, o 1 si no hay ninguna.
     *
     * El COALESCE garantiza que si el template no tiene versiones todavía,
     * arrancamos en 1 (en vez de NULL+1=NULL).
     */
    @Query("SELECT COALESCE(MAX(v.versionNumber), 0) + 1 FROM BotPromptTemplateVersion v WHERE v.templateId = :templateId")
    Integer findNextVersionNumber(Long templateId);

    /** Trae una versión específica de un template, validando ownership. */
    Optional<BotPromptTemplateVersion> findByIdAndTemplateId(Long id, Long templateId);

    /**
     * Borra TODAS las versiones de un template. Se llama desde
     * {@code BotPromptTemplateController.delete()} antes de borrar el
     * template, para mantener la BD consistente sin FK cascade.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM BotPromptTemplateVersion v WHERE v.templateId = :templateId")
    int deleteByTemplateId(Long templateId);

    /** Cuenta de versiones (lo usa el DTO para mostrar "12 versiones"). */
    long countByTemplateId(Long templateId);
}
