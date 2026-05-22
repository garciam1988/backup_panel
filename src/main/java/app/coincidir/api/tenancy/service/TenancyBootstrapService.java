package app.coincidir.api.tenancy.service;

import app.coincidir.api.domain.BotConfig;
import app.coincidir.api.repository.BotConfigRepository;
import app.coincidir.api.tenancy.domain.Brand;
import app.coincidir.api.tenancy.repository.BrandRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * TenancyBootstrapService — seed mínimo al arrancar.
 *
 * Filosofía:
 *   El bootstrap SOLO se asegura de que exista una marca. NADA más se crea
 *   automáticamente. DIOS es quien:
 *     - Crea las sucursales desde /admin → Sucursales.
 *     - Asigna usuarios a sucursales desde /admin → Usuarios y Roles.
 *     - Decide a qué sucursal pertenece cada record (operando con
 *       X-Branch-Id seteado en cada request).
 *
 * Esto evita "magia" donde el sistema crea data sin que el admin la haya
 * pedido. Es más prolijo y deja claro qué se hace y por qué.
 *
 * Lo que ANTES se hacía automáticamente y AHORA NO:
 *   - Crear "Casa Central" como sucursal default → eliminado.
 *   - Asignar branch default a users legacy → eliminado.
 *   - Backfillear records sin branch_id → eliminado (se quedan en NULL y
 *     el listado los ignora).
 *   - Backfillear audit logs sin branch_id → eliminado.
 *
 * Si el sistema arranca sin branches:
 *   - Bot público devuelve "no hay sucursales configuradas".
 *   - DIOS entra al admin con todos los módulos en gris hasta que cree
 *     la primera branch desde /admin → Sucursales.
 *   - Users no-DIOS sin branches NO PUEDEN ENTRAR (login devuelve 403).
 *     Eso es a propósito: si están sin asignar es porque DIOS no terminó
 *     la configuración.
 *
 * El campo `default_for_brand` en la entidad Branch sigue existiendo en BD
 * por compatibilidad pero ya NO TIENE LÓGICA asociada. Lo dejamos como
 * dead column hasta que se haga una migración formal para borrarlo.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenancyBootstrapService {

    private final BrandRepository brandRepo;
    private final BotConfigRepository botConfigRepo;

    /**
     * Paso 1 (único): Asegura que existe al menos una marca. Idempotente.
     *
     * El nombre se toma de bot_config.brand_name si está; si no, queda
     * "Default Brand" — DIOS lo cambia después desde /admin.
     */
    @Transactional
    public void ensureAtLeastOneBrand() {
        if (brandRepo.count() == 0) {
            Brand brand = createBrandFromBotConfigOrDefault();
            brandRepo.save(brand);
            log.info("[TenancyBootstrap] Marca inicial creada: slug='{}' name='{}'",
                    brand.getSlug(), brand.getName());
        } else {
            log.debug("[TenancyBootstrap] Marcas existentes: {} — skip creación", brandRepo.count());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private Brand createBrandFromBotConfigOrDefault() {
        Brand brand = new Brand();
        brand.setMultiBranchEnabled(Boolean.FALSE);
        brand.setTimezoneDefault("America/Argentina/Buenos_Aires");
        brand.setActive(Boolean.TRUE);

        Optional<BotConfig> cfg = botConfigRepo.findById(1L);
        String brandName = cfg.map(BotConfig::getBrandName)
                .filter(s -> s != null && !s.isBlank())
                .orElse("Default Brand");

        brand.setName(brandName);
        brand.setSlug(slugify(brandName));
        return brand;
    }

    private static String slugify(String name) {
        if (name == null || name.isBlank()) return "default";
        String s = name.trim().toLowerCase()
                .replace(' ', '-')
                .replace('.', '-')
                .replace('/', '-')
                .replaceAll("[^a-z0-9-]", "");
        s = s.replaceAll("-+", "-").replaceAll("^-|-$", "");
        return s.isBlank() ? "default" : s;
    }
}
