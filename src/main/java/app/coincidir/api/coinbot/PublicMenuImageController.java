package app.coincidir.api.coinbot;

import app.coincidir.api.domain.MenuImage;
import app.coincidir.api.repository.MenuImageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * PublicMenuImageController — sirve el binario de las imágenes del menú
 * bajo /api/public/** para que el <img src="..."> del bot pueda cargarlas
 * sin necesidad de mandar Authorization header.
 *
 * Vive bajo /api/public/** (que ya está en permitAll global en SecurityConfig)
 * para evitar depender del orden de matchers. El controller admin está bajo
 * /api/admin/menu-images para las operaciones protegidas (list, upload, etc).
 */
@Slf4j
@RestController
@RequestMapping("/api/public/menu-images")
@RequiredArgsConstructor
public class PublicMenuImageController {

    private final MenuImageRepository repo;

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
}
