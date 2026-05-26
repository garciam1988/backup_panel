-- V106 — Versionado de Bot Prompt Templates
--
-- Permite mantener un historial de cambios sobre bot_prompt_template y
-- restaurar versiones anteriores desde el panel /admin → Prompt.
--
-- Diseño:
--   - Cada UPDATE de bot_prompt_template guarda el state PREVIO como una
--     fila acá (lo hace el controller — no usamos triggers para mantener
--     la lógica centralizada y testeable).
--   - version_number es secuencial dentro del template (1, 2, 3...).
--   - Las versiones son inmutables: solo INSERT, nunca UPDATE/DELETE
--     (excepto cuando se borra el template padre).

CREATE TABLE IF NOT EXISTS bot_prompt_template_version (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    template_id     BIGINT       NOT NULL,
    version_number  INT          NOT NULL,
    name            VARCHAR(100) NOT NULL,
    description     VARCHAR(300),
    prompt_text     LONGTEXT     NOT NULL,
    active          TINYINT(1)   NOT NULL DEFAULT 1,
    reason          VARCHAR(20),
    created_by      VARCHAR(80),
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bptv_template_version (template_id, version_number),
    KEY idx_bptv_template_id (template_id),
    KEY idx_bptv_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Backfill: para cada template existente que NO tenga ninguna versión aún,
-- creamos la versión inicial (versionNumber=1, reason='initial') con el
-- state actual. Así, el historial nunca arranca vacío para templates que
-- ya existían antes de este módulo — el operador ve "Versión 1 — initial"
-- al instalar y a partir de los próximos edits van llenándose 2, 3, ...
--
-- IMPORTANTE: el WHERE NOT EXISTS hace esto idempotente. Si la migración
-- se corre más de una vez (raro pero posible en un repair), no duplica.

INSERT INTO bot_prompt_template_version
    (template_id, version_number, name, description, prompt_text, active, reason, created_by, created_at)
SELECT
    t.id,
    1,
    t.name,
    t.description,
    t.prompt_text,
    t.active,
    'initial',
    NULL,
    COALESCE(t.created_at, NOW())
FROM bot_prompt_template t
WHERE NOT EXISTS (
    SELECT 1 FROM bot_prompt_template_version v
    WHERE v.template_id = t.id
);
