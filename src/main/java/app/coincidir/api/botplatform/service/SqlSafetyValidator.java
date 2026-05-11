package app.coincidir.api.botplatform.service;

import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * SqlSafetyValidator — valida que un SQL generado por el LLM sea seguro
 * para ejecutarse contra una BD de cliente.
 *
 * Reglas (Fase 2):
 *   1. Debe parsear como SQL válido.
 *   2. Debe ser UN SOLO statement (no admitir `;` que separe statements).
 *   3. Debe ser SELECT (Select). Cualquier UPDATE, INSERT, DELETE, DROP,
 *      ALTER, TRUNCATE, GRANT, REVOKE, CREATE, MERGE, REPLACE, EXEC, CALL
 *      se rechaza explícitamente.
 *   4. Sin keywords peligrosos por defensa en profundidad: INTO OUTFILE,
 *      INTO DUMPFILE, LOAD_FILE (vectores de MySQL para escribir/leer
 *      archivos del server).
 *
 * Por qué AST + lista negra textual:
 *   - JSqlParser cubre las structures (un INSERT no parsea como Select).
 *   - La lista negra textual cubre features SQL-vendor que JSqlParser no
 *     siempre reconoce (ej: SELECT ... INTO OUTFILE de MySQL).
 *
 * Lo que NO valida (queda para iteraciones futuras):
 *   - Subqueries que escondan UPDATE (raro en SELECT, pero existe en CTEs
 *     de Postgres). Si aparece, el motor lo rechaza al ejecutar porque
 *     pasamos read_only en el connection.
 *   - Joins con tablas blacklisteadas. Si querés ocultar tablas sensibles
 *     vendrá en Fase 4 (whitelist por conector).
 */
@Slf4j
@Component
public class SqlSafetyValidator {

    /** Palabras prohibidas — defensa en profundidad sobre el AST. */
    private static final String[] FORBIDDEN_KEYWORDS = {
            "INTO OUTFILE", "INTO DUMPFILE", "LOAD_FILE(",
            "BENCHMARK(", "SLEEP(", "PG_SLEEP("
    };

    public record ValidationResult(boolean ok, String reason) {
        // IMPORTANTE: los factories estáticos NO pueden llamarse "ok" porque
        // chocan con el accessor del campo (también llamado ok()). El compilador
        // resuelve ValidationResult.ok() al método estático y rompe expresiones
        // como `!v.ok()`. Usamos success/failure como nombres de factory.
        public static ValidationResult success() { return new ValidationResult(true, null); }
        public static ValidationResult failure(String reason) { return new ValidationResult(false, reason); }
    }

    public ValidationResult validate(String sql) {
        if (sql == null || sql.isBlank()) {
            return ValidationResult.failure("SQL vacío.");
        }
        String trimmed = sql.trim();

        // Defensa rápida: rechazar palabras peligrosas antes incluso de parsear.
        // El parser podría no reconocerlas (vendor-specific) y dejarlas pasar.
        String upper = trimmed.toUpperCase(Locale.ROOT);
        for (String forbidden : FORBIDDEN_KEYWORDS) {
            if (upper.contains(forbidden)) {
                return ValidationResult.failure("SQL rechazado: contiene `" + forbidden + "`.");
            }
        }

        // Parsear. Usamos parseStatements (plural) para detectar si el LLM
        // mandó múltiples statements separados por ;.
        Statements statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(trimmed);
        } catch (Exception e) {
            return ValidationResult.failure("SQL no parseable: " + e.getMessage());
        }

        if (statements.getStatements().size() != 1) {
            return ValidationResult.failure("Solo se permite un statement por query. " +
                    "Recibidos: " + statements.getStatements().size() + ".");
        }

        Statement stmt = statements.getStatements().get(0);

        // En JSqlParser 5.x, Select es una interface marker y las
        // implementaciones concretas son PlainSelect, Values y SetOperationList.
        // getSelectBody() está removido — el propio statement ES el select.
        if (!(stmt instanceof Select)) {
            return ValidationResult.failure("Solo se permiten queries SELECT. " +
                    "Statement recibido: " + stmt.getClass().getSimpleName() + ".");
        }

        // SELECT ... INTO otra_tabla puede crear tablas o copiar datos — bloqueado.
        // PlainSelect tiene getIntoTables(); las otras implementaciones de Select
        // (Values, SetOperationList) no.
        if (stmt instanceof PlainSelect ps) {
            if (ps.getIntoTables() != null && !ps.getIntoTables().isEmpty()) {
                return ValidationResult.failure("SELECT ... INTO no está permitido.");
            }
        }

        return ValidationResult.success();
    }
}
