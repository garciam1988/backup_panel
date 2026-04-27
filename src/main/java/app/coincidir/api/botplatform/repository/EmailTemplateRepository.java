package app.coincidir.api.botplatform.repository;

import app.coincidir.api.botplatform.domain.EmailTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EmailTemplateRepository extends JpaRepository<EmailTemplate, Long> {
    List<EmailTemplate> findByTableIdOrderByEventAsc(Long tableId);
    Optional<EmailTemplate> findByTableIdAndEvent(Long tableId, String event);
}
