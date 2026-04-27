package app.coincidir.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/**
 * MenuVideo — video del menú digital. El admin sube uno cuando quiere
 * que se muestre corriendo a la derecha del chat (en desktop) mientras
 * el cliente conversa con el bot.
 *
 * El binario se guarda como LONGBLOB (hasta 4 GB teóricos, en la práctica
 * cap a ~50 MB por video). Para una parrilla con un loop ambiental de 20-40
 * segundos en MP4 H.264 con bitrate medio, alcanza con 5-15 MB.
 *
 * Si el admin pasó una URL externa (YouTube/Vimeo), no se sube nada acá
 * — la URL se guarda en menu_config_json del BotConfig directamente.
 *
 * Solo guardamos UN video activo a la vez (el campo videoFileId del menu_config
 * apunta a este). Cuando se sube uno nuevo, el anterior se borra.
 */
@Entity
@Table(name = "menu_video")
@Getter @Setter
public class MenuVideo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", length = 200)
    private String name;

    @Column(name = "content_type", length = 80)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Lob
    @Column(name = "data", columnDefinition = "LONGBLOB", nullable = false)
    private byte[] data;

    @Column(name = "active", nullable = false)
    private Boolean active = Boolean.TRUE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (active == null) active = Boolean.TRUE;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
}
