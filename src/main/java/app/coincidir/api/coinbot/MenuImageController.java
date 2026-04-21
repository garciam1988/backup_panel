package app.coincidir.api.coinbot;

import app.coincidir.api.domain.MenuImage;
import app.coincidir.api.repository.MenuImageRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * MenuImageController — galería de imágenes del menú digital.
 *
 * CRUD bajo /api/admin/menu-images (requiere ADMIN).
 * El endpoint /raw/{id} sirve el binario como image/* para usar directo en
 * <img src=""/>. El GET normal devuelve metadata + data URL base64 (pequeñas)
 * para listados. El PUT /{id}/meta permite cambiar rol/nombre/sortOrder
 * sin resubir.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/menu-images")
@RequiredArgsConstructor
public class MenuImageController {

    private final MenuImageRepository repo;

    private static final long MAX_SIZE = 2L * 1024 * 1024; // 2 MB por imagen
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "image/jpeg", "image/jpg", "image/png", "image/webp", "image/gif"
    );

    // ─────────────────────────────────────────────────────────────────────
    @GetMapping
    @Transactional(readOnly = true)
    public List<MenuImageDto> list(@RequestParam(defaultValue = "false") boolean includeData) {
        return repo.findAllByOrderByIdDesc().stream()
                .map(img -> MenuImageDto.from(img, includeData))
                .toList();
    }

    // ─────────────────────────────────────────────────────────────────────
    // NOTA: el endpoint /{id}/raw vive en PublicMenuImageController bajo
    // /api/public/menu-images/{id}/raw — es el path usado por <img src="...">
    // que no puede mandar Authorization header. Al estar bajo /api/public/**
    // cae en un permitAll robusto que no depende del orden de matchers.
    // ─────────────────────────────────────────────────────────────────────

    @PostMapping(consumes = "multipart/form-data")
    @Transactional
    public MenuImageDto upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "role", required = false) String role
    ) {
        if (file == null || file.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Archivo vacío");
        if (file.getSize() > MAX_SIZE)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "La imagen supera los 2MB permitidos");
        String ct = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (!ALLOWED_TYPES.contains(ct))
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Formato no permitido (JPG, PNG, WEBP, GIF)");

        MenuImage img = new MenuImage();
        img.setName(name != null && !name.isBlank() ? name : file.getOriginalFilename());
        img.setRole(role != null && !role.isBlank() ? role : "generic");
        img.setContentType(ct);
        img.setSizeBytes(file.getSize());
        try { img.setData(file.getBytes()); }
        catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error leyendo archivo");
        }
        return MenuImageDto.from(repo.save(img), false);
    }

    @PutMapping("/{id}/meta")
    @Transactional
    public MenuImageDto updateMeta(@PathVariable Long id, @RequestBody UpdateMetaRequest body) {
        MenuImage img = repo.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (body.name != null)      img.setName(body.name);
        if (body.role != null)      img.setRole(body.role);
        if (body.sortOrder != null) img.setSortOrder(body.sortOrder);
        if (body.active != null)    img.setActive(body.active);
        return MenuImageDto.from(repo.save(img), false);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public void delete(@PathVariable Long id) {
        if (!repo.existsById(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        repo.deleteById(id);
    }

    // ─────────────────────────────────────────────────────────────────────
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class UpdateMetaRequest {
        public String  name;
        public String  role;
        public Integer sortOrder;
        public Boolean active;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class MenuImageDto {
        public Long    id;
        public String  name;
        public String  role;
        public String  contentType;
        public Long    sizeBytes;
        public Integer sortOrder;
        public Boolean active;
        public String  url;       // path relativo a /api/admin/menu-images/{id}/raw
        public String  dataUrl;   // data:image/png;base64,... (solo si includeData)
        public Instant createdAt;

        public static MenuImageDto from(MenuImage img, boolean includeData) {
            MenuImageDto d = new MenuImageDto();
            d.id          = img.getId();
            d.name        = img.getName();
            d.role        = img.getRole();
            d.contentType = img.getContentType();
            d.sizeBytes   = img.getSizeBytes();
            d.sortOrder   = img.getSortOrder();
            d.active      = img.getActive();
            d.url         = "/api/public/menu-images/" + img.getId() + "/raw";
            d.createdAt   = img.getCreatedAt();
            if (includeData && img.getData() != null) {
                d.dataUrl = "data:" + (img.getContentType() != null ? img.getContentType() : "image/jpeg")
                        + ";base64," + Base64.getEncoder().encodeToString(img.getData());
            }
            return d;
        }
    }
}
