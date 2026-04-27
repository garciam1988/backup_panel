package app.coincidir.api.coinbot;

import app.coincidir.api.domain.MenuVideo;
import app.coincidir.api.repository.MenuVideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * PublicMenuVideoController — sirve el binario del video del menú bajo
 * /api/public/** para que el tag <video src="..."> del bot pueda cargarlo
 * sin necesidad de mandar Authorization header.
 *
 * Soporta requests con header "Range" para que browsers puedan hacer
 * seeking eficiente y carga progresiva. Esto es crítico para videos —
 * sin esto el browser tiene que descargar el archivo entero antes de
 * empezar a reproducir.
 */
@Slf4j
@RestController
@RequestMapping("/api/public/menu-videos")
@RequiredArgsConstructor
public class PublicMenuVideoController {

    private final MenuVideoRepository repo;

    @GetMapping("/{id}/raw")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> raw(
            @PathVariable Long id,
            @RequestHeader(value = "Range", required = false) String rangeHeader
    ) {
        MenuVideo v = repo.findById(id).orElse(null);
        if (v == null || v.getData() == null || v.getData().length == 0) {
            log.warn("public raw video: id {} no existe o sin data", id);
            return ResponseEntity.notFound().build();
        }
        byte[] full = v.getData();
        long fullLen = full.length;
        String ct = (v.getContentType() != null && !v.getContentType().isBlank())
                ? v.getContentType() : "video/mp4";

        // Si no hay Range header, devolvemos el archivo completo
        if (rangeHeader == null || !rangeHeader.startsWith("bytes=")) {
            HttpHeaders h = new HttpHeaders();
            try { h.setContentType(MediaType.parseMediaType(ct)); }
            catch (Exception e) { h.setContentType(MediaType.parseMediaType("video/mp4")); }
            h.setContentLength(fullLen);
            h.set("Accept-Ranges", "bytes");
            h.setCacheControl("public, max-age=86400");
            return new ResponseEntity<>(full, h, HttpStatus.OK);
        }

        // Parsear Range: "bytes=START-END" (END opcional)
        long start, end;
        try {
            String spec = rangeHeader.substring("bytes=".length()).trim();
            int dash = spec.indexOf('-');
            if (dash < 0) throw new IllegalArgumentException("Range mal formado");
            start = Long.parseLong(spec.substring(0, dash));
            String endStr = spec.substring(dash + 1);
            end = endStr.isBlank() ? fullLen - 1 : Long.parseLong(endStr);
            if (start < 0 || end >= fullLen || start > end) throw new IllegalArgumentException("Range fuera de rango");
        } catch (Exception e) {
            HttpHeaders h = new HttpHeaders();
            h.set("Content-Range", "bytes */" + fullLen);
            return new ResponseEntity<>(h, HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
        }

        int chunkLen = (int) (end - start + 1);
        byte[] chunk = new byte[chunkLen];
        System.arraycopy(full, (int) start, chunk, 0, chunkLen);

        HttpHeaders h = new HttpHeaders();
        try { h.setContentType(MediaType.parseMediaType(ct)); }
        catch (Exception e) { h.setContentType(MediaType.parseMediaType("video/mp4")); }
        h.setContentLength(chunkLen);
        h.set("Accept-Ranges", "bytes");
        h.set("Content-Range", "bytes " + start + "-" + end + "/" + fullLen);
        h.setCacheControl("public, max-age=86400");
        return new ResponseEntity<>(chunk, h, HttpStatus.PARTIAL_CONTENT);
    }
}
