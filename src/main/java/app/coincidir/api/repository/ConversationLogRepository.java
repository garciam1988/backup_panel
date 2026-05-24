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
     * - branchId: filtra por sucursal. NULL = sin filtro (modo "todas").
     *             Para filtrar por una sucursal específica, las conversaciones
     *             legacy con branchId=NULL quedan FUERA (es el comportamiento
     *             que queremos: data sin atribución no aparece en ninguna
     *             sucursal específica).
     *
     * Ordena por startedAt DESC.
     */
    @Query("SELECT c FROM ConversationLog c WHERE " +
            "(:brandName IS NULL OR LOWER(c.brandName) = LOWER(:brandName)) AND " +
            "(:clientName IS NULL OR " +
            "  LOWER(CONCAT(COALESCE(c.clientFirstName,''), ' ', COALESCE(c.clientLastName,''))) LIKE LOWER(CONCAT('%', :clientName, '%'))) AND " +
            "(:freeText IS NULL OR LOWER(c.messagesJson) LIKE LOWER(CONCAT('%', :freeText, '%'))) AND " +
            "(:branchId IS NULL OR c.branchId = :branchId) " +
            "ORDER BY c.startedAt DESC")
    Page<ConversationLog> search(@Param("brandName") String brandName,
                                 @Param("clientName") String clientName,
                                 @Param("freeText") String freeText,
                                 @Param("branchId") Long branchId,
                                 Pageable pageable);

    /** Versión legacy sin branch — la dejamos para compat con callers que no pasen el filtro. */
    default Page<ConversationLog> search(String brandName, String clientName, String freeText, Pageable pageable) {
        return search(brandName, clientName, freeText, null, pageable);
    }

    /** Lista de brand_name distintos, para poblar el selector de filtros en /admin. */
    @Query("SELECT DISTINCT c.brandName FROM ConversationLog c WHERE c.brandName IS NOT NULL AND c.brandName <> '' ORDER BY c.brandName ASC")
    List<String> findDistinctBrandNames();

    /**
     * Todas las conversaciones que pertenecen a una sucursal específica.
     * Para ClientesService cuando hay branch en el contexto. Las conversaciones
     * con branch_id NULL (legacy) NO se incluyen — son data sin atribución.
     */
    List<ConversationLog> findByBranchId(Long branchId);

    /**
     * Busca el último registro de conversación de un visitor (por visitorId).
     * Lo usa el endpoint público para hacer UPSERT: si ya hay un registro de
     * la misma conversación (mismo visitorId), lo actualiza en lugar de crear
     * un duplicado. Esto es lo que permite que el bot persista la conversación
     * apenas concreta una reserva, y después la "actualice" con todos los
     * mensajes posteriores cuando se cierra la pestaña.
     */
    Optional<ConversationLog> findFirstByVisitorIdOrderByIdDesc(String visitorId);

    /**
     * Métricas de conversación en el rango: cantidad total, duración promedio
     * en minutos, mensajes promedio por conversación.
     *
     * Devuelve [totalConversations, avgDurationMinutes, avgMessageCount].
     * Devuelve null para los promedios si no hay datos (el service maneja eso).
     *
     * Filtro defensivo: message_count >= 2 (excluye conversaciones de 1 mensaje
     * que típicamente son aperturas accidentales del bot sin engagement).
     *
     * NOTA — query nativa SQL en vez de JPQL:
     * Originalmente intenté hacer esto con JPQL usando FUNCTION('TIMESTAMPDIFF'),
     * pero Hibernate 6 no infiere el tipo de retorno de FUNCTION('TIMESTAMPDIFF')
     * y le pasa java.lang.Object a AVG(), que rechaza:
     *     "Parameter 1 of function 'avg()' has type 'NUMERIC',
     *      but argument is of type 'java.lang.Object'"
     * El backend ni siquiera arrancaba (fallo en bootstrap del repo).
     *
     * Solución: query nativa SQL. Perdemos portabilidad teórica (TIMESTAMPDIFF
     * es de MySQL) pero ya estábamos atados a MySQL de hecho, así que no es
     * pérdida real. Y arranca.
     */
    @org.springframework.data.jpa.repository.Query(
        value = "SELECT COUNT(*), " +
                "AVG(TIMESTAMPDIFF(MINUTE, started_at, ended_at)), " +
                "AVG(message_count) " +
                "FROM conversation_log " +
                "WHERE created_at >= :from AND created_at < :to " +
                "AND message_count >= 2",
        nativeQuery = true
    )
    Object[] conversationStats(
        @org.springframework.data.repository.query.Param("from") java.time.Instant from,
        @org.springframework.data.repository.query.Param("to") java.time.Instant to);
}
