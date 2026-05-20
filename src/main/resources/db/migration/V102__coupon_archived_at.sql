-- V102 — Archivado de cupones.
--
-- Un cupón con usos previos (coupon_use registrados) no se puede eliminar
-- porque rompe el histórico. La alternativa es archivarlo: el cupón sigue
-- existiendo en BD (referenciable por coupon_use) pero NO aparece en la
-- lista del panel admin, y se fuerza active=false al archivarlo para que
-- tampoco aparezca en la PWA del cliente.
--
-- Si el admin quiere ver los archivados, puede pasar ?includeArchived=true
-- en el endpoint de listado.
--
-- Nota: no archivamos loyalty_reward porque ya tiene deleted_at desde V100
-- (soft delete histórico).

ALTER TABLE coupon
    ADD COLUMN archived_at DATETIME NULL AFTER active,
    ADD INDEX idx_coupon_archived (archived_at);
