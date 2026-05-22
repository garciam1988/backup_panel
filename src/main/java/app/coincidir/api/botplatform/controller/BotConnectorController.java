package app.coincidir.api.botplatform.controller;

import app.coincidir.api.botplatform.domain.BotConnector;
import app.coincidir.api.botplatform.domain.BotConnector.DbType;
import app.coincidir.api.botplatform.repository.BotConnectorRepository;
import app.coincidir.api.botplatform.service.DynamicDataSourceService;
import app.coincidir.api.tenancy.access.BranchAccessGuard;
import app.coincidir.api.tenancy.context.BranchContext;
import app.coincidir.api.tenancy.context.BranchContext.BranchScope;
import app.coincidir.api.tenancy.domain.Branch;
import app.coincidir.api.tenancy.repository.BranchRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * BotConnectorController — CRUD de conectores a BDs externas.
 *
 * Endpoints (bajo /api/admin/, requieren JWT):
 *   GET    /api/admin/bot-connectors          → listado (all=true incluye inactivos)
 *   GET    /api/admin/bot-connectors/{id}     → uno
 *   POST   /api/admin/bot-connectors          → crear
 *   PUT    /api/admin/bot-connectors/{id}     → editar
 *   DELETE /api/admin/bot-connectors/{id}     → borrar
 *   POST   /api/admin/bot-connectors/{id}/test → probar conexión
 *   POST   /api/admin/bot-connectors/test      → probar antes de guardar (sin persistir)
 *   GET    /api/admin/bot-connectors/db-types → listar tipos soportados
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/bot-connectors")
@RequiredArgsConstructor
public class BotConnectorController {

    private final BotConnectorRepository repo;
    private final DynamicDataSourceService dataSourceService;
    private final app.coincidir.api.botplatform.service.SchemaIntrospectionService schemaService;
    private final BranchAccessGuard branchAccess;
    private final BranchRepository branchRepo;

    @GetMapping
    @Transactional(readOnly = true)
    public List<BotConnectorDto> list(@RequestParam(value = "all", defaultValue = "false") boolean all,
                                       Authentication auth) {
        String username = auth != null ? auth.getName() : null;
        List<Long> readableBranchIds = branchAccess.readableBranchIdsOrNullForUnrestricted(username);

        List<BotConnector> list;
        if (readableBranchIds == null) {
            // DIOS/ADMIN
            list = all
                    ? repo.findAllByOrderByNameAsc()
                    : repo.findByActiveTrueOrderByNameAsc();
        } else if (readableBranchIds.isEmpty()) {
            // User sin branches asignadas — solo globales
            list = all
                    ? repo.findGlobalsOrderByNameAsc()
                    : repo.findActiveGlobalsOrderByNameAsc();
        } else {
            list = all
                    ? repo.findVisibleForBranchesOrderByNameAsc(readableBranchIds)
                    : repo.findActiveVisibleForBranchesOrderByNameAsc(readableBranchIds);
        }

        Map<Long, String> branchNames = loadBranchNames(list);
        return list.stream().map(c -> BotConnectorDto.fromEntity(c, false, branchNames)).toList();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<BotConnectorDto> getOne(@PathVariable Long id, Authentication auth) {
        String username = auth != null ? auth.getName() : null;
        return repo.findById(id)
                .map(c -> {
                    branchAccess.requireRead(username, c.getBranchId());
                    Map<Long, String> bn = loadBranchNames(List.of(c));
                    return BotConnectorDto.fromEntity(c, false, bn);
                })
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/db-types")
    public List<String> dbTypes() {
        return java.util.Arrays.stream(DbType.values()).map(Enum::name).toList();
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody BotConnectorDto dto, Authentication auth) {
        String err = dto.validate();
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));

        String username = auth != null ? auth.getName() : null;
        BranchScope scope = BranchContext.current();
        Long scopeBranchId = (scope != null) ? scope.getBranchId() : null;
        Long finalBranchId = branchAccess.resolveBranchForCreate(username, dto.branchId, scopeBranchId);
        branchAccess.requireWrite(username, finalBranchId);

        // Lookup por (name, branchId) — la constraint unique es compuesta tras Bloque 1.
        // PERO: si el caller crea uno NO-global (branchId != null) y ya existe un
        // GLOBAL con el mismo nombre, también es conflicto: el bot vería dos
        // connectors con el mismo nombre cuando esté scopeado a esa branch
        // (porque ve los de su branch + los globales), y eso rompe la generación
        // de tools (nombres duplicados). Bloqueamos ese caso explícitamente.
        if (repo.findByNameAndBranchId(dto.name.trim(), finalBranchId).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", finalBranchId == null
                            ? "Ya existe un conector global con ese nombre"
                            : "Ya existe un conector con ese nombre en esta sucursal"));
        }
        if (finalBranchId != null && repo.findByNameAndBranchId(dto.name.trim(), null).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "Ya existe un conector GLOBAL con ese nombre. Elegí otro nombre o pedile a un administrador que renombre/borre el global."));
        }
        BotConnector e = new BotConnector();
        dto.applyTo(e);
        e.setBranchId(finalBranchId);
        BotConnector saved = repo.save(e);
        log.info("bot_connector creado: id={}, name='{}', type={}, branch={}",
                saved.getId(), saved.getName(), saved.getDbType(), saved.getBranchId());
        Map<Long, String> bn = loadBranchNames(List.of(saved));
        return ResponseEntity.status(HttpStatus.CREATED).body(BotConnectorDto.fromEntity(saved, false, bn));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody BotConnectorDto dto, Authentication auth) {
        Optional<BotConnector> opt = repo.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        String err = dto.validate();
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));
        BotConnector e = opt.get();

        String username = auth != null ? auth.getName() : null;
        branchAccess.requireWrite(username, e.getBranchId());

        // Capturamos el glossary previo ANTES del applyTo para detectar
        // si cambió y, en ese caso, re-generar el llmSummary cacheado.
        String oldGlossary = e.getBusinessGlossary();
        dto.applyTo(e);
        // Nota: branchId NO se actualiza desde update — los connectors quedan
        // en su sucursal de creación. Mover entre branches sería un endpoint
        // dedicado (no implementado en Bloque 2).
        BotConnector saved = repo.save(e);
        // Invalidar el pool cacheado — las credenciales pueden haber cambiado
        dataSourceService.invalidate(id);
        // Si cambió el glossary, regeneramos el llmSummary del cache para
        // que el bot vea el cambio sin tener que re-escanear toda la BD.
        if (!java.util.Objects.equals(oldGlossary, saved.getBusinessGlossary())) {
            try {
                schemaService.regenerateLlmSummary(id);
                log.info("bot_connector id={} — llmSummary regenerado por cambio de glossary", id);
            } catch (Exception ex) {
                log.warn("No pude regenerar llmSummary para connector {}: {}", id, ex.getMessage());
            }
        }
        log.info("bot_connector actualizado: id={}", id);
        Map<Long, String> bn = loadBranchNames(List.of(saved));
        return ResponseEntity.ok(BotConnectorDto.fromEntity(saved, false, bn));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable Long id, Authentication auth) {
        Optional<BotConnector> opt = repo.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        String username = auth != null ? auth.getName() : null;
        branchAccess.requireWrite(username, opt.get().getBranchId());

        repo.deleteById(id);
        dataSourceService.invalidate(id);
        log.info("bot_connector eliminado: id={}", id);
        return ResponseEntity.noContent().build();
    }

    /** Probar una conexión ya guardada. */
    @PostMapping("/{id}/test")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> testExisting(@PathVariable Long id, Authentication auth) {
        String username = auth != null ? auth.getName() : null;
        return repo.findById(id).<ResponseEntity<Map<String, Object>>>map(conn -> {
            // Test es read-only pero implica intentar conectarse — pedimos read.
            branchAccess.requireRead(username, conn.getBranchId());
            String err = dataSourceService.testConnection(conn);
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            if (err == null) {
                body.put("ok", true);
                body.put("message", "Conexión exitosa");
            } else {
                body.put("ok", false);
                body.put("error", err);
            }
            return ResponseEntity.ok(body);
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Probar una conexión ANTES de guardarla (útil en el form). */
    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> testAdHoc(@RequestBody BotConnectorDto dto) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        String vErr = dto.validate();
        if (vErr != null) {
            body.put("ok", false);
            body.put("error", vErr);
            return ResponseEntity.badRequest().body(body);
        }
        BotConnector tmp = new BotConnector();
        dto.applyTo(tmp);

        // ────────────────────────────────────────────────────────────────────
        // FIX: cuando se prueba un conector que YA EXISTE (form de edición),
        // la UI no manda la password (queda enmascarada y el usuario no la
        // re-tipea). El DTO llega con password=null y el BotConnector temporal
        // queda intentando conectarse sin password.
        //
        // Síntoma observado: en MySQL, intentar conectarse sin password contra
        // un server que sí la requiere produce un timeout (el server corta el
        // handshake en silencio) — el usuario veía "timeout 30s" cuando en
        // realidad el problema era credenciales vacías.
        //
        // Solución: si el DTO trae id y la password está vacía/null, traemos
        // la password real desde la BD y la usamos en el test. Resto de
        // campos (host, port, user, etc.) se respetan del DTO porque el
        // usuario puede estar probando cambios sin haber guardado.
        // ────────────────────────────────────────────────────────────────────
        if (dto.id != null && (tmp.getPassword() == null || tmp.getPassword().isBlank())) {
            repo.findById(dto.id).ifPresent(saved -> {
                if (saved.getPassword() != null && !saved.getPassword().isBlank()) {
                    tmp.setPassword(saved.getPassword());
                }
            });
        }

        String err = dataSourceService.testConnection(tmp);
        if (err == null) {
            body.put("ok", true);
            body.put("message", "Conexión exitosa");
        } else {
            body.put("ok", false);
            body.put("error", err);
        }
        return ResponseEntity.ok(body);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    private Map<Long, String> loadBranchNames(Collection<BotConnector> connectors) {
        Set<Long> branchIds = new HashSet<>();
        for (BotConnector c : connectors) {
            if (c.getBranchId() != null) branchIds.add(c.getBranchId());
        }
        if (branchIds.isEmpty()) return Map.of();
        Map<Long, String> out = new HashMap<>();
        for (Branch b : branchRepo.findAllById(branchIds)) {
            out.put(b.getId(), b.getName());
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────
    // DTO
    // ─────────────────────────────────────────────────────────────────────
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BotConnectorDto {
        public Long     id;
        public String   name;
        public String   description;
        /** Sucursal a la que pertenece. Null = global. */
        public Long     branchId;
        public String   branchName;
        public String   dbType;       // enum como string
        public String   host;
        public Integer  port;
        public String   databaseName;
        public String   username;
        public String   password;     // en respuestas de listado, vuelve null (ver fromEntity)
        public String   extraParams;
        public Boolean  active;
        // Fase 2 — SQL libre opt-in por conector.
        public Boolean  sqlExecEnabled;
        public Integer  sqlExecMaxRows;
        public Integer  sqlExecTimeoutSec;
        public Integer  sqlExecMaxBytes;
        /** CSV de tablas permitidas en minúsculas. null/vacío = todas. */
        public String   sqlExecTableWhitelist;
        /** Rate limit por minuto (global del conector). null/0 = sin límite. */
        public Integer  sqlExecRateLimitPerMinute;
        /** Rate limit por día (global del conector). null/0 = sin límite. */
        public Integer  sqlExecRateLimitPerDay;
        /** Rate limit por sessionId por minuto. null/0 = sin límite. */
        public Integer  sqlExecRateLimitPerSessionMinute;
        /** Glosario de negocio en lenguaje natural. null/blank = sin glosario. */
        public String   businessGlossary;
        public Instant  createdAt;
        public Instant  updatedAt;

        public static BotConnectorDto fromEntity(BotConnector e) {
            return fromEntity(e, false, Map.of());
        }

        /** Si includePassword=false, el password se oculta (útil para listados). */
        public static BotConnectorDto fromEntity(BotConnector e, boolean includePassword) {
            return fromEntity(e, includePassword, Map.of());
        }

        public static BotConnectorDto fromEntity(BotConnector e, boolean includePassword,
                                                  Map<Long, String> branchNames) {
            BotConnectorDto d = new BotConnectorDto();
            d.id            = e.getId();
            d.name          = e.getName();
            d.description   = e.getDescription();
            d.branchId      = e.getBranchId();
            d.branchName    = e.getBranchId() != null ? branchNames.get(e.getBranchId()) : null;
            d.dbType        = e.getDbType() != null ? e.getDbType().name() : null;
            d.host          = e.getHost();
            d.port          = e.getPort();
            d.databaseName  = e.getDatabaseName();
            d.username      = e.getUsername();
            d.password      = includePassword ? e.getPassword() : null;
            d.extraParams   = e.getExtraParams();
            d.active        = e.getActive();
            d.sqlExecEnabled    = e.getSqlExecEnabled();
            d.sqlExecMaxRows    = e.getSqlExecMaxRows();
            d.sqlExecTimeoutSec = e.getSqlExecTimeoutSec();
            d.sqlExecMaxBytes   = e.getSqlExecMaxBytes();
            d.sqlExecTableWhitelist = e.getSqlExecTableWhitelist();
            d.sqlExecRateLimitPerMinute        = e.getSqlExecRateLimitPerMinute();
            d.sqlExecRateLimitPerDay           = e.getSqlExecRateLimitPerDay();
            d.sqlExecRateLimitPerSessionMinute = e.getSqlExecRateLimitPerSessionMinute();
            d.businessGlossary = e.getBusinessGlossary();
            d.createdAt     = e.getCreatedAt();
            d.updatedAt     = e.getUpdatedAt();
            return d;
        }

        public void applyTo(BotConnector e) {
            if (name != null)         e.setName(name.trim());
            if (description != null)  e.setDescription(description);
            if (dbType != null)       e.setDbType(DbType.valueOf(dbType.toUpperCase()));
            if (host != null)         e.setHost(host.trim());
            if (port != null)         e.setPort(port);
            if (databaseName != null) e.setDatabaseName(databaseName.trim());
            if (username != null)     e.setUsername(username.trim());
            if (password != null)     e.setPassword(password);
            if (extraParams != null)  e.setExtraParams(extraParams);
            if (active != null)       e.setActive(active);
            if (sqlExecEnabled != null)    e.setSqlExecEnabled(sqlExecEnabled);
            if (sqlExecMaxRows != null)    e.setSqlExecMaxRows(sqlExecMaxRows);
            if (sqlExecTimeoutSec != null) e.setSqlExecTimeoutSec(sqlExecTimeoutSec);
            if (sqlExecMaxBytes != null)   e.setSqlExecMaxBytes(sqlExecMaxBytes);
            if (sqlExecTableWhitelist != null) {
                // Normalizar: minúsculas, sin espacios extra, sin duplicados.
                // Si queda vacío después de normalizar, guardamos null (no "").
                String csv = sqlExecTableWhitelist;
                java.util.LinkedHashSet<String> uniq = new java.util.LinkedHashSet<>();
                for (String s : csv.split(",")) {
                    String t = s.trim().toLowerCase(java.util.Locale.ROOT);
                    if (!t.isEmpty()) uniq.add(t);
                }
                e.setSqlExecTableWhitelist(uniq.isEmpty() ? null : String.join(",", uniq));
            }
            // Rate limits: 0 → null (= sin límite). El frontend manda 0 cuando
            // el admin destildó el control, queremos guardarlo como null para
            // que la lógica del servicio no consulte la BD innecesariamente.
            if (sqlExecRateLimitPerMinute != null) {
                e.setSqlExecRateLimitPerMinute(
                        sqlExecRateLimitPerMinute <= 0 ? null : sqlExecRateLimitPerMinute);
            }
            if (sqlExecRateLimitPerDay != null) {
                e.setSqlExecRateLimitPerDay(
                        sqlExecRateLimitPerDay <= 0 ? null : sqlExecRateLimitPerDay);
            }
            if (sqlExecRateLimitPerSessionMinute != null) {
                e.setSqlExecRateLimitPerSessionMinute(
                        sqlExecRateLimitPerSessionMinute <= 0 ? null : sqlExecRateLimitPerSessionMinute);
            }
            if (businessGlossary != null) {
                // Trim y blank → null para consistencia (lo mismo que con
                // los otros campos opcionales).
                String g = businessGlossary.trim();
                e.setBusinessGlossary(g.isEmpty() ? null : g);
            }
        }

        /** Devuelve null si el DTO es válido para create/update, o un mensaje de error. */
        public String validate() {
            if (name == null || name.isBlank()) return "name es obligatorio";
            if (dbType == null || dbType.isBlank()) return "dbType es obligatorio";
            try { DbType.valueOf(dbType.toUpperCase()); }
            catch (Exception ex) { return "dbType inválido: " + dbType + ". Válidos: MYSQL, POSTGRES, SQLSERVER, ORACLE, MARIADB"; }
            if (host == null || host.isBlank()) return "host es obligatorio";
            if (port == null || port <= 0 || port > 65535) return "port inválido";
            if (databaseName == null || databaseName.isBlank()) return "databaseName es obligatorio";
            if (username == null || username.isBlank()) return "username es obligatorio";
            return null;
        }
    }
}
