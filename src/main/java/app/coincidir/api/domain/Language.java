package app.coincidir.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Language — idiomas disponibles en el bot.
 *
 * El admin gestiona estos idiomas desde /admin (sección "Lenguajes").
 * Por defecto el sistema siembra 3 (es, en, pt-BR), pero se pueden agregar
 * cuantos quiera (it, fr, de, etc).
 *
 * - {@code code} es un BCP-47 reducido. Ejemplos: "es", "en", "pt-BR", "it".
 * - {@code voiceId} es el voice_id de ElevenLabs para usar al hablar en este idioma.
 *   Puede ser null para usar la voz default global del bot.
 * - {@code isDefault} marca el idioma por defecto. Solo uno puede ser default.
 * - {@code enabled} si es false, el idioma no aparece en el selector del bot.
 * - {@code isSystem} marca los idiomas seedeados (es/en/pt-BR) que no se pueden borrar.
 */
@Entity
@Table(name = "app_language", indexes = {
        @Index(name = "idx_app_language_code", columnList = "code", unique = true)
})
@Getter @Setter
public class Language {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Código BCP-47 reducido (ej: "es", "en", "pt-BR", "it"). */
    @Column(name = "code", length = 10, nullable = false, unique = true)
    private String code;

    /** Nombre en español del idioma (ej: "Inglés", "Portugués (Brasil)"). */
    @Column(name = "name", length = 60, nullable = false)
    private String name;

    /** Nombre nativo del idioma (ej: "English", "Português"). */
    @Column(name = "native_name", length = 60)
    private String nativeName;

    /** Emoji de bandera para mostrar en el selector. Ej: "🇺🇸". */
    @Column(name = "flag", length = 8)
    private String flag;

    /** Voice ID de ElevenLabs para TTS en este idioma. Null = usar voz default global. */
    @Column(name = "voice_id", length = 80)
    private String voiceId;

    /** Si está habilitado, aparece en el selector del bot. */
    @Column(name = "enabled", nullable = false)
    private Boolean enabled = Boolean.TRUE;

    /** Si es true, es el idioma por defecto (solo uno puede ser true). */
    @Column(name = "is_default", nullable = false)
    private Boolean isDefault = Boolean.FALSE;

    /** Si es true, es un idioma del sistema (no se puede borrar). */
    @Column(name = "is_system", nullable = false)
    private Boolean isSystem = Boolean.FALSE;

    /** Orden de aparición en el selector (menor = primero). */
    @Column(name = "display_order", nullable = false)
    private Integer displayOrder = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (enabled == null) enabled = Boolean.TRUE;
        if (isDefault == null) isDefault = Boolean.FALSE;
        if (isSystem == null) isSystem = Boolean.FALSE;
        if (displayOrder == null) displayOrder = 0;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
}
