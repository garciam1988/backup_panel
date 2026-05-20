-- V101 — Asociar cupones y premios a un segmento opcional.
--
-- Hasta acá los cupones y los premios aplicaban a TODOS los clientes
-- (los públicos los podía ver cualquiera). Ahora cada cupón / premio
-- puede tener un segment_id opcional:
--
--   - segment_id = NULL  → aplica a todos los clientes (comportamiento previo).
--   - segment_id = N     → solo lo ven / canjean los clientes que matcheen
--                          ese segmento en su criteria_json.
--
-- Casos de uso:
--   * Roll gratis solo para clientes con >= 10 stamps.
--   * 20% off solo para clientes que no visitan hace 30 días (reactivación).
--   * Cupón de cumple solo para los que cumplen en los próximos 7 días.
--
-- La evaluación del segmento se hace en código (SegmentEvaluator), no por FK.
-- Por eso no agregamos FOREIGN KEY: si el admin borra un segmento mientras
-- hay cupones referenciándolo, esos cupones quedan "huérfanos" y se tratan
-- como segment_id NULL (visibles a todos) en la lógica. Esto evita romper
-- promociones en producción si alguien borra un segmento por error.

ALTER TABLE coupon
    ADD COLUMN segment_id BIGINT NULL AFTER campaign_id,
    ADD INDEX idx_coupon_segment (segment_id);

ALTER TABLE loyalty_reward
    ADD COLUMN segment_id BIGINT NULL AFTER max_per_customer,
    ADD INDEX idx_loyalty_reward_segment (segment_id);
