-- ─────────────────────────────────────────────────────────────────────────
-- V_table_service_session.sql
--
-- Sesiones de "en servicio" de cada mesa: trackeamos desde que una mesa
-- entra en estado EN SERVICIO hasta que sale (TERMINADA, CANCELADA, o
-- vuelve a un estado anterior).
--
-- Cada fila representa UN turno completo de una mesa. Mientras la mesa
-- esté activa, `ended_at IS NULL`. Al cerrar la sesión se completa
-- `ended_at` + `ended_reason`.
--
-- Uso:
--   - El listener TableServiceTrackerService escucha BotTableChangeEvent
--     y crea/cierra sesiones según el status del record.
--   - El frontend de Smart Tables consume GET /api/admin/table-service/active
--     para mostrar el cronómetro en vivo (usando started_at como referencia).
--   - Reportes históricos (duración promedio por turno, mesas que pasan
--     el límite, etc) salen de GET /api/admin/table-service/history.
--
-- Diseño:
--   - record_id es opcional pero ÚNICO para sesiones activas (índice
--     parcial). Eso asegura idempotencia: si el listener corre dos veces
--     para el mismo update, la segunda no crea una sesión duplicada.
--   - branch_id NO es null porque queremos filtrar siempre por sucursal
--     (cada local trackea sus propias mesas). Si una reserva no tiene
--     branch asignada, no se trackea (caso edge, no debería ocurrir hoy).
--   - table_label guarda el ID textual de la mesa (ej "M1", "Barra 6")
--     como llegó del record, NO un FK a una tabla de mesas físicas
--     (porque las mesas en Smart Tables son configuración JSON, no entidades
--     SQL). Eso permite que el histórico sobreviva al renombrado de mesas.
--   - ended_reason es enum-like (string corto): 'completed' / 'cancelled'
--     / 'reverted' / 'auto_timeout'. El último no se usa hoy (la decisión
--     fue dejar las sesiones abiertas indefinidamente), pero queda
--     reservado para uso futuro.
-- ─────────────────────────────────────────────────────────────────────────

CREATE TABLE table_service_session (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,

    -- Tenancy
    branch_id BIGINT NOT NULL,

    -- Identificación de la mesa
    -- record_id puede ser NULL si la sesión fue creada manualmente sin
    -- una reserva concreta detrás. En la práctica, hoy SIEMPRE viene de
    -- un BotTableRecord, así que casi siempre estará seteada.
    record_id BIGINT NULL,

    -- bot_table_id de la BotTable que contiene el record (típicamente
    -- la tabla de "Reservas" de la marca). Útil para joins con bot_table
    -- en reportes.
    bot_table_id BIGINT NULL,

    -- ID textual de la mesa (ej "M1", "Barra-6"). NO es FK, es lo que
    -- el record tenía guardado en su columna `mesa` al momento del cambio.
    -- Si el operador renombra la mesa en el editor, este valor no cambia
    -- — preservamos el histórico exacto.
    table_label VARCHAR(64) NOT NULL,

    -- Snapshot informativo: cantidad de personas, nombre del cliente,
    -- al momento de arrancar la sesión. Sirve para reportes sin tener
    -- que joinear contra el record (que puede haber cambiado o haberse
    -- borrado). Ambos pueden ser NULL.
    persons_count INT NULL,
    title VARCHAR(255) NULL,

    -- Timestamps
    started_at TIMESTAMP NOT NULL,
    -- ended_at NULL = sesión todavía activa
    ended_at TIMESTAMP NULL DEFAULT NULL,

    -- Motivo de cierre. NULL mientras está activa.
    -- Valores esperados: 'completed', 'cancelled', 'reverted', 'auto_timeout'.
    -- Se usa string corto en lugar de un ENUM SQL para permitir agregar
    -- razones nuevas sin migrar tabla.
    ended_reason VARCHAR(20) NULL DEFAULT NULL,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_tss_branch_active (branch_id, ended_at),
    INDEX idx_tss_branch_started (branch_id, started_at DESC),
    INDEX idx_tss_record (record_id),
    INDEX idx_tss_table_label (branch_id, table_label, started_at DESC)
);

-- Idempotencia: para CUALQUIER record, solo puede haber UNA sesión activa
-- (ended_at IS NULL) a la vez. Esto asegura que si el listener corre dos
-- veces para el mismo update (por reintento de transacción, p.ej.), la
-- segunda inserción falla y el código lo trata como "ya creada".
--
-- Nota: MySQL/MariaDB no soporta índices únicos PARCIALES (con WHERE).
-- Para emular esa restricción usamos un truco: una columna generada que
-- vale `record_id` solo cuando `ended_at IS NULL`. Hacemos UNIQUE sobre
-- esa columna — al cerrar la sesión, la columna pasa a NULL y libera el
-- slot para una nueva sesión del mismo record.
ALTER TABLE table_service_session
    ADD COLUMN active_record_id BIGINT GENERATED ALWAYS AS (
        CASE WHEN ended_at IS NULL AND record_id IS NOT NULL THEN record_id ELSE NULL END
    ) VIRTUAL,
    ADD UNIQUE INDEX uniq_tss_active_record (active_record_id);
