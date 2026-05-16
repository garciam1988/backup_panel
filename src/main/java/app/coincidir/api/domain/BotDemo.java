package app.coincidir.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/**
 * BotDemo — snapshot completo de la configuración del bot pensado como
 * "plantilla" (backup) para alternar rápido entre escenarios.
 *
 * Incluye TODO lo que se considera "configuración del bot":
 *   - config base del BotConfig (serializado como JSON)
 *   - catálogos Excel (solo metadata: nombres + active. Las filas NO se clonan)
 *   - conectores de BD
 *   - tools (SQL)
 *   - prompt templates (plantillas de prompt)
 *   - integraciones API (REST externas) + sus endpoints
 *   - bot tables (tablas custom — solo definición, NO registros)
 *   - idiomas configurados + voces
 *   - textos del frontend (ui-texts) + traducciones
 *
 * NO se guardan (decisión consciente):
 *   - usuarios y roles (sensible)
 *   - logs de conversación, alertas de fraude, base de clientes (operativo)
 *   - imágenes/videos del menú (binarios pesados)
 *   - registros de bot tables (datos del cliente, podrían pisarse)
 *   - métricas de uso / billing
 *
 * Al "aplicar" un backup, el frontend orquesta el reemplazo módulo por módulo
 * (este backend es "dumb storage" — guarda y devuelve JSON blobs).
 */
@Entity
@Table(name = "bot_demo", indexes = {
        @Index(name = "idx_bot_demo_name", columnList = "name"),
        @Index(name = "idx_bot_demo_updated_at", columnList = "updated_at")
})
@Getter @Setter
public class BotDemo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nombre descriptivo de la demo (ej: "El Parmegiano", "Ferretería"). */
    @Column(name = "name", length = 150, nullable = false, unique = true)
    private String name;

    /** Descripción opcional para diferenciar entre demos similares. */
    @Column(name = "description", length = 500)
    private String description;

    /** Snapshot completo del BotConfig como JSON. */
    @Column(name = "config_json", columnDefinition = "LONGTEXT", nullable = false)
    private String configJson;

    /** Catálogos Excel serializados (incluye filas). */
    @Column(name = "catalogs_json", columnDefinition = "LONGTEXT")
    private String catalogsJson;

    /** Conectores de BDs serializados. */
    @Column(name = "connectors_json", columnDefinition = "LONGTEXT")
    private String connectorsJson;

    /** Tools del bot serializadas. */
    @Column(name = "tools_json", columnDefinition = "LONGTEXT")
    private String toolsJson;

    /** Plantillas de prompt (bot_prompt_template) serializadas. */
    @Column(name = "prompt_templates_json", columnDefinition = "LONGTEXT")
    private String promptTemplatesJson;

    /** Integraciones API (REST externas) + endpoints, serializadas. */
    @Column(name = "api_integrations_json", columnDefinition = "LONGTEXT")
    private String apiIntegrationsJson;

    /** Bot Tables (definición de tablas custom, sin registros) serializadas. */
    @Column(name = "bot_tables_json", columnDefinition = "LONGTEXT")
    private String botTablesJson;

    /** Idiomas configurados (bot_language) + voces, serializados. */
    @Column(name = "languages_json", columnDefinition = "LONGTEXT")
    private String languagesJson;

    /** Textos del frontend (ui_text + traducciones) serializados. */
    @Column(name = "ui_texts_json", columnDefinition = "LONGTEXT")
    private String uiTextsJson;

    /** Última vez que se aplicó esta demo (para ordenar y mostrar en la UI). */
    @Column(name = "last_applied_at")
    private Instant lastAppliedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
