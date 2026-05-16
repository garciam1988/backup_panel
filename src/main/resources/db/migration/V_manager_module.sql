-- ════════════════════════════════════════════════════════════════════════════
-- Migration: módulo /manager (ARViz / Jarvis)
-- ════════════════════════════════════════════════════════════════════════════
--
-- Cambios:
--   1. Agrega columna user_account.manager_access (BOOLEAN, default FALSE)
--   2. Crea tabla manager_config (singleton, id=1)
--   3. Seed inicial del singleton
--
-- Safe correr más de una vez (usa IF NOT EXISTS donde es posible).
-- ════════════════════════════════════════════════════════════════════════════

-- ──────────────────────────────────────────────────────────────────────────
-- 1. user_account.manager_access
-- ──────────────────────────────────────────────────────────────────────────
-- MySQL no soporta ADD COLUMN IF NOT EXISTS hasta 8.0.16; usamos un trick
-- con information_schema para que sea idempotente.

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'user_account'
      AND COLUMN_NAME = 'manager_access'
);
SET @sql := IF(@col_exists = 0,
    'ALTER TABLE user_account ADD COLUMN manager_access BOOLEAN NOT NULL DEFAULT FALSE',
    'SELECT ''manager_access ya existe, skip'' AS msg'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Índice para queries de "usuarios con acceso manager"
SET @idx_exists := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'user_account'
      AND INDEX_NAME = 'idx_user_account_manager_access'
);
SET @sql := IF(@idx_exists = 0,
    'CREATE INDEX idx_user_account_manager_access ON user_account(manager_access)',
    'SELECT ''idx_user_account_manager_access ya existe, skip'' AS msg'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ──────────────────────────────────────────────────────────────────────────
-- 2. manager_config (singleton)
-- ──────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS manager_config (
    id                          BIGINT PRIMARY KEY,

    -- Identidad / branding
    display_name                VARCHAR(80),
    wake_word                   VARCHAR(40),
    tagline                     VARCHAR(200),
    theme_color                 VARCHAR(16),
    accent_color                VARCHAR(16),

    -- LLM
    llm_model                   VARCHAR(80),
    max_tokens                  INT,
    system_prompt               LONGTEXT,

    -- TTS
    tts_enabled                 BOOLEAN NOT NULL DEFAULT TRUE,
    tts_provider                VARCHAR(20),
    elevenlabs_voice_id         VARCHAR(128),
    elevenlabs_model            VARCHAR(80),
    tts_stability               DOUBLE,
    tts_similarity              DOUBLE,
    tts_speed                   DOUBLE,
    openai_tts_voice            VARCHAR(40),

    -- Wake / STT
    wake_word_enabled           BOOLEAN NOT NULL DEFAULT TRUE,
    stt_language                VARCHAR(16),
    silence_timeout_ms          INT,

    -- Tools
    tools_whitelist_json        LONGTEXT,

    -- UI / behavior
    max_popups                  INT,
    auto_close_popups_minutes   INT,
    quick_commands_json         TEXT,

    -- Logging
    log_conversations           BOOLEAN NOT NULL DEFAULT TRUE,

    -- Audit
    updated_at                  TIMESTAMP NULL,
    updated_by                  VARCHAR(255)
);

-- ──────────────────────────────────────────────────────────────────────────
-- 3. Seed inicial del singleton (id=1) — sólo si no existe
-- ──────────────────────────────────────────────────────────────────────────
INSERT INTO manager_config (
    id, display_name, wake_word, tagline, theme_color, accent_color,
    llm_model, max_tokens, system_prompt,
    tts_enabled, tts_provider, elevenlabs_model, tts_stability, tts_similarity, tts_speed, openai_tts_voice,
    wake_word_enabled, stt_language, silence_timeout_ms,
    tools_whitelist_json, max_popups, auto_close_popups_minutes, quick_commands_json,
    log_conversations, updated_at
)
SELECT
    1, 'JARVIS', 'jarvis', 'Asistente operativo', '#00d4ff', '#ffb84d',
    'claude-sonnet-4-20250514', 1024,
    CONCAT(
        'Sos el asistente operativo interno de la empresa. Tu interlocutor es un manager ',
        'que ya te conoce, así que NO te presentás, NO saludás de más, vas directo al grano.',
        CHAR(10), CHAR(10),
        'Hablás en español rioplatense, profesional pero amigable. Respuestas cortas ',
        '(máximo 2 oraciones cuando devolvés datos: el gráfico/tabla habla por sí solo).',
        CHAR(10), CHAR(10),
        'REGLAS:', CHAR(10),
        '1. Si el pedido necesita datos, USÁ una tool. Nunca inventes números.', CHAR(10),
        '2. Después de la tool, comentá el insight clave en 1-2 oraciones.', CHAR(10),
        '3. Si es charla general o no hace falta tool, respondé corto.', CHAR(10),
        '4. Si el pedido es ambiguo, pedí UNA aclaración puntual antes de invocar tools.'
    ),
    TRUE, 'elevenlabs', 'eleven_turbo_v2_5', 0.5, 0.75, 1.05, 'onyx',
    TRUE, 'es-AR', 1400,
    '{"botTools":["*"],"botApiTools":["*"],"botTableTools":["*"],"loyaltyTools":["*"],"sqlExec":{"enabled":false,"connectorIds":[]}}',
    4, 0, '[]',
    TRUE, NOW()
WHERE NOT EXISTS (SELECT 1 FROM manager_config WHERE id = 1);
