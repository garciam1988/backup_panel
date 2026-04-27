package app.coincidir.api.coinbot;

import app.coincidir.api.domain.MenuVideo;
import app.coincidir.api.repository.MenuVideoRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * MenuVideoController — endpoint admin para subir/listar/borrar el video
 * ambiental del menú digital.
 *
 *   GET    /api/admin/menu-videos
 *   POST   /api/admin/menu-videos                (multipart, máx 50 MB)
 *   DELETE /api/admin/menu-videos/{id}
 *
 * El endpoint público para servir el binario en sí vive bajo
 * /api/public/menu-videos/{id}/raw (en PublicMenuVideoController) — necesario
 * porque el tag <video src="..."> no puede mandar Authorization header.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/menu-videos")
@RequiredArgsConstructor
public class MenuVideoController {

    private final MenuVideoRepository repo;

    private static final long MAX_SIZE = 50L * 1024 * 1024; // 50 MB por video
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "video/mp4", "video/quicktime", "video/webm"
    );

    @GetMapping
    @Transactional(readOnly = true)
    public List<MenuVideoDto> list() {
        return repo.findByActiveTrueOrderByCreatedAtDesc().stream()
                .map(MenuVideoDto::from)
                .toList();
    }

    @PostMapping(consumes = "multipart/form-data")
    @Transactional
    public MenuVideoDto upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "name", required = false) String name
    ) {
        if (file == null || file.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Archivo vacío");
        if (file.getSize() > MAX_SIZE)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "El video supera los 50MB permitidos. Comprimilo o usá una URL externa.");
        String ct = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (!ALLOWED_TYPES.contains(ct))
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Formato no permitido. Usá MP4, MOV o WebM.");

        // Política de un solo video activo: cuando se sube uno nuevo, marcamos
        // los anteriores como inactivos (no los borramos por si el admin
        // referenciaba uno viejo en el menu_config_json y lo quiere recuperar).
        for (MenuVideo old : repo.findByActiveTrueOrderByCreatedAtDesc()) {
            old.setActive(false);
            repo.save(old);
        }

        MenuVideo v = new MenuVideo();
        v.setName(name != null && !name.isBlank() ? name : file.getOriginalFilename());
        v.setContentType(ct);
        v.setSizeBytes(file.getSize());
        try { v.setData(file.getBytes()); }
        catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "No se pudo leer el archivo: " + e.getMessage());
        }
        v = repo.save(v);
        return MenuVideoDto.from(v);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public void delete(@PathVariable Long id) {
        repo.deleteById(id);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class MenuVideoDto {
        public Long id;
        public String name;
        public String contentType;
        public Long sizeBytes;
        public Boolean active;
        public Instant createdAt;
        public String rawUrl;

        public static MenuVideoDto from(MenuVideo v) {
            MenuVideoDto d = new MenuVideoDto();
            d.id = v.getId();
            d.name = v.getName();
            d.contentType = v.getContentType();
            d.sizeBytes = v.getSizeBytes();
            d.active = v.getActive();
            d.createdAt = v.getCreatedAt();
            d.rawUrl = "/api/public/menu-videos/" + v.getId() + "/raw";
            return d;
        }
    }
}
