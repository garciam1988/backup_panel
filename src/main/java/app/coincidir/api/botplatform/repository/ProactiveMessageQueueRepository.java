package app.coincidir.api.botplatform.repository;

import app.coincidir.api.botplatform.domain.ProactiveMessageQueue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ProactiveMessageQueueRepository extends JpaRepository<ProactiveMessageQueue, Long> {

    /** Pendientes de entrega para una sesión (no entregados todavía), ordenados
     *  por orden de creación para que el cliente los reciba en orden. */
    List<ProactiveMessageQueue> findBySessionIdAndDeliveredAtIsNullOrderByCreatedAtAsc(String sessionId);

    /** Cleanup: borra mensajes entregados hace más de N días (si quedó vivo
     *  el cron — opcional, no es crítico). */
    @Modifying
    @Query("DELETE FROM ProactiveMessageQueue m WHERE m.deliveredAt IS NOT NULL AND m.deliveredAt < :cutoff")
    int deleteDeliveredBefore(@Param("cutoff") Instant cutoff);
}
