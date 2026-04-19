package app.coincidir.api.botplatform.repository;

import app.coincidir.api.botplatform.domain.BotToolAudit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BotToolAuditRepository extends JpaRepository<BotToolAudit, Long> {
    Page<BotToolAudit> findByToolIdOrderByCreatedAtDesc(Long toolId, Pageable pageable);
    Page<BotToolAudit> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
