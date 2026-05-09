-- ─────────────────────────────────────────────────────────────────────
-- Migración: campos de geolocalización en conversation_log
--
-- Resuelve la IP del visitante a país/provincia/ciudad al persistir la
-- conversación, usando ip-api.com (free tier, sin registro). El resultado
-- se guarda en estas columnas para evitar llamadas externas en el listado
-- del módulo /admin > Clientes.
--
-- Si la resolución falla (timeout / rate limit / IP inválida), las columnas
-- quedan NULL y el frontend muestra "—". No bloquea el guardado.
--
-- Campos:
--   - geo_country: nombre del país en inglés ("Argentina", "United States")
--   - geo_country_code: ISO 3166-1 alpha-2 ("AR", "US") — útil para mostrar
--                       la bandera en el frontend.
--   - geo_region: provincia/estado/región
--   - geo_city: ciudad
-- ─────────────────────────────────────────────────────────────────────

ALTER TABLE conversation_log
  ADD COLUMN geo_country VARCHAR(80) NULL DEFAULT NULL
  AFTER ip_address;

ALTER TABLE conversation_log
  ADD COLUMN geo_country_code VARCHAR(2) NULL DEFAULT NULL
  AFTER geo_country;

ALTER TABLE conversation_log
  ADD COLUMN geo_region VARCHAR(100) NULL DEFAULT NULL
  AFTER geo_country_code;

ALTER TABLE conversation_log
  ADD COLUMN geo_city VARCHAR(100) NULL DEFAULT NULL
  AFTER geo_region;
