-- ─────────────────────────────────────────────────────────────────────────
-- V_branch_smart_tables_layout.sql
--
-- Layout del local guardado en base de datos por sucursal.
--
-- Antes este state vivía en localStorage del navegador, lo cual hacía que:
--   - Cambiar de PC perdiera todo el diseño.
--   - Modo incógnito mostrara un cuarto vacío.
--   - Limpiar cache borrara el trabajo.
--
-- Ahora una sola fila por sucursal contiene el JSON completo del layout:
-- forma y dimensiones del local, lista de items (mesas, sillas, paredes,
-- decoración), presets de pisos/paredes, sincronización con bot_table, etc.
-- El frontend lo carga al montar el módulo y lo persiste vía PUT con
-- debounce de 600ms cuando el usuario edita.
--
-- Decisiones de schema:
--
--   - PK simple `id`, índice único en `branch_id`: una fila por sucursal.
--     No usamos branch_id como PK directa para mantener consistencia con
--     el resto del schema (todas las tablas tienen id autoincremental) y
--     porque puede facilitar futuras evoluciones (versionado, history, etc).
--
--   - `layout_json` es LONGTEXT para tolerar layouts grandes (locales con
--     50+ items + paredes custom + polígonos). En la práctica ronda los
--     10-50 KB. MEDIUMTEXT alcanzaría, pero LONGTEXT no cuesta nada y nos
--     da margen para layouts con mucha decoración.
--
--   - `updated_by` guarda el username del último editor. Útil para auditoría
--     básica ("¿quién movió la barra?"). No es FK porque queremos
--     sobrevivir al borrado de usuarios.
--
--   - `version` para optimistic locking: si dos cajeras editan a la vez
--     en pestañas distintas, el segundo PUT detecta que la version cambió
--     y devuelve 409. El frontend muestra "alguien más editó, refrescá".
--     Hoy es muy improbable porque el modo edición lo usa solo el dueño,
--     pero como costo es trivial lo dejamos prevenido.
-- ─────────────────────────────────────────────────────────────────────────

CREATE TABLE branch_smart_tables_layout (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,

    branch_id BIGINT NOT NULL,

    -- JSON serializado con el shape { version: 1, room: {...}, items: [...] }.
    -- Mismo shape que antes se guardaba en localStorage — los componentes
    -- del frontend no cambian, solo cambia la fuente.
    layout_json LONGTEXT NOT NULL,

    -- Username del último editor. Sin FK para preservar histórico.
    updated_by VARCHAR(120) NULL,

    -- Optimistic locking. Cada PUT incrementa esto y exige que el cliente
    -- haya leído la versión actual.
    version BIGINT NOT NULL DEFAULT 1,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uniq_branch_smart_tables_layout_branch (branch_id)
);
