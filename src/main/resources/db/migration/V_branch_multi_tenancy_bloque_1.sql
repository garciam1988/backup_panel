-- ═══════════════════════════════════════════════════════════════════════════
-- V_branch_multi_tenancy_bloque_1.sql
-- ═══════════════════════════════════════════════════════════════════════════
--
-- BLOQUE 1 — Infraestructura de Brand + Branch (sin tocar tablas existentes).
--
-- Objetivo:
--   1. Crear tabla `brand` (marca/cliente) y `branch` (sucursal).
--   2. Insertar una marca + sucursal "default" para que la data legacy tenga
--      a quién apuntar cuando otros bloques empiecen a agregar `branch_id`
--      a las tablas operacionales.
--   3. CERO impacto en el comportamiento actual: ninguna tabla existente se
--      toca en este bloque. Las apps siguen leyendo y escribiendo igual.
--
-- Reversible:
--   Ver V_branch_multi_tenancy_bloque_1_down.sql para el rollback.
-- ═══════════════════════════════════════════════════════════════════════════

-- ─────────────────────────────────────────────────────────────────────────
-- Tabla: brand
--   La marca / cliente / cuenta del sistema. Hoy hay una por deploy (YES
--   Travel, Mikhuna, Brasas), pero el modelo permite varias en el mismo
--   deploy si en el futuro se quisiera.
--
--   `multi_branch_enabled` = false por default. Cuando un cliente lo activa,
--   el frontend muestra el selector de sucursal. Si está en false, todo
--   se comporta como si la única sucursal fuera la "default" (transparente
--   para el usuario).
-- ─────────────────────────────────────────────────────────────────────────
CREATE TABLE brand (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    slug                  VARCHAR(64)  NOT NULL,
    name                  VARCHAR(150) NOT NULL,
    multi_branch_enabled  BOOLEAN      NOT NULL DEFAULT FALSE,
    timezone_default      VARCHAR(64)  NOT NULL DEFAULT 'America/Argentina/Buenos_Aires',
    active                BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_brand_slug (slug)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ─────────────────────────────────────────────────────────────────────────
-- Tabla: branch
--   Sucursal / local físico de una marca. Toda fila operacional del sistema
--   (en bloques posteriores) va a tener un `branch_id` apuntando acá.
--
--   `slug` es lo que aparece en la URL del bot público:
--     bot.mikhuna.com.ar/palermo  → branch_slug = "palermo"
--
--   `default_for_brand` marca cuál es la sucursal "principal" / fallback de
--   la marca. Cuando `brand.multi_branch_enabled = false`, todo se rutea
--   automáticamente a esta. También se usa para resolver el caso donde el
--   bot público no recibe slug en la URL.
-- ─────────────────────────────────────────────────────────────────────────
CREATE TABLE branch (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    brand_id            BIGINT       NOT NULL,
    slug                VARCHAR(64)  NOT NULL,
    name                VARCHAR(150) NOT NULL,
    address             VARCHAR(255) NULL,
    phone               VARCHAR(40)  NULL,
    timezone            VARCHAR(64)  NOT NULL DEFAULT 'America/Argentina/Buenos_Aires',
    default_for_brand   BOOLEAN      NOT NULL DEFAULT FALSE,
    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_branch_brand FOREIGN KEY (brand_id) REFERENCES brand(id) ON DELETE CASCADE,
    UNIQUE KEY uk_branch_brand_slug (brand_id, slug),
    INDEX idx_branch_brand_default (brand_id, default_for_brand)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Solo puede haber una sucursal `default_for_brand=true` por marca.
-- Lo enforcemos con un índice único parcial emulado: una columna generada que
-- vale brand_id solo si default_for_brand=true (NULL si no), con índice único.
ALTER TABLE branch
    ADD COLUMN _default_brand_key BIGINT GENERATED ALWAYS AS (
        CASE WHEN default_for_brand = TRUE THEN brand_id ELSE NULL END
    ) STORED,
    ADD UNIQUE KEY uk_branch_one_default_per_brand (_default_brand_key);

-- ─────────────────────────────────────────────────────────────────────────
-- Seed: crear una marca y sucursal "default" basadas en bot_config actual.
--
-- Hoy bot_config es singleton (id=1). Tomamos su brand_name como nombre de
-- la marca. El slug se deriva del nombre (lowercase + sin espacios) — si
-- queda vacío o raro, usamos "default".
--
-- IMPORTANTE: este seed es idempotente — si ya existe una marca con ese
-- slug, no la duplica. Eso permite re-correr la migración en distintos
-- entornos sin romper.
-- ─────────────────────────────────────────────────────────────────────────
INSERT INTO brand (slug, name, multi_branch_enabled, timezone_default)
SELECT
    LOWER(REPLACE(REPLACE(REPLACE(
        COALESCE(NULLIF(TRIM(brand_name), ''), 'default'),
        ' ', '-'), '.', ''), '/', '-')) AS slug,
    COALESCE(NULLIF(TRIM(brand_name), ''), 'Default Brand') AS name,
    FALSE,
    'America/Argentina/Buenos_Aires'
FROM bot_config
WHERE id = 1
  AND NOT EXISTS (
      SELECT 1 FROM brand WHERE slug = LOWER(REPLACE(REPLACE(REPLACE(
          COALESCE(NULLIF(TRIM(bot_config.brand_name), ''), 'default'),
          ' ', '-'), '.', ''), '/', '-'))
  );

-- Si bot_config no tiene fila (cliente nuevo sin config inicial), igual
-- creamos una marca placeholder para que el sistema arranque consistente.
INSERT INTO brand (slug, name, multi_branch_enabled)
SELECT 'default', 'Default Brand', FALSE
WHERE NOT EXISTS (SELECT 1 FROM brand);

-- Sucursal default para cada marca recién creada (o existente sin sucursales).
INSERT INTO branch (brand_id, slug, name, default_for_brand, active)
SELECT
    b.id,
    'default',
    'Casa Central',
    TRUE,
    TRUE
FROM brand b
WHERE NOT EXISTS (
    SELECT 1 FROM branch br WHERE br.brand_id = b.id AND br.default_for_brand = TRUE
);

-- ═══════════════════════════════════════════════════════════════════════════
-- FIN del Bloque 1.
-- Después de esta migración:
--   - brand y branch existen, con una marca y una sucursal "Casa Central".
--   - Ninguna otra tabla del sistema fue tocada.
--   - El comportamiento del admin, bot público, marketing, etc. es idéntico.
-- ═══════════════════════════════════════════════════════════════════════════
