package app.coincidir.api.botplatform.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * ApiEndpoint — un endpoint individual de una ApiIntegration.
 *
 * Puede haber sido:
 *   - Auto-detectado (importado desde OpenAPI)
 *   - Creado manualmente por el admin
 *
 * Se convierte en "tool" de Claude cuando activeAsTool=true. Cuando el bot
 * decide invocarlo, el ApiCallExecutor arma la request y la ejecuta.
 */
@Entity
@Table(name = "api_endpoint", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"integration_id", "method", "path"})
})
@Data
public class ApiEndpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "integration_id", nullable = false)
    private Long integrationId;

    /**
     * Método HTTP: GET, POST, PUT, PATCH, DELETE.
     * Los writes (POST/PUT/PATCH/DELETE) solo se permiten si allowWrites=true.
     */
    @Column(name = "method", nullable = false, length = 10)
    private String method;

    /** Path relativo a baseUrl de la integración (ej: "/products/{id}"). */
    @Column(name = "path", nullable = false, length = 500)
    private String path;

    /**
     * Nombre único que Claude ve como tool (ej: "search_products").
     * Auto-generado desde OpenAPI operationId si existe, o del path.
     * Debe ser snake_case y único por bot.
     */
    @Column(name = "tool_name", nullable = false, length = 100)
    private String toolName;

    /** Descripción que Claude lee para decidir cuándo usar la tool. */
    @Column(name = "description", length = 1000)
    private String description;

    /**
     * JSON Schema de los parámetros que Claude debe proveer al invocar.
     * Incluye path params, query params y body (si hay).
     * Formato estándar: { "type": "object", "properties": {...}, "required": [...] }
     */
    @Lob
    @Column(name = "input_schema_json", columnDefinition = "TEXT")
    private String inputSchemaJson;

    /** Si es true, está activo como tool del bot. */
    @Column(name = "active_as_tool", nullable = false)
    private Boolean activeAsTool = false;

    /**
     * Si es true, el bot puede ejecutarlo aunque sea método de escritura
     * (POST/PUT/PATCH/DELETE). Default false = solo reads seguros.
     */
    @Column(name = "allow_writes", nullable = false)
    private Boolean allowWrites = false;

    /**
     * Si es true, requiere confirmación humana (un botón en el chat) antes
     * de ejecutarse. Recomendado para cualquier write crítico.
     */
    @Column(name = "require_confirmation", nullable = false)
    private Boolean requireConfirmation = false;

    /** "openapi" | "manual" — origen del endpoint. */
    @Column(name = "source", nullable = false, length = 20)
    private String source = "manual";

    /** operationId del OpenAPI (si vino de ahí). */
    @Column(name = "operation_id", length = 200)
    private String operationId;

    /** Tag del OpenAPI (para agrupar en la UI). */
    @Column(name = "tag", length = 100)
    private String tag;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /** Helper: true si es método de lectura seguro. */
    public boolean isReadOnly() {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
    }
}
