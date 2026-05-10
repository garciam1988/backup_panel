package app.coincidir.api.coinbot;

import app.coincidir.api.domain.MenuImage;
import app.coincidir.api.repository.MenuImageRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * PublicMenuImageController — sirve el binario de las imágenes del menú
 * bajo /api/public/** para que el <img src="..."> del bot pueda cargarlas
 * sin necesidad de mandar Authorization header.
 *
 * Vive bajo /api/public/** (que ya está en permitAll global en SecurityConfig)
 * para evitar depender del orden de matchers. El controller admin está bajo
 * /api/admin/menu-images para las operaciones protegidas (list, upload, etc).
 *
 * GET /api/public/menu-images          → listado público (metadata, sin bytes)
 * GET /api/public/menu-images/{id}/raw → bytes crudos para <img src>
 *
 * El listado público existe porque el DigitalMenu del cliente final necesita
 * saber qué imagen tiene rol "hero", "ambient", "logo_center" o "category:X"
 * para construir el header y los placeholders. Sin él, el cliente no tenía
 * forma de pedir la lista (el endpoint admin requiere JWT) y todas las
 * imágenes de rol caían en null → hero se renderizaba con color sólido.
 *
 * Solo se exponen imágenes con active=true. Los bytes no van en este listado
 * (los carga el browser a demanda vía /{id}/raw, que tiene cache headers).
 */
@Slf4j
@RestController
@RequestMapping("/api/public/menu-images")
@RequiredArgsConstructor
public class PublicMenuImageController {

    private final MenuImageRepository repo;

    @GetMapping
    @Transactional(readOnly = true)
    public List<PublicMenuImageDto> list() {
        return repo.findByActiveTrueOrderByRoleAscSortOrderAscIdAsc().stream()
                .map(PublicMenuImageDto::from)
                .toList();
    }

    @GetMapping("/{id}/raw")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> raw(@PathVariable Long id) {
        MenuImage img = repo.findById(id).orElse(null);
        if (img == null) {
            log.warn("public raw: imagen {} no existe", id);
            return ResponseEntity.notFound().build();
        }
        byte[] bytes = img.getData();
        if (bytes == null || bytes.length == 0) {
            log.warn("public raw: imagen {} sin data (bytes null o vacío)", id);
            return ResponseEntity.notFound().build();
        }
        String ct = (img.getContentType() != null && !img.getContentType().isBlank())
                ? img.getContentType() : "image/jpeg";

        HttpHeaders h = new HttpHeaders();
        try { h.setContentType(MediaType.parseMediaType(ct)); }
        catch (Exception e) { h.setContentType(MediaType.IMAGE_JPEG); }
        h.setContentLength(bytes.length);
        h.setCacheControl("public, max-age=86400");
        return new ResponseEntity<>(bytes, h, HttpStatus.OK);
    }

    /**
     * DTO público: incluye metadata necesaria para que el DigitalMenu encuentre
     * la imagen por rol/nombre. NO incluye bytes — el <img> los carga aparte.
     * Mismos campos que MenuImageController.MenuImageDto pero sin dataUrl.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PublicMenuImageDto {
        public Long    id;
        public String  name;
        public String  role;
        public String  contentType;
        public Long    sizeBytes;
        public Integer sortOrder;
        public Boolean active;
        public String  url;        // /api/public/menu-images/{id}/raw
        public Instant createdAt;

        public static PublicMenuImageDto from(MenuImage img) {
            PublicMenuImageDto d = new PublicMenuImageDto();
            d.id          = img.getId();
            d.name        = img.getName();
            d.role        = img.getRole();
            d.contentType = img.getContentType();
            d.sizeBytes   = img.getSizeBytes();
            d.sortOrder   = img.getSortOrder();
            d.active      = img.getActive();
            d.url         = "/api/public/menu-images/" + img.getId() + "/raw";
            d.createdAt   = img.getCreatedAt();
            return d;
        }
    }
}
