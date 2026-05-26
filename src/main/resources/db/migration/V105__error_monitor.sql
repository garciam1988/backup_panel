-- V105 — Error Monitor (módulo /admin restringido a DIOS)
--
-- Agrega los campos necesarios para que la tabla client_log_event sirva como
-- almacenamiento unificado de:
--   - Errores y warnings del frontend (window.onerror, unhandledrejection,
--     fetch fallidos, React error boundary, breadcrumbs de acciones previas)
--   - Excepciones del backend (capturadas por ApiExceptionHandler)
--   - Eventos WARN/ERROR del propio backend (Logback appender)
--
-- Todos los ALTERS son aditivos y usan IF NOT EXISTS para ser idempotentes.
-- Las filas existentes (pre-error-monitor) quedan con source='frontend',
-- status='open' por default — sin migración de datos necesaria.
--
-- Notas de compatibilidad:
--  - MySQL 8.0+ soporta "ADD COLUMN IF NOT EXISTS" — Railway usa esa versión.
--  - Si el deploy es a una MySQL más vieja (5.7), reemplazar IF NOT EXISTS
--    por un bloque condicional INFORMATION_SCHEMA o correr el ALTER manual.

ALTER TABLE client_log_event
    ADD COLUMN IF NOT EXISTS source           VARCHAR(20)  DEFAULT 'frontend',
    ADD COLUMN IF NOT EXISTS error_type       VARCHAR(40),
    ADD COLUMN IF NOT EXISTS short_desc       VARCHAR(255),
    ADD COLUMN IF NOT EXISTS detail           LONGTEXT,
    ADD COLUMN IF NOT EXISTS recommendation   TEXT,
    ADD COLUMN IF NOT EXISTS previous_action  VARCHAR(500),
    ADD COLUMN IF NOT EXISTS status           VARCHAR(20)  DEFAULT 'open',
    ADD COLUMN IF NOT EXISTS fingerprint      VARCHAR(64),
    ADD COLUMN IF NOT EXISTS occurrence_count INT          DEFAULT 1,
    ADD COLUMN IF NOT EXISTS http_status      INT,
    ADD COLUMN IF NOT EXISTS exception_class  VARCHAR(255),
    ADD COLUMN IF NOT EXISTS resolved_by      VARCHAR(80),
    ADD COLUMN IF NOT EXISTS resolved_at      DATETIME,
    ADD COLUMN IF NOT EXISTS resolution_note  VARCHAR(500);

-- Índices para que la búsqueda y las agregaciones sean rápidas incluso con
-- decenas de miles de filas. CREATE INDEX en MySQL 8 también soporta
-- IF NOT EXISTS desde 8.0.13.

CREATE INDEX IF NOT EXISTS idx_clep_source       ON client_log_event(source);
CREATE INDEX IF NOT EXISTS idx_clep_error_type   ON client_log_event(error_type);
CREATE INDEX IF NOT EXISTS idx_clep_status       ON client_log_event(status);
CREATE INDEX IF NOT EXISTS idx_clep_fingerprint  ON client_log_event(fingerprint);
CREATE INDEX IF NOT EXISTS idx_clep_server_ts    ON client_log_event(server_ts);
CREATE INDEX IF NOT EXISTS idx_clep_level        ON client_log_event(level);

-- Backfill: las filas existentes (que vinieron solo del ingest del frontend
-- antes del módulo) las dejamos con source='frontend' explícito y status='open'
-- para que aparezcan en el panel sin tener que filtrar NULLs en cada query.

UPDATE client_log_event
   SET source = 'frontend'
 WHERE source IS NULL;

UPDATE client_log_event
   SET status = 'open'
 WHERE status IS NULL;

UPDATE client_log_event
   SET occurrence_count = 1
 WHERE occurrence_count IS NULL;
