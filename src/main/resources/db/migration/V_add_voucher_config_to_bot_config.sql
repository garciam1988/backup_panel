-- ─────────────────────────────────────────────────────────────────────
-- Migración: configuración de vouchers en bot_config
--
-- Campos:
--   1. vouchers_enabled (boolean)
--      Habilita las tools nativas del bot: obtener_vouchers,
--      enviar_voucher_email, descargar_voucher.
--
--   2. voucher_api_base_url (varchar(500))
--      URL base del admin panel del cliente que expone
--      /api/admin/groups/{id}/vouchers. Si está vacía, CoinBotController
--      cae al valor de application.yml (env ADMIN_PANEL_URL).
-- ─────────────────────────────────────────────────────────────────────

ALTER TABLE bot_config
  ADD COLUMN vouchers_enabled TINYINT(1) NOT NULL DEFAULT 0
  AFTER menu_config_json;

ALTER TABLE bot_config
  ADD COLUMN voucher_api_base_url VARCHAR(500) NULL DEFAULT NULL
  AFTER vouchers_enabled;

-- Configuración inicial para YES Travel (singleton id=1):
--   - tools de vouchers prendidas
--   - URL apunta al admin panel actual
UPDATE bot_config
SET vouchers_enabled = 1,
    voucher_api_base_url = 'https://admin.yes-traveluy.com'
WHERE id = 1;
