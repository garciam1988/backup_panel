-- ─────────────────────────────────────────────────────────────────────
-- Migración: campos de contacto del local en bot_config
--
-- Se usan en la pantalla de mantenimiento del bot (cuando el backend está
-- caído) para que el visitante tenga canales alternativos para contactar
-- al cliente. También quedan disponibles para que el bot los use en su
-- prompt si querés (ej: "te paso el WhatsApp del local").
--
-- Todos opcionales. Si no se completan, la pantalla de mantenimiento no
-- muestra esa sección.
--
-- Campos:
--   - contact_whatsapp: número con formato libre, ej "+54 9 11 3926-2072"
--                       (al click se abre wa.me con los dígitos limpios)
--   - contact_phone:    teléfono fijo / alternativo
--   - contact_address:  dirección física del local — ej "Av. X 123, CABA"
-- ─────────────────────────────────────────────────────────────────────

ALTER TABLE bot_config
  ADD COLUMN contact_whatsapp VARCHAR(40) NULL DEFAULT NULL;

ALTER TABLE bot_config
  ADD COLUMN contact_phone VARCHAR(40) NULL DEFAULT NULL;

ALTER TABLE bot_config
  ADD COLUMN contact_address VARCHAR(255) NULL DEFAULT NULL;
