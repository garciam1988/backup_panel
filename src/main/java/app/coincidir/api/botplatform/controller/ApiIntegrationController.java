package app.coincidir.api.botplatform.controller;

import app.coincidir.api.botplatform.domain.ApiCallLog;
import app.coincidir.api.botplatform.domain.ApiEndpoint;
import app.coincidir.api.botplatform.domain.ApiIntegration;
import app.coincidir.api.botplatform.repository.ApiCallLogRepository;
import app.coincidir.api.botplatform.repository.ApiEndpointRepository;
import app.coincidir.api.botplatform.repository.ApiIntegrationRepository;
import app.coincidir.api.botplatform.service.CryptoService;
import app.coincidir.api.botplatform.service.OpenApiImporter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * ApiIntegrationController — CRUD de integraciones con APIs REST externas.
 *
 * Endpoints:
 *   GET    /api/admin/api-integrations
 *   POST   /api/admin/api-integrations
 *   GET    /api/admin/api-integrations/{id}
 *   PUT    /api/admin/api-integrations/{id}
 *   DELETE /api/admin/api-integrations/{id}
 *
 *   POST   /api/admin/api-integrations/{id}/import-openapi
 *   GET    /api/admin/api-integrations/{id}/endpoints
 *   POST   /api/admin/api-integrations/{id}/endpoints
 *   PUT    /api/admin/api-integrations/endpoints/{endpointId}
 *   DELETE /api/admin/api-integrations/endpoints/{endpointId}
 *
 * Credenciales: van encriptadas (AES-GCM) via CryptoService. El GET nunca
 * devuelve la credencial en claro — devuelve un flag hasCredential.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/api-integrations")
@RequiredArgsConstructor
public class ApiIntegrationController {

    private final ApiIntegrationRepository integrationRepo;
    private final ApiEndpointRepository endpointRepo;
    private final ApiCallLogRepository logRepo;
    private final CryptoService crypto;
    private final OpenApiImporter openApiImporter;

    // ─────────────────────────────────────────────────────────────────
    // CRUD de integrations
    // ─────────────────────────────────────────────────────────────────

    @GetMapping
    @Transactional(readOnly = true)
    public List<IntegrationDto> list() {
        return integrationRepo.findAllByOrderByNameAsc().stream()
                .map(this::toDto).toList();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public IntegrationDto get(@PathVariable Long id) {
        return toDto(integrationRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    @PostMapping
    @Transactional
    public IntegrationDto create(@RequestBody IntegrationSaveRequest req) {
        validateReq(req);
        ApiIntegration e = new ApiIntegration();
        applyRequest(e, req);
        return toDto(integrationRepo.save(e));
    }

    @PutMapping("/{id}")
    @Transactional
    public IntegrationDto update(@PathVariable Long id, @RequestBody IntegrationSaveRequest req) {
        validateReq(req);
        ApiIntegration e = integrationRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        applyRequest(e, req);
        return toDto(integrationRepo.save(e));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public void delete(@PathVariable Long id) {
        endpointRepo.deleteByIntegrationId(id);
        integrationRepo.deleteById(id);
    }

    // ─────────────────────────────────────────────────────────────────
    // Import OpenAPI
    // ─────────────────────────────────────────────────────────────────

    @PostMapping("/{id}/import-openapi")
    @Transactional
    public ImportResult importOpenapi(@PathVariable Long id) {
        ApiIntegration integ = integrationRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (integ.getOpenapiUrl() == null || integ.getOpenapiUrl().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "openapiUrl no configurada");
        }

        List<OpenApiImporter.EndpointSpec> specs = openApiImporter.fetchAndParse(integ.getOpenapiUrl());
        List<ApiEndpoint> existing = endpointRepo.findByIntegrationIdOrderByTagAscPathAsc(id);

        ImportResult result = new ImportResult();
        result.created = 0;
        result.updated = 0;
        result.skipped = 0;

        for (OpenApiImporter.EndpointSpec spec : specs) {
            // Buscar si ya existe (por method+path)
            ApiEndpoint match = existing.stream()
                    .filter(e -> e.getMethod().equalsIgnoreCase(spec.method)
                              && e.getPath().equals(spec.path))
                    .findFirst()
                    .orElse(null);

            if (match == null) {
                ApiEndpoint e = new ApiEndpoint();
                e.setIntegrationId(id);
                e.setMethod(spec.method);
                e.setPath(spec.path);
                // toolName: si ya hay otro con ese nombre (de otra integration), le prefijo
                String tn = uniqueToolName(spec.toolName);
                e.setToolName(tn);
                e.setDescription(spec.description);
                e.setInputSchemaJson(spec.inputSchemaJson);
                e.setOperationId(spec.operationId);
                e.setTag(spec.tag);
                e.setSource("openapi");
                e.setActiveAsTool(false); // Admin elige uno por uno
                e.setAllowWrites(false);
                e.setRequireConfirmation(!spec.isReadOnly()); // writes por default piden confirmación
                endpointRepo.save(e);
                result.created++;
            } else {
                // Actualizar metadata sin pisar flags (activeAsTool, allowWrites, etc)
                match.setDescription(spec.description);
                match.setInputSchemaJson(spec.inputSchemaJson);
                match.setOperationId(spec.operationId);
                match.setTag(spec.tag);
                endpointRepo.save(match);
                result.updated++;
            }
        }
        result.total = specs.size();
        log.info("[ImportOpenAPI] integ {} — creados {}, actualizados {}", id, result.created, result.updated);
        return result;
    }

    private String uniqueToolName(String base) {
        if (base == null || base.isBlank()) base = "unnamed";
        String candidate = base;
        int n = 1;
        while (endpointRepo.findByToolName(candidate).isPresent()) {
            n++;
            candidate = base + "_" + n;
            if (n > 100) break; // safety
        }
        return candidate;
    }

    // ─────────────────────────────────────────────────────────────────
    // CRUD de endpoints de una integration
    // ─────────────────────────────────────────────────────────────────

    @GetMapping("/{id}/endpoints")
    @Transactional(readOnly = true)
    public List<EndpointDto> listEndpoints(@PathVariable Long id) {
        return endpointRepo.findByIntegrationIdOrderByTagAscPathAsc(id).stream()
                .map(this::toEndpointDto).toList();
    }

    @PostMapping("/{id}/endpoints")
    @Transactional
    public EndpointDto createEndpoint(@PathVariable Long id, @RequestBody EndpointSaveRequest req) {
        if (!integrationRepo.existsById(id))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "integration no existe");
        validateEndpointReq(req);
        ApiEndpoint e = new ApiEndpoint();
        e.setIntegrationId(id);
        e.setSource("manual");
        applyEndpointRequest(e, req);
        return toEndpointDto(endpointRepo.save(e));
    }

    @PutMapping("/endpoints/{endpointId}")
    @Transactional
    public EndpointDto updateEndpoint(@PathVariable Long endpointId, @RequestBody EndpointSaveRequest req) {
        ApiEndpoint e = endpointRepo.findById(endpointId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        validateEndpointReq(req);
        applyEndpointRequest(e, req);
        return toEndpointDto(endpointRepo.save(e));
    }

    @DeleteMapping("/endpoints/{endpointId}")
    @Transactional
    public void deleteEndpoint(@PathVariable Long endpointId) {
        endpointRepo.deleteById(endpointId);
    }

    // ─────────────────────────────────────────────────────────────────
    // Audit log
    // ─────────────────────────────────────────────────────────────────

    /**
     * Lista los logs de ejecución paginados.
     *   integrationId: filtro opcional (null = todas)
     *   ok:            filtro opcional (null = todos, true = solo exitosos, false = solo fallidos)
     *   page/size:     paginación (default page=0, size=50, cap 200)
     */
    @GetMapping("/call-logs")
    @Transactional(readOnly = true)
    public CallLogsResponse listCallLogs(
            @RequestParam(required = false) Long integrationId,
            @RequestParam(required = false) Boolean ok,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        if (size <= 0) size = 50;
        if (size > 200) size = 200;
        if (page < 0) page = 0;
        Pageable pageable = PageRequest.of(page, size);

        Page<ApiCallLog> p;
        if (integrationId != null && ok != null) {
            p = logRepo.findByIntegrationIdAndOkOrderByCalledAtDesc(integrationId, ok, pageable);
        } else if (integrationId != null) {
            p = logRepo.findByIntegrationIdOrderByCalledAtDesc(integrationId, pageable);
        } else if (ok != null) {
            p = logRepo.findByOkOrderByCalledAtDesc(ok, pageable);
        } else {
            p = logRepo.findAllByOrderByCalledAtDesc(pageable);
        }

        CallLogsResponse resp = new CallLogsResponse();
        resp.items = p.getContent().stream().map(this::toLogDto).toList();
        resp.total = p.getTotalElements();
        resp.page = page;
        resp.size = size;
        return resp;
    }

    private CallLogDto toLogDto(ApiCallLog l) {
        CallLogDto d = new CallLogDto();
        d.id = l.getId();
        d.integrationId = l.getIntegrationId();
        d.endpointId = l.getEndpointId();
        d.toolName = l.getToolName();
        d.method = l.getMethod();
        d.url = l.getUrl();
        d.argsJson = l.getArgsJson();
        d.httpStatus = l.getHttpStatus();
        d.ok = l.getOk();
        d.error = l.getError();
        d.responseExcerpt = l.getResponseExcerpt();
        d.durationMs = l.getDurationMs();
        d.calledAt = l.getCalledAt();
        return d;
    }

    // ─────────────────────────────────────────────────────────────────
    // Mapping y validaciones
    // ─────────────────────────────────────────────────────────────────

    private void validateReq(IntegrationSaveRequest r) {
        if (r == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body vacío");
        if (r.name == null || r.name.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name requerido");
        if (r.baseUrl == null || r.baseUrl.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "baseUrl requerida");
        if (!r.baseUrl.startsWith("http://") && !r.baseUrl.startsWith("https://"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "baseUrl debe arrancar con http:// o https://");
        if (r.authType == null) r.authType = "none";
        if (!List.of("none", "api_key", "bearer").contains(r.authType))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "authType inválido");
    }

    private void applyRequest(ApiIntegration e, IntegrationSaveRequest r) {
        e.setName(r.name.trim());
        e.setDescription(r.description);
        // Normalizar baseUrl: sin trailing slash
        String base = r.baseUrl.trim();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        e.setBaseUrl(base);
        e.setAuthType(r.authType);
        e.setAuthHeaderName(r.authHeaderName);
        e.setOpenapiUrl(r.openapiUrl);
        if (r.active != null) e.setActive(r.active);
        if (r.rateLimitPerMinute != null && r.rateLimitPerMinute > 0)
            e.setRateLimitPerMinute(r.rateLimitPerMinute);

        // Credencial: si viene un valor nuevo no vacío, lo encriptamos.
        // Si viene null, no tocamos (mantiene lo anterior).
        // Si viene explícitamente string vacío "", limpiamos.
        if (r.authCredentialPlain != null) {
            if (r.authCredentialPlain.isEmpty()) {
                e.setAuthCredentialEnc(null);
            } else {
                e.setAuthCredentialEnc(crypto.encrypt(r.authCredentialPlain));
            }
        }
    }

    private void validateEndpointReq(EndpointSaveRequest r) {
        if (r == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body vacío");
        if (r.method == null || r.path == null || r.toolName == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "method/path/toolName requeridos");
        if (!List.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(r.method.toUpperCase()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "method inválido");
    }

    private void applyEndpointRequest(ApiEndpoint e, EndpointSaveRequest r) {
        e.setMethod(r.method.toUpperCase());
        e.setPath(r.path);
        e.setToolName(r.toolName);
        e.setDescription(r.description);
        e.setInputSchemaJson(r.inputSchemaJson);
        if (r.activeAsTool != null) e.setActiveAsTool(r.activeAsTool);
        if (r.allowWrites != null) e.setAllowWrites(r.allowWrites);
        if (r.requireConfirmation != null) e.setRequireConfirmation(r.requireConfirmation);
        if (r.tag != null) e.setTag(r.tag);
    }

    private IntegrationDto toDto(ApiIntegration e) {
        IntegrationDto d = new IntegrationDto();
        d.id = e.getId();
        d.name = e.getName();
        d.description = e.getDescription();
        d.baseUrl = e.getBaseUrl();
        d.authType = e.getAuthType();
        d.authHeaderName = e.getAuthHeaderName();
        d.openapiUrl = e.getOpenapiUrl();
        d.active = e.getActive();
        d.rateLimitPerMinute = e.getRateLimitPerMinute();
        d.hasCredential = e.getAuthCredentialEnc() != null && !e.getAuthCredentialEnc().isBlank();
        d.createdAt = e.getCreatedAt();
        d.updatedAt = e.getUpdatedAt();
        // Contar endpoints rápido
        d.endpointCount = endpointRepo.findByIntegrationIdOrderByTagAscPathAsc(e.getId()).size();
        return d;
    }

    private EndpointDto toEndpointDto(ApiEndpoint e) {
        EndpointDto d = new EndpointDto();
        d.id = e.getId();
        d.integrationId = e.getIntegrationId();
        d.method = e.getMethod();
        d.path = e.getPath();
        d.toolName = e.getToolName();
        d.description = e.getDescription();
        d.inputSchemaJson = e.getInputSchemaJson();
        d.activeAsTool = e.getActiveAsTool();
        d.allowWrites = e.getAllowWrites();
        d.requireConfirmation = e.getRequireConfirmation();
        d.source = e.getSource();
        d.operationId = e.getOperationId();
        d.tag = e.getTag();
        d.createdAt = e.getCreatedAt();
        d.updatedAt = e.getUpdatedAt();
        return d;
    }

    // ─────────────────────────────────────────────────────────────────
    // DTOs
    // ─────────────────────────────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class IntegrationSaveRequest {
        public String name;
        public String description;
        public String baseUrl;
        public String authType;
        public String authHeaderName;
        public String authCredentialPlain; // NUNCA se retorna; se encripta al guardar
        public String openapiUrl;
        public Boolean active;
        public Integer rateLimitPerMinute;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class IntegrationDto {
        public Long id;
        public String name;
        public String description;
        public String baseUrl;
        public String authType;
        public String authHeaderName;
        public String openapiUrl;
        public Boolean active;
        public Integer rateLimitPerMinute;
        public Boolean hasCredential; // true si hay authCredentialEnc seteado
        public Integer endpointCount;
        public Instant createdAt;
        public Instant updatedAt;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EndpointSaveRequest {
        public String method;
        public String path;
        public String toolName;
        public String description;
        public String inputSchemaJson;
        public Boolean activeAsTool;
        public Boolean allowWrites;
        public Boolean requireConfirmation;
        public String tag;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EndpointDto {
        public Long id;
        public Long integrationId;
        public String method;
        public String path;
        public String toolName;
        public String description;
        public String inputSchemaJson;
        public Boolean activeAsTool;
        public Boolean allowWrites;
        public Boolean requireConfirmation;
        public String source;
        public String operationId;
        public String tag;
        public Instant createdAt;
        public Instant updatedAt;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ImportResult {
        public Integer total;
        public Integer created;
        public Integer updated;
        public Integer skipped;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CallLogsResponse {
        public List<CallLogDto> items;
        public Long total;
        public Integer page;
        public Integer size;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CallLogDto {
        public Long id;
        public Long integrationId;
        public Long endpointId;
        public String toolName;
        public String method;
        public String url;
        public String argsJson;
        public Integer httpStatus;
        public Boolean ok;
        public String error;
        public String responseExcerpt;
        public Long durationMs;
        public Instant calledAt;
    }
}
