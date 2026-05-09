package app.coincidir.api.repository;

import app.coincidir.api.domain.ConversationLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ConversationLogRepository extends JpaRepository<ConversationLog, Long> {

    /**
     * Búsqueda con filtros combinables. Todos los parámetros son opcionales (pasar null para no filtrar).
     * - brandName: igual exacto (case-insensitive)
     * - clientName: LIKE sobre first + last name
     * - freeText: LIKE sobre messages_json (suficiente para volumen chico).
     *
     * Ordena por startedAt DESC.
     */
    @Query("SELECT c FROM ConversationLog c WHERE " +
            "(:brandName IS NULL OR LOWER(c.brandName) = LOWER(:brandName)) AND " +
            "(:clientName IS NULL OR " +
            "  LOWER(CONCAT(COALESCE(c.clientFirstName,''), ' ', COALESCE(c.clientLastName,''))) LIKE LOWER(CONCAT('%', :clientName, '%'))) AND " +
            "(:freeText IS NULL OR LOWER(c.messagesJson) LIKE LOWER(CONCAT('%', :freeText, '%'))) " +
            "ORDER BY c.startedAt DESC")
    Page<ConversationLog> search(@Param("brandName") String brandName,
                                 @Param("clientName") String clientName,
                                 @Param("freeText") String freeText,
                                 Pageable pageable);

    /** Lista de brand_name distintos, para poblar el selector de filtros en /admin. */
    @Query("SELECT DISTINCT c.brandName FROM ConversationLog c WHERE c.brandName IS NOT NULL AND c.brandName <> '' ORDER BY c.brandName ASC")
    List<String> findDistinctBrandNames();

    /**
     * Busca el último registro de conversación de un visitor (por visitorId).
     * Lo usa el endpoint público para hacer UPSERT: si ya hay un registro de
     * la misma conversación (mismo visitorId), lo actualiza en lugar de crear
     * un duplicado. Esto es lo que permite que el bot persista la conversación
     * apenas concreta una reserva, y después la "actualice" con todos los
     * mensajes posteriores cuando se cierra la pestaña.
     */
    Optional<ConversationLog> findFirstByVisitorIdOrderByIdDesc(String visitorId);
}
