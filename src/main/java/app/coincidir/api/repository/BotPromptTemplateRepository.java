package app.coincidir.api.repository;

import app.coincidir.api.domain.BotPromptTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BotPromptTemplateRepository extends JpaRepository<BotPromptTemplate, Long> {

    /** Todos los activos, ordenados alfabéticamente por nombre (para el dropdown). */
    List<BotPromptTemplate> findByActiveTrueOrderByNameAsc();

    /** Todos, incluyendo inactivos (para el panel de gestión). */
    List<BotPromptTemplate> findAllByOrderByNameAsc();

    Optional<BotPromptTemplate> findByName(String name);
}
