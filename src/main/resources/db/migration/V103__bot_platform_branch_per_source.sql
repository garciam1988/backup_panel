-- ============================================================================
-- V103 — Catálogos y connectors per-branch (Bloque 1)
--
-- Agrega `branch_id` (nullable) a las dos fuentes de datos del bot:
--   - excel_catalog   → fuentes Excel/PDF/Word/TXT/URL
--   - bot_connector   → conectores SQL externos
--
-- Semántica de NULL:
--   - branch_id NULL = "global a la marca": visible para todas las sucursales.
--   - branch_id != NULL = "de esa sucursal": solo visible / editable por
--     quien tenga acceso a esa branch (o DIOS).
--
-- También cambia el UNIQUE de `name` a (`name`, `branch_id`) para permitir
-- que cada sucursal pueda tener su propio "menu", "pos_principal", etc.
-- En MySQL los NULLs no chocan entre sí en UNIQUE, así que el comportamiento
-- "global" sigue siendo posible.
--
-- TODOS LOS REGISTROS EXISTENTES quedan con branch_id = NULL = global. Esto
-- es a propósito: el deploy de Bloque 1 NO debe cambiar el comportamiento
-- visible. Los filtros y los cambios de UI vienen en Bloque 2.
-- ============================================================================

-- ─── excel_catalog ──────────────────────────────────────────────────────────

ALTER TABLE excel_catalog
    ADD COLUMN branch_id BIGINT NULL AFTER description;

-- FK a branch
ALTER TABLE excel_catalog
    ADD CONSTRAINT fk_excel_catalog_branch
    FOREIGN KEY (branch_id) REFERENCES branch(id);

-- Drop del UNIQUE antiguo sobre `name` solo. Hibernate auto-DDL le puso un
-- nombre auto-generado al constraint (típicamente "name" cuando viene de
-- @Column(unique=true)). Lo buscamos en information_schema y lo dropeamos
-- por nombre — defensivo, evita romper si Hibernate eligió otro nombre.
SET @cs := (
    SELECT CONSTRAINT_NAME
    FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'excel_catalog'
      AND CONSTRAINT_TYPE = 'UNIQUE'
      AND CONSTRAINT_NAME != 'uq_excel_catalog_name_branch'
    LIMIT 1
);

SET @sql := IF(@cs IS NULL,
    'SELECT 1',  -- no había unique viejo, no hace nada
    CONCAT('ALTER TABLE excel_catalog DROP INDEX `', @cs, '`'));
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Nuevo UNIQUE compuesto (idempotente: si ya existe por algún re-run, falla
-- silenciosamente con un IF NOT EXISTS sería ideal, pero MySQL no soporta
-- IF NOT EXISTS en CREATE UNIQUE INDEX vieja escuela. Confiamos en Flyway
-- para que esto corra una sola vez).
ALTER TABLE excel_catalog
    ADD CONSTRAINT uq_excel_catalog_name_branch UNIQUE (name, branch_id);

-- Índice de filtrado (todas las queries van a filtrar por branch_id)
CREATE INDEX idx_excel_catalog_branch ON excel_catalog(branch_id);


-- ─── bot_connector ──────────────────────────────────────────────────────────

ALTER TABLE bot_connector
    ADD COLUMN branch_id BIGINT NULL AFTER description;

ALTER TABLE bot_connector
    ADD CONSTRAINT fk_bot_connector_branch
    FOREIGN KEY (branch_id) REFERENCES branch(id);

-- Acá el constraint antiguo SÍ tiene nombre conocido (uk_bot_connector_name)
-- porque está declarado explícito en la entity. Lo dropeamos directo.
ALTER TABLE bot_connector DROP INDEX uk_bot_connector_name;

ALTER TABLE bot_connector
    ADD CONSTRAINT uk_bot_connector_name_branch UNIQUE (name, branch_id);

CREATE INDEX idx_bot_connector_branch ON bot_connector(branch_id);
