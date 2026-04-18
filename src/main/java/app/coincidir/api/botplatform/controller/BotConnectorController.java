package app.coincidir.api.botplatform.controller;

import app.coincidir.api.botplatform.domain.BotConnector;
import app.coincidir.api.botplatform.domain.BotConnector.DbType;
import app.coincidir.api.botplatform.repository.BotConnectorRepository;
import app.coincidir.api.botplatform.service.DynamicDataSourceService;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    @GetMapping
    @Transactional(readOnly = true)
    public List<BotConnectorDto> list(@RequestParam(value = "all", defaultValue = "false") boolean all) {
        List<BotConnector> list = all
                ? repo.findAllByOrderByNameAsc()
                : repo.findByActiveTrueOrderByNameAsc();
        return list.stream().map(BotConnectorDto::fromEntity).toList();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<BotConnectorDto> getOne(@PathVariable Long id) {
        return repo.findById(id)
                .map(BotConnectorDto::fromEntity)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/db-types")
    public List<String> dbTypes() {
        return java.util.Arrays.stream(DbType.values()).map(Enum::name).toList();
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody BotConnectorDto dto) {
        String err = dto.validate();
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));
        if (repo.findByName(dto.name.trim()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Ya existe un conector con ese nombre"));
        }
        BotConnector e = new BotConnector();
        dto.applyTo(e);
        BotConnector saved = repo.save(e);
        log.info("bot_connector creado: id={}, name='{}', type={}", saved.getId(), saved.getName(), saved.getDbType());
        return ResponseEntity.status(HttpStatus.CREATED).body(BotConnectorDto.fromEntity(saved));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody BotConnectorDto dto) {
        Optional<BotConnector> opt = repo.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        String err = dto.validate();
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));
        BotConnector e = opt.get();
        dto.applyTo(e);
        BotConnector saved = repo.save(e);
        // Invalidar el pool cacheado — las credenciales pueden haber cambiado
        dataSourceService.invalidate(id);
        log.info("bot_connector actualizado: id={}", id);
        return ResponseEntity.ok(BotConnectorDto.fromEntity(saved));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!repo.existsById(id)) return ResponseEntity.notFound().build();
        repo.deleteById(id);
        dataSourceService.invalidate(id);
        log.info("bot_connector eliminado: id={}", id);
        return ResponseEntity.noContent().build();
    }

    /** Probar una conexión ya guardada. */
    @PostMapping("/{id}/test")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> testExisting(@PathVariable Long id) {
        return repo.findById(id).<ResponseEntity<Map<String, Object>>>map(conn -> {
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
    // DTO
    // ─────────────────────────────────────────────────────────────────────
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BotConnectorDto {
        public Long     id;
        public String   name;
        public String   description;
        public String   dbType;       // enum como string
        public String   host;
        public Integer  port;
        public String   databaseName;
        public String   username;
        public String   password;     // en respuestas de listado, vuelve null (ver fromEntity)
        public String   extraParams;
        public Boolean  active;
        public Instant  createdAt;
        public Instant  updatedAt;

        public static BotConnectorDto fromEntity(BotConnector e) {
            return fromEntity(e, false);
        }

        /** Si includePassword=false, el password se oculta (útil para listados). */
        public static BotConnectorDto fromEntity(BotConnector e, boolean includePassword) {
            BotConnectorDto d = new BotConnectorDto();
            d.id            = e.getId();
            d.name          = e.getName();
            d.description   = e.getDescription();
            d.dbType        = e.getDbType() != null ? e.getDbType().name() : null;
            d.host          = e.getHost();
            d.port          = e.getPort();
            d.databaseName  = e.getDatabaseName();
            d.username      = e.getUsername();
            d.password      = includePassword ? e.getPassword() : null;
            d.extraParams   = e.getExtraParams();
            d.active        = e.getActive();
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
