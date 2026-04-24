package app.coincidir.api.botplatform.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * ApiIntegration — representa una API REST externa con la que el bot puede integrarse.
 *
 * Cada integración tiene:
 *   - Datos de conexión (baseUrl)
 *   - Autenticación (tipo + credenciales encriptadas)
 *   - Opcionalmente, una URL de OpenAPI/Swagger para auto-detectar endpoints
 *
 * Los endpoints individuales viven en ApiEndpoint (relación 1:N).
 */
@Entity
@Table(name = "api_integration")
@Data
public class ApiIntegration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nombre descriptivo (ej: "API de YES Travel", "Stripe Payments"). */
    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /** Descripción opcional para el admin. */
    @Column(name = "description", length = 500)
    private String description;

    /** URL base de la API (ej: "https://api.yes-traveluy.com"). SIN trailing slash. */
    @Column(name = "base_url", nullable = false, length = 500)
    private String baseUrl;

    /**
     * Tipo de autenticación. Valores aceptados:
     *   - "none": sin auth.
     *   - "api_key": header con nombre configurable (default: X-API-Key).
     *   - "bearer": header "Authorization: Bearer <token>".
     */
    @Column(name = "auth_type", nullable = false, length = 20)
    private String authType = "none";

    /**
     * Credencial encriptada (AES-256). Para api_key es el valor de la key;
     * para bearer es el JWT. Para none queda null.
     */
    @Column(name = "auth_credential_enc", columnDefinition = "TEXT")
    private String authCredentialEnc;

    /** Nombre del header para api_key (si aplica). Default: "X-API-Key". */
    @Column(name = "auth_header_name", length = 120)
    private String authHeaderName;

    /**
     * URL del documento OpenAPI/Swagger (ej: "/v3/api-docs"). Opcional.
     * Se usa para auto-detectar endpoints al presionar "Importar".
     */
    @Column(name = "openapi_url", length = 500)
    private String openapiUrl;

    /** Si es false, todos los endpoints de esta integración quedan deshabilitados. */
    @Column(name = "active", nullable = false)
    private Boolean active = true;

    /** Rate limit: llamadas máximas por minuto por esta integración. Default: 60. */
    @Column(name = "rate_limit_per_minute")
    private Integer rateLimitPerMinute;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (rateLimitPerMinute == null) rateLimitPerMinute = 60;
        if (authHeaderName == null && "api_key".equals(authType)) authHeaderName = "X-API-Key";
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
