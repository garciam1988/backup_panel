-- ════════════════════════════════════════════════════════════════════════════
-- BLOQUE 1 — MÓDULO MARKETING (Loyalty + Campañas)
-- Stack: MySQL / MariaDB - Spring Boot - Hibernate (ddl-auto: update) + Flyway
--
-- Este archivo contiene TODAS las migrations del Bloque 1.
-- Ejecutar de corrido. Idempotente NO — primera instalación del módulo.
--
-- Si vas a re-ejecutar, dropear primero:
--   DROP TABLE IF EXISTS notification_log, coupon_use, coupon,
--                        campaign_recipient, marketing_campaign,
--                        marketing_segment, loyalty_redemption,
--                        loyalty_transaction, loyalty_card,
--                        loyalty_customer, loyalty_reward, loyalty_program;
--   DROP VIEW  IF EXISTS v_loyalty_daily_stats;
--   ALTER TABLE bot_config DROP COLUMN marketing_enabled,
--                          DROP COLUMN marketing_config_json;
-- ════════════════════════════════════════════════════════════════════════════


-- ─────────────────────────────────────────────────────────────────────
-- PARTE A — Flags del módulo en bot_config (singleton)
-- ─────────────────────────────────────────────────────────────────────

ALTER TABLE bot_config
  ADD COLUMN marketing_enabled TINYINT(1) NOT NULL DEFAULT 0
  AFTER voucher_api_base_url;

-- Config del módulo Marketing (JSON):
-- {
--   "programId": 1,
--   "exposedTools": ["get_loyalty_status","enroll_customer","list_rewards",
--                    "redeem_reward","get_active_campaigns","get_active_coupons"],
--   "pwaBaseUrl": "https://loyalty.cliente.com",
--   "enrollment": {
--     "askBirthdate": true,
--     "askEmail": true,
--     "consentText": "Acepto recibir promos por WhatsApp"
--   },
--   "channels": {
--     "whatsapp": { "enabled": true, "twilioFromNumber": "+5491155555555" },
--     "email":    { "enabled": true },
--     "webpush":  { "enabled": true, "vapidPublicKey": "..." }
--   }
-- }
ALTER TABLE bot_config
  ADD COLUMN marketing_config_json LONGTEXT NULL
  AFTER marketing_enabled;


-- ─────────────────────────────────────────────────────────────────────
-- PARTE B — Tablas core de Loyalty
-- ─────────────────────────────────────────────────────────────────────

-- ── PROGRAMA DE FIDELIZACIÓN ────────────────────────────────────────
CREATE TABLE loyalty_program (
    id                        BIGINT AUTO_INCREMENT PRIMARY KEY,
    name                      VARCHAR(150)  NOT NULL,
    description               TEXT          NULL,

    stamps_enabled            TINYINT(1)    NOT NULL DEFAULT 1,
    points_enabled            TINYINT(1)    NOT NULL DEFAULT 0,
    cashback_enabled          TINYINT(1)    NOT NULL DEFAULT 0,

    stamps_required           INT           NULL,
    stamps_reward_text        VARCHAR(255)  NULL,
    stamps_reset_on_redeem    TINYINT(1)    NOT NULL DEFAULT 1,

    points_per_currency       DECIMAL(10,4) NULL,
    points_expiry_days        INT           NULL,

    cashback_percentage       DECIMAL(5,2)  NULL,
    cashback_min_purchase     DECIMAL(12,2) NULL,
    cashback_expiry_days      INT           NULL,
    cashback_max_per_purchase DECIMAL(12,2) NULL,

    identification_methods    JSON          NOT NULL,

    multi_branch_mode         VARCHAR(30)   NOT NULL DEFAULT 'GLOBAL_WITH_TRACKING',

    card_design_json          JSON          NULL,

    active                    TINYINT(1)    NOT NULL DEFAULT 1,
    created_at                DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
                              ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ── CATÁLOGO DE RECOMPENSAS ─────────────────────────────────────────
CREATE TABLE loyalty_reward (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    program_id          BIGINT        NOT NULL,

    name                VARCHAR(200)  NOT NULL,
    description         TEXT          NULL,
    image_url           VARCHAR(500)  NULL,

    reward_type         VARCHAR(20)   NOT NULL,
    cost_stamps         INT           NULL,
    cost_points         INT           NULL,
    cost_cashback       DECIMAL(12,2) NULL,

    valid_from          DATETIME      NULL,
    valid_until         DATETIME      NULL,
    stock_total         INT           NULL,
    stock_remaining     INT           NULL,
    max_per_customer    INT           NULL,

    valid_days_of_week  JSON          NULL,
    valid_hours_json    JSON          NULL,
    branch_restrictions JSON          NULL,

    active              TINYINT(1)    NOT NULL DEFAULT 1,
    display_order       INT           NOT NULL DEFAULT 0,

    created_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
                        ON UPDATE CURRENT_TIMESTAMP,
    deleted_at          DATETIME      NULL,

    INDEX idx_loyalty_reward_program (program_id),
    INDEX idx_loyalty_reward_active  (program_id, active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ── CLIENTES ENROLADOS ──────────────────────────────────────────────
CREATE TABLE loyalty_customer (
    id                     BIGINT AUTO_INCREMENT PRIMARY KEY,

    customer_hash          VARCHAR(40)   NOT NULL UNIQUE,
    phone                  VARCHAR(20)   NOT NULL,
    email                  VARCHAR(150)  NULL,
    first_name             VARCHAR(100)  NOT NULL,
    last_name              VARCHAR(100)  NULL,
    birth_date             DATE          NULL,

    reservation_table_slug VARCHAR(60)   NULL,
    reservation_record_id  BIGINT        NULL,

    enrolled_at            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    enrolled_source        VARCHAR(30)   NULL,
    enrolled_branch        VARCHAR(64)   NULL,

    last_activity_at       DATETIME      NULL,
    total_visits           INT           NOT NULL DEFAULT 0,

    accepts_whatsapp       TINYINT(1)    NOT NULL DEFAULT 1,
    accepts_email          TINYINT(1)    NOT NULL DEFAULT 1,
    accepts_push           TINYINT(1)    NOT NULL DEFAULT 1,
    web_push_subscription  JSON          NULL,

    active                 TINYINT(1)    NOT NULL DEFAULT 1,

    created_at             DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
                           ON UPDATE CURRENT_TIMESTAMP,
    deleted_at             DATETIME      NULL,

    UNIQUE KEY uk_loyalty_customer_phone (phone),
    INDEX idx_loyalty_customer_email     (email),
    INDEX idx_loyalty_customer_activity  (last_activity_at),
    INDEX idx_loyalty_customer_reserv    (reservation_table_slug, reservation_record_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ── TARJETA (BALANCES ACUMULADOS) ───────────────────────────────────
CREATE TABLE loyalty_card (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id         BIGINT        NOT NULL,
    program_id          BIGINT        NOT NULL,

    current_stamps      INT           NOT NULL DEFAULT 0,
    current_points      INT           NOT NULL DEFAULT 0,
    cashback_balance    DECIMAL(12,2) NOT NULL DEFAULT 0,

    lifetime_stamps     INT           NOT NULL DEFAULT 0,
    lifetime_points     INT           NOT NULL DEFAULT 0,
    lifetime_cashback   DECIMAL(12,2) NOT NULL DEFAULT 0,

    tier_code           VARCHAR(30)   NULL,
    tier_progress       DECIMAL(10,2) NULL,

    last_stamp_at       DATETIME      NULL,
    last_redeem_at      DATETIME      NULL,

    created_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
                        ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_loyalty_card_customer (customer_id),
    INDEX idx_loyalty_card_program      (program_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ── TRANSACCIONES (HISTORIAL INMUTABLE) ─────────────────────────────
CREATE TABLE loyalty_transaction (
    id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id            BIGINT        NOT NULL,
    card_id                BIGINT        NOT NULL,

    transaction_type       VARCHAR(30)   NOT NULL,

    stamps_delta           INT           NOT NULL DEFAULT 0,
    points_delta           INT           NOT NULL DEFAULT 0,
    cashback_delta         DECIMAL(12,2) NOT NULL DEFAULT 0,

    branch_id              VARCHAR(64)   NULL,
    purchase_amount        DECIMAL(12,2) NULL,

    reservation_table_slug VARCHAR(60)   NULL,
    reservation_record_id  BIGINT        NULL,

    reward_id              BIGINT        NULL,
    redemption_id          BIGINT        NULL,

    applied_rules_json     JSON          NULL,

    source                 VARCHAR(30)   NOT NULL,
    performed_by           VARCHAR(64)   NULL,
    notes                  TEXT          NULL,

    created_at             DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_loyalty_tx_customer  (customer_id, created_at DESC),
    INDEX idx_loyalty_tx_branch    (branch_id, created_at),
    INDEX idx_loyalty_tx_type      (transaction_type),
    INDEX idx_loyalty_tx_reserv    (reservation_table_slug, reservation_record_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ── CANJES DE RECOMPENSAS ───────────────────────────────────────────
CREATE TABLE loyalty_redemption (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id         BIGINT        NOT NULL,
    reward_id           BIGINT        NOT NULL,

    redemption_code     VARCHAR(20)   NOT NULL UNIQUE,

    stamps_cost         INT           NOT NULL DEFAULT 0,
    points_cost         INT           NOT NULL DEFAULT 0,
    cashback_cost       DECIMAL(12,2) NOT NULL DEFAULT 0,

    status              VARCHAR(20)   NOT NULL DEFAULT 'PENDING',

    requested_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at          DATETIME      NULL,
    redeemed_at         DATETIME      NULL,
    redeemed_branch     VARCHAR(64)   NULL,
    redeemed_by_user    VARCHAR(64)   NULL,
    cancelled_at        DATETIME      NULL,
    cancellation_reason TEXT          NULL,

    INDEX idx_redemption_customer (customer_id, status),
    INDEX idx_redemption_status   (status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ─────────────────────────────────────────────────────────────────────
-- PARTE C — Segmentos, Campañas, Cupones, Notificaciones
-- ─────────────────────────────────────────────────────────────────────

-- ── SEGMENTOS ───────────────────────────────────────────────────────
CREATE TABLE marketing_segment (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    name                VARCHAR(150)  NOT NULL,
    description         TEXT          NULL,

    criteria_json       JSON          NOT NULL,

    estimated_size      INT           NULL,
    last_computed_at    DATETIME      NULL,

    active              TINYINT(1)    NOT NULL DEFAULT 1,

    created_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
                        ON UPDATE CURRENT_TIMESTAMP,
    deleted_at          DATETIME      NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ── CAMPAÑAS ────────────────────────────────────────────────────────
CREATE TABLE marketing_campaign (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    name                    VARCHAR(150)  NOT NULL,
    description             TEXT          NULL,

    segment_id              BIGINT        NULL,
    target_filter_json      JSON          NULL,

    channels_json           JSON          NOT NULL,

    message_whatsapp        TEXT          NULL,
    message_email_subject   VARCHAR(200)  NULL,
    message_email_body      LONGTEXT      NULL,
    message_push_title      VARCHAR(100)  NULL,
    message_push_body       VARCHAR(255)  NULL,

    cta_url                 VARCHAR(500)  NULL,
    coupon_id               BIGINT        NULL,

    schedule_type           VARCHAR(20)   NOT NULL DEFAULT 'IMMEDIATE',
    scheduled_at            DATETIME      NULL,
    recurrence_config_json  JSON          NULL,
    trigger_config_json     JSON          NULL,

    status                  VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',

    total_targeted          INT           NOT NULL DEFAULT 0,
    total_sent              INT           NOT NULL DEFAULT 0,
    total_delivered         INT           NOT NULL DEFAULT 0,
    total_opened            INT           NOT NULL DEFAULT 0,
    total_clicked           INT           NOT NULL DEFAULT 0,
    total_converted         INT           NOT NULL DEFAULT 0,

    created_at              DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
                            ON UPDATE CURRENT_TIMESTAMP,
    started_at              DATETIME      NULL,
    completed_at            DATETIME      NULL,

    INDEX idx_campaign_status    (status),
    INDEX idx_campaign_scheduled (schedule_type, scheduled_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ── DESTINATARIOS DE CAMPAÑA ────────────────────────────────────────
CREATE TABLE campaign_recipient (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    campaign_id         BIGINT        NOT NULL,
    customer_id         BIGINT        NOT NULL,

    whatsapp_status     VARCHAR(20)   NULL,
    email_status        VARCHAR(20)   NULL,
    push_status         VARCHAR(20)   NULL,

    whatsapp_sent_at    DATETIME      NULL,
    email_sent_at       DATETIME      NULL,
    push_sent_at        DATETIME      NULL,

    opened_at           DATETIME      NULL,
    clicked_at          DATETIME      NULL,
    converted_at        DATETIME      NULL,
    conversion_value    DECIMAL(12,2) NULL,

    error_message       TEXT          NULL,

    created_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uk_camp_recip (campaign_id, customer_id),
    INDEX idx_camp_recip_customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ── CUPONES ─────────────────────────────────────────────────────────
CREATE TABLE coupon (
    id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
    code                     VARCHAR(40)   NOT NULL UNIQUE,
    name                     VARCHAR(150)  NOT NULL,
    description              TEXT          NULL,

    discount_type            VARCHAR(20)   NOT NULL,
    discount_value           DECIMAL(12,2) NULL,
    free_item_ref            VARCHAR(150)  NULL,

    min_purchase             DECIMAL(12,2) NULL,
    max_discount             DECIMAL(12,2) NULL,
    valid_from               DATETIME      NULL,
    valid_until              DATETIME      NULL,
    valid_days_of_week_json  JSON          NULL,
    valid_branches_json      JSON          NULL,

    usage_type               VARCHAR(30)   NOT NULL DEFAULT 'MULTI_USE_PER_CUSTOMER',
    max_uses_total           INT           NULL,
    max_uses_per_customer    INT           NOT NULL DEFAULT 1,
    current_uses             INT           NOT NULL DEFAULT 0,

    source                   VARCHAR(30)   NOT NULL DEFAULT 'MANUAL',
    campaign_id              BIGINT        NULL,

    active                   TINYINT(1)    NOT NULL DEFAULT 1,

    created_at               DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
                             ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_coupon_active (active, valid_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ── USOS DE CUPÓN ───────────────────────────────────────────────────
CREATE TABLE coupon_use (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    coupon_id           BIGINT        NOT NULL,
    customer_id         BIGINT        NOT NULL,

    used_at             DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    used_branch         VARCHAR(64)   NULL,
    used_by_user        VARCHAR(64)   NULL,
    purchase_amount     DECIMAL(12,2) NULL,
    discount_applied    DECIMAL(12,2) NULL,

    INDEX idx_coupon_use_coupon   (coupon_id),
    INDEX idx_coupon_use_customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ── LOG UNIFICADO DE NOTIFICACIONES ─────────────────────────────────
CREATE TABLE notification_log (
    id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id              BIGINT        NULL,

    source_type              VARCHAR(30)   NOT NULL,
    source_ref               VARCHAR(64)   NULL,

    channel                  VARCHAR(20)   NOT NULL,

    title                    VARCHAR(255)  NULL,
    body                     TEXT          NULL,
    payload_json             JSON          NULL,

    status                   VARCHAR(20)   NOT NULL,
    provider                 VARCHAR(40)   NULL,
    provider_message_id      VARCHAR(255)  NULL,
    error_message            TEXT          NULL,

    queued_at                DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at                  DATETIME      NULL,
    delivered_at             DATETIME      NULL,
    read_at                  DATETIME      NULL,

    INDEX idx_notif_log_customer  (customer_id, queued_at DESC),
    INDEX idx_notif_log_source    (source_type, source_ref),
    INDEX idx_notif_log_status    (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ─────────────────────────────────────────────────────────────────────
-- PARTE D — Vista de stats diarias (para dashboards)
-- MySQL no tiene MATERIALIZED VIEW. Usamos VIEW normal por ahora.
-- ─────────────────────────────────────────────────────────────────────

CREATE OR REPLACE VIEW v_loyalty_daily_stats AS
SELECT
    DATE(created_at)                 AS activity_date,
    transaction_type,
    branch_id,
    COUNT(*)                         AS tx_count,
    COUNT(DISTINCT customer_id)      AS unique_customers,
    SUM(stamps_delta)                AS stamps_total,
    SUM(points_delta)                AS points_total,
    SUM(cashback_delta)              AS cashback_total,
    SUM(COALESCE(purchase_amount,0)) AS purchase_total
FROM loyalty_transaction
GROUP BY DATE(created_at), transaction_type, branch_id;


-- ─────────────────────────────────────────────────────────────────────
-- PARTE E — Seed mínimo: programa singleton id=1
-- ─────────────────────────────────────────────────────────────────────

INSERT INTO loyalty_program
  (id, name, description,
   stamps_enabled, points_enabled, cashback_enabled,
   stamps_required, stamps_reward_text, stamps_reset_on_redeem,
   identification_methods, multi_branch_mode,
   card_design_json, active)
VALUES
  (1,
   'Programa de Fidelización',
   'Configurá los tipos de programa desde /marketing.',
   1, 0, 0,
   10, 'Premio principal', 1,
   JSON_ARRAY('customer_qr','local_qr','phone'),
   'GLOBAL_WITH_TRACKING',
   JSON_OBJECT(
     'primaryColor',   '#E63946',
     'secondaryColor', '#1D3557',
     'showQrOnCard',   true,
     'quickActions',   JSON_ARRAY('reserve','menu','rewards','promos')
   ),
   1)
ON DUPLICATE KEY UPDATE id = id;

-- ════════════════════════════════════════════════════════════════════════════
-- FIN BLOQUE 1
-- ════════════════════════════════════════════════════════════════════════════
