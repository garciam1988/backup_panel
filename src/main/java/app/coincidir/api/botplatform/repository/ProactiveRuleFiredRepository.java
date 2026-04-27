package app.coincidir.api.botplatform.repository;

import app.coincidir.api.botplatform.domain.ProactiveRuleFired;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProactiveRuleFiredRepository extends JpaRepository<ProactiveRuleFired, Long> {

    boolean existsByRuleIdAndSessionIdAndContextKey(Long ruleId, String sessionId, String contextKey);

    /**
     * Borra las marcas para un context_key específico de TODAS las reglas que
     * apliquen a una tabla. Se llama cuando un contexto "se cierra" (ej: la
     * mesa se libera) para que si vuelve a abrirse, las reglas puedan dispararse
     * de nuevo. Se filtra por las reglas asociadas a la table_id pasada.
     */
    @Modifying
    @Query("DELETE FROM ProactiveRuleFired f WHERE f.contextKey = :contextKey " +
           "AND f.ruleId IN (SELECT r.id FROM ProactiveRule r WHERE r.tableId = :tableId)")
    int deleteByTableIdAndContextKey(@Param("tableId") Long tableId,
                                      @Param("contextKey") String contextKey);

    @Modifying
    @Query("DELETE FROM ProactiveRuleFired f WHERE f.ruleId = :ruleId")
    int deleteByRuleId(@Param("ruleId") Long ruleId);
}
