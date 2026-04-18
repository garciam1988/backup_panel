package app.coincidir.api.repository;

import app.coincidir.api.domain.logging.ClientLogEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ClientLogEventRepository extends JpaRepository<ClientLogEvent, Long>, JpaSpecificationExecutor<ClientLogEvent> {
}
