-- ─────────────────────────────────────────────────────────────────────────
-- V_branch_smart_tables_enabled.sql
--
-- Agrega un flag por sucursal para habilitar o no el módulo Smart Tables.
--
-- Contexto:
--   El módulo Smart Tables (diseñador 3D/2D del salón, plano de mesas con
--   estado en vivo de reservas) ahora vive también en su propia URL pública
--   /smarttables — además de la sección /admin → Smart Tables. Como no
--   todas las sucursales lo usan (algunos clientes son delivery-only, o
--   sucursales chicas que no necesitan diagramar layout), agregamos un
--   toggle por sucursal.
--
-- Comportamiento:
--   - smart_tables_enabled = true  → la sucursal tiene acceso al módulo
--     desde /smarttables y desde /admin → Smart Tables (con permiso).
--   - smart_tables_enabled = false → /smarttables muestra "no activado" a
--     ADMIN/DIOS y "sin permisos suficientes" a operadores.
--     Desde /admin, la sección Smart Tables solo deja activar/desactivar.
--
-- Default: FALSE — al introducir esta feature, ninguna sucursal la tiene
-- prendida hasta que un ADMIN/DIOS la active explícitamente. Esto evita
-- exponer el módulo a operadores que no esperaban verlo.
-- ─────────────────────────────────────────────────────────────────────────

ALTER TABLE branch
    ADD COLUMN smart_tables_enabled BOOLEAN NOT NULL DEFAULT FALSE;
