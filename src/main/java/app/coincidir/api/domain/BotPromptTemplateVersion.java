package app.coincidir.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * BotPromptTemplateVersion — snapshot histórico de una plantilla de prompt.
 *
 * <h3>Por qué</h3>
 * El admin puede iterar mucho sobre un prompt (afinarlo, probar variantes,
 * arrepentirse de un cambio). Antes, cada update sobrescribía el promptText
 * y perdíamos el original — si en producción el bot empezaba a responder
 * mal después de un cambio, no había forma de volver atrás. Esta entity
 * guarda un snapshot ANTES de cada update, y desde el panel se puede
 * navegar el historial y restaurar cualquier versión anterior.
 *
 * <h3>Modelo de versionado</h3>
 * <ul>
 *   <li>Cada vez que se actualiza un {@link BotPromptTemplate}, el state
 *       <strong>previo</strong> se guarda como una nueva fila acá.</li>
 *   <li>Las versiones son inmutables — solo se crean, nunca se editan.
 *       Si necesitás "modificar una versión vieja", restauras esa versión
 *       como current y editás desde ahí (que a su vez crea otra entry).</li>
 *   <li>{@code versionNumber} es secuencial dentro del template (1, 2, 3...)
 *       y se calcula como {@code MAX(versionNumber) + 1} al crear.</li>
 *   <li>{@code reason} se setea automáticamente: "edit" para cambios normales,
 *       "restore" cuando se restauró desde el historial, "ai-generate" cuando
 *       el generador con IA pisó el texto.</li>
 * </ul>
 *
 * <h3>Borrado en cascada</h3>
 * No usamos {@code ON DELETE CASCADE} a nivel BD para mantener consistencia
 * con el resto del modelo (que evita cascades implícitas). El controller
 * borra las versiones explícitamente cuando se borra el template.
 */
@Entity
@Table(name = "bot_prompt_template_version", indexes = {
    @Index(name = "idx_bptv_template_id", columnList = "template_id"),
    @Index(name = "idx_bptv_created_at", columnList = "created_at"),
    @Index(name = "uk_bptv_template_version", columnList = "template_id,version_number", unique = true)
})
@Getter @Setter
public class BotPromptTemplateVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * FK al template padre. No usamos {@code @ManyToOne} con JPA porque no
     * necesitamos cargar el template cuando trabajamos con sus versiones — y
     * mantener una relación lazy bidireccional complica el delete cascade.
     * Acá guardamos solo el ID y el template se resuelve por separado si hace
     * falta.
     */
    @Column(name = "template_id", nullable = false)
    private Long templateId;

    /** Versión secuencial dentro del template. 1, 2, 3... */
    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    /**
     * Snapshot del nombre del template al momento de esta versión. Lo guardamos
     * porque el nombre puede haber cambiado entre versiones — y queremos que
     * el historial muestre exactamente lo que era cuando ocurrió ese cambio.
     */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** Snapshot de la descripción. */
    @Column(name = "description", length = 300)
    private String description;

    /** Snapshot del prompt completo en este momento de la historia. */
    @Column(name = "prompt_text", nullable = false, columnDefinition = "LONGTEXT")
    private String promptText;

    /** Snapshot del flag active. */
    @Column(name = "active", nullable = false)
    private Boolean active = true;

    /**
     * Razón del cambio. Valores típicos:
     * <ul>
     *   <li>{@code "edit"} — edición manual desde el panel</li>
     *   <li>{@code "restore"} — restauración desde una versión vieja</li>
     *   <li>{@code "ai-generate"} — el generador con IA pisó el texto</li>
     *   <li>{@code "initial"} — versión inicial (creada por la migración
     *       V106 para templates que existían antes del módulo)</li>
     * </ul>
     */
    @Column(name = "reason", length = 20)
    private String reason;

    /**
     * Username del operador que originó este cambio. Puede ser null si la
     * versión fue creada por la migración inicial o por código sin contexto
     * de auth (raro, pero defensivo). El frontend cae al "-" si es null.
     */
    @Column(name = "created_by", length = 80)
    private String createdBy;

    /** Cuándo se creó esta versión (= cuándo se cambió el template). */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (reason == null) reason = "edit";
        if (active == null) active = true;
    }
}
