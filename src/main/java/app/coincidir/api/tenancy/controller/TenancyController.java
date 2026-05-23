package app.coincidir.api.tenancy.controller;

import app.coincidir.api.botplatform.repository.BotTableRecordRepository;
import app.coincidir.api.security.PermissionsService;
import app.coincidir.api.security.PermissionsService.EffectivePermissions;
import app.coincidir.api.tenancy.context.BranchContext;
import app.coincidir.api.tenancy.context.BranchContext.BranchScope;
import app.coincidir.api.tenancy.domain.Branch;
import app.coincidir.api.tenancy.domain.Brand;
import app.coincidir.api.tenancy.repository.BranchRepository;
import app.coincidir.api.tenancy.repository.BrandRepository;
import app.coincidir.api.tenancy.repository.UserBranchAccessRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.text.Normalizer;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TenancyController — endpoints para el admin sobre marca + sucursales.
 *
 * Endpoints:
 *   GET    /api/admin/tenancy/context          → marca y sucursal actuales del request
 *   GET    /api/admin/tenancy/branches         → lista de sucursales activas (selector)
 *   GET    /api/admin/tenancy/branches/all     → lista de todas (incluye inactivas) — gestión
 *   POST   /api/admin/tenancy/branches         → crear sucursal
 *   PUT    /api/admin/tenancy/branches/{id}    → editar sucursal
 *   DELETE /api/admin/tenancy/branches/{id}    → borrar sucursal (con validaciones)
 *
 * Autorización:
 *   - Los GET de lectura están abiertos a cualquier user autenticado.
 *   - Los POST/PUT/DELETE requieren canManageUsers (típicamente DIOS) porque
 *     crear/borrar sucursales es una operación delicada que afecta a todos.
 *     Si en el futuro hay un permiso más fino (canManageTenancy), cambiar acá.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/tenancy")
@RequiredArgsConstructor
public class TenancyController {

    private final BrandRepository brandRepo;
    private final BranchRepository branchRepo;
    private final UserBranchAccessRepository userBranchAccessRepo;
    private final BotTableRecordRepository botTableRecordRepo;
    private final PermissionsService permissionsService;

    // ── Lecturas (ya existían) ────────────────────────────────────────────

    @GetMapping("/context")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> context() {
        BranchScope scope = BranchContext.current();
        if (scope == null) {
            log.error("[Tenancy] /context sin BranchScope — seed del Bloque 1 no corrió?");
            return ResponseEntity.status(503).body(Map.of("error", "Sistema sin tenancy inicializado"));
        }

        Brand brand = brandRepo.findById(scope.getBrandId()).orElse(null);
        Branch branch = branchRepo.findById(scope.getBranchId()).orElse(null);
        if (brand == null || branch == null) {
            log.error("[Tenancy] /context: scope={} pero brand o branch no existen en BD", scope);
            return ResponseEntity.status(503).body(Map.of("error", "Tenancy inconsistente"));
        }

        List<Branch> branches = branchRepo.findByBrandIdAndActiveTrueOrderByNameAsc(brand.getId());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("brand", brandDto(brand));
        response.put("branch", branchDto(branch));
        response.put("branches", branches.stream().map(this::branchDto).toList());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/branches")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> branches() {
        // Mismo fix que /branches/all: usar resolveCurrentBrand para soportar
        // DIOS sin sucursal elegida (modo "Todas las sucursales").
        Brand brand = resolveCurrentBrand();
        return branchRepo.findByBrandIdAndActiveTrueOrderByNameAsc(brand.getId())
                .stream()
                .map(this::branchDto)
                .toList();
    }

    /**
     * Lista TODAS las sucursales del brand actual (activas e inactivas) con
     * estadísticas básicas (cantidad de records, cantidad de users asignados).
     * Lo usa la pantalla de gestión.
     *
     * Fix Bloque 5: antes este endpoint requería un BranchContext resuelto
     * (X-Branch-Id en header), pero eso rompía el caso de DIOS en modo
     * "Todas las sucursales" — sin sucursal elegida en el chip → sin context
     * → endpoint devolvía []. Peor todavía: la PRIMERA sucursal creada nunca
     * se podía ver hasta que DIOS la eligiera, y para elegirla tenía que verla.
     * Huevo y gallina.
     *
     * Solución: usar resolveCurrentBrand() (igual que create/update/delete),
     * que tiene fallback "primera marca de la BD" cuando no hay context.
     */
    @GetMapping("/branches/all")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> branchesAll(Authentication auth) {
        // Para listar todas (incluye inactivas) requerimos auth básica —
        // cualquier admin logueado puede ver el listado de gestión.
        if (auth == null || auth.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        Brand brand = resolveCurrentBrand();
        return branchRepo.findByBrandIdOrderByNameAsc(brand.getId())
                .stream()
                .map(this::branchDtoWithStats)
                .toList();
    }

    // ── Escrituras (Bloque 5) ─────────────────────────────────────────────

    @PostMapping("/branches")
    @Transactional
    public Map<String, Object> create(@RequestBody BranchUpsertRequest body, Authentication auth) {
        requireCanManageBranches(auth);
        Brand brand = resolveCurrentBrand();

        // Validaciones
        validateName(body.name);
        String slug = (body.slug != null && !body.slug.isBlank())
                ? slugify(body.slug)
                : slugify(body.name);
        if (slug.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Slug inválido (no quedó nada después de normalizar)");
        }
        // Unicidad: el constraint uk_branch_brand_slug nos respalda, pero
        // chequeamos acá para devolver un error claro en vez de 500.
        if (branchRepo.findByBrandIdAndSlug(brand.getId(), slug).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ya existe una sucursal con slug '" + slug + "' en esta marca");
        }

        Branch b = new Branch();
        b.setBrandId(brand.getId());
        b.setSlug(slug);
        b.setName(body.name.trim());
        b.setAddress(blankToNull(body.address));
        b.setPhone(blankToNull(body.phone));
        b.setTimezone(body.timezone != null && !body.timezone.isBlank()
                ? body.timezone : brand.getTimezoneDefault());
        b.setActive(body.active != null ? body.active : Boolean.TRUE);
        // La nueva NO es default automáticamente — el admin la marca después si quiere.
        b.setDefaultForBrand(Boolean.FALSE);
        Branch saved = branchRepo.save(b);

        log.info("[Tenancy] Sucursal creada: id={} slug='{}' name='{}' por user='{}'",
                saved.getId(), saved.getSlug(), saved.getName(), auth.getName());
        return branchDto(saved);
    }

    @PutMapping("/branches/{id}")
    @Transactional
    public Map<String, Object> update(@PathVariable Long id,
                                       @RequestBody BranchUpsertRequest body,
                                       Authentication auth) {
        requireCanManageBranches(auth);
        Brand brand = resolveCurrentBrand();

        Branch b = branchRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sucursal no encontrada"));
        if (!b.getBrandId().equals(brand.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "La sucursal pertenece a otra marca");
        }

        validateName(body.name);
        String newSlug = (body.slug != null && !body.slug.isBlank()) ? slugify(body.slug) : b.getSlug();
        if (newSlug.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Slug inválido");
        }
        // Si el slug cambió, validar unicidad.
        if (!newSlug.equals(b.getSlug())) {
            if (branchRepo.findByBrandIdAndSlug(brand.getId(), newSlug).isPresent()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Ya existe una sucursal con slug '" + newSlug + "' en esta marca");
            }
            log.warn("[Tenancy] Slug de sucursal id={} cambió '{}' → '{}'. URLs externas que apunten al slug viejo van a romperse.",
                    id, b.getSlug(), newSlug);
        }

        // Validación clave: desactivar la última sucursal activa rompería todo.
        boolean wasActive = Boolean.TRUE.equals(b.getActive());
        boolean willBeActive = body.active != null ? body.active : wasActive;
        if (wasActive && !willBeActive) {
            long activeCount = branchRepo.countByBrandIdAndActiveTrue(brand.getId());
            if (activeCount <= 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "No se puede desactivar la única sucursal activa de la marca. " +
                        "Creá otra activa primero.");
            }
        }

        b.setSlug(newSlug);
        b.setName(body.name.trim());
        b.setAddress(blankToNull(body.address));
        b.setPhone(blankToNull(body.phone));
        if (body.timezone != null && !body.timezone.isBlank()) {
            b.setTimezone(body.timezone);
        }
        if (body.active != null) b.setActive(body.active);
        Branch saved = branchRepo.save(b);

        log.info("[Tenancy] Sucursal actualizada: id={} por user='{}'", id, auth.getName());
        return branchDto(saved);
    }

    @DeleteMapping("/branches/{id}")
    @Transactional
    public Map<String, Object> delete(@PathVariable Long id, Authentication auth) {
        requireCanManageBranches(auth);
        Brand brand = resolveCurrentBrand();

        Branch b = branchRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sucursal no encontrada"));
        if (!b.getBrandId().equals(brand.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "La sucursal pertenece a otra marca");
        }

        // No se puede borrar si tiene records de bot_table_record asociados —
        // preservamos data. El admin tendría que migrar/borrar primero.
        long recordCount = botTableRecordRepo.countByBranchId(id);
        if (recordCount > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La sucursal tiene " + recordCount + " registro(s) de datos asociados " +
                    "(reservas, clientes, etc). Migrá o borrá esos registros primero.");
        }

        // No se puede borrar si tiene usuarios asignados.
        //
        // Antes contábamos con `findByBranchId(id).size()`, pero esa query
        // incluye filas HUÉRFANAS de `user_branch_access` — accesos cuyo
        // `user_id` ya no existe en `panel_user`. Esas filas quedaban cuando
        // se borraba un user sin limpiar sus accesos asociados (bug del
        // controller de delete de usuarios, ya arreglado).
        //
        // `countLiveUsersByBranchId` cuenta solo accesos vivos. Y antes de
        // contar, hacemos `deleteOrphans()` para barrer cualquier huérfana
        // que haya quedado de borrados anteriores al fix — así el admin no
        // queda atascado intentando borrar una sucursal "que no tiene
        // usuarios" según la UI pero sí según la BD vieja.
        userBranchAccessRepo.deleteOrphans();
        long usersAssigned = userBranchAccessRepo.countLiveUsersByBranchId(id);
        if (usersAssigned > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La sucursal tiene " + usersAssigned + " usuario(s) asignado(s). " +
                    "Reasigná esos usuarios a otra sucursal antes de borrar.");
        }

        // No se puede borrar la última activa
        if (Boolean.TRUE.equals(b.getActive())) {
            long activeCount = branchRepo.countByBrandIdAndActiveTrue(brand.getId());
            if (activeCount <= 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "No se puede borrar la única sucursal activa. Creá otra activa primero.");
            }
        }

        branchRepo.delete(b);
        log.info("[Tenancy] Sucursal borrada: id={} slug='{}' por user='{}'",
                id, b.getSlug(), auth.getName());
        return Map.of("ok", true, "deletedId", id);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private void requireCanManageBranches(Authentication auth) {
        if (auth == null || auth.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        EffectivePermissions perms = permissionsService.resolveByUsername(auth.getName());
        // Reusamos canManageUsers como gate. Si en el futuro hay un permiso
        // más fino (canManageTenancy), refactorizar acá.
        if (!perms.canManageUsers()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Necesitás permiso canManageUsers para gestionar sucursales.");
        }
    }

    private Brand resolveCurrentBrand() {
        BranchScope scope = BranchContext.current();
        if (scope == null) {
            // Caso edge: DIOS sin branch elegida hace una operación de tenancy.
            // Resolvemos la primera marca (hoy hay 1 por deploy).
            return brandRepo.findAll().stream().findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                            "Sin marca en BD"));
        }
        return brandRepo.findById(scope.getBrandId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Brand del scope no existe"));
    }

    private static void validateName(String name) {
        if (name == null || name.trim().length() < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El nombre de la sucursal es requerido (mínimo 2 caracteres)");
        }
        if (name.length() > 120) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El nombre es demasiado largo (máximo 120 caracteres)");
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String slugify(String name) {
        if (name == null || name.isBlank()) return "";
        String nfd = Normalizer.normalize(name, Normalizer.Form.NFD);
        String noAccents = nfd.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        String s = noAccents.trim().toLowerCase()
                .replace(' ', '-')
                .replace('.', '-')
                .replace('/', '-')
                .replaceAll("[^a-z0-9-]", "");
        s = s.replaceAll("-+", "-").replaceAll("^-|-$", "");
        return s;
    }

    // ── DTOs ──────────────────────────────────────────────────────────────

    private Map<String, Object> brandDto(Brand b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.getId());
        m.put("slug", b.getSlug());
        m.put("name", b.getName());
        m.put("multiBranchEnabled", b.getMultiBranchEnabled());
        m.put("timezoneDefault", b.getTimezoneDefault());
        m.put("active", b.getActive());
        return m;
    }

    private Map<String, Object> branchDto(Branch b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.getId());
        m.put("slug", b.getSlug());
        m.put("name", b.getName());
        m.put("address", b.getAddress());
        m.put("phone", b.getPhone());
        m.put("timezone", b.getTimezone());
        m.put("defaultForBrand", b.getDefaultForBrand());
        m.put("active", b.getActive());
        return m;
    }

    /** DTO enriquecido para la pantalla de gestión: incluye usuarios asignados y records. */
    private Map<String, Object> branchDtoWithStats(Branch b) {
        Map<String, Object> m = branchDto(b);
        try {
            long usersAssigned = userBranchAccessRepo.findByBranchId(b.getId()).size();
            m.put("usersAssigned", usersAssigned);
        } catch (Exception e) {
            m.put("usersAssigned", 0);
        }
        try {
            m.put("recordCount", botTableRecordRepo.countByBranchId(b.getId()));
        } catch (Exception e) {
            m.put("recordCount", 0);
        }
        return m;
    }

    // ── Request DTO ───────────────────────────────────────────────────────

    public static class BranchUpsertRequest {
        public String name;
        public String slug;       // opcional — si no viene, se genera desde name
        public String address;
        public String phone;
        public String timezone;
        public Boolean active;
    }
}
