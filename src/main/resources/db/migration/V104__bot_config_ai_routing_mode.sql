-- ─────────────────────────────────────────────────────────────────────────
-- V104__bot_config_ai_routing_mode.sql
--
-- Agrega columna `ai_routing_mode` a bot_config para configurar la
-- estrategia de routing del modelo LLM por cliente, desde el panel /admin.
--
-- Valores válidos:
--   - 'sonnet_only'   → todo Sonnet 4.5 (default, comportamiento actual).
--   - 'haiku_only'    → todo Haiku 4.5 (~3x más barato, menor capacidad).
--   - 'smart_routing' → routing per-sesión con fallback automático
--                       Haiku→Sonnet (recomendado para producción).
--
-- DEFAULT 'sonnet_only': preserva comportamiento histórico. Los bots
-- existentes y los nuevos arrancan en este modo. El cambio de estrategia
-- se hace explícitamente desde el panel /admin → módulo LLM.
-- ─────────────────────────────────────────────────────────────────────────

ALTER TABLE bot_config
    ADD COLUMN ai_routing_mode VARCHAR(30) NOT NULL DEFAULT 'sonnet_only'
    COMMENT 'Estrategia de routing LLM: sonnet_only|haiku_only|smart_routing';
