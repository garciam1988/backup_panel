package app.coincidir.api.marketing.controller;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Genera QR codes para los flujos del módulo marketing.
 *
 *   GET /api/admin/marketing/enroll-qr
 *     Devuelve un PNG con el QR que apunta a la URL pública de alta de
 *     cliente (/altacliente). El dueño imprime y pega en mesas para que
 *     los clientes se den de alta sin intervención del bot.
 *
 *   GET /api/admin/marketing/qr?url=...
 *     Genérico: dada cualquier URL, devuelve el PNG del QR. Útil para
 *     futuras campañas (cupones físicos, eventos, etc.).
 *
 * Requiere JWT admin.
 *
 * El tamaño por default es 600x600 con ECC alto (resistente a impresión
 * doblada / con manchas). Override con query param ?size=N.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/marketing")
public class MarketingQrController {

    /**
     * URL del frontend público. Se lee de la env MARKETING_PWA_BASE_URL.
     * Si está vacía, el QR no se puede generar (qué dominio apuntamos?).
     */
    @Value("${marketing.pwa-base-url:}")
    private String pwaBaseUrl;

    @GetMapping(value = "/enroll-qr", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<?> enrollQr(@RequestParam(value = "size", defaultValue = "600") int size) {
        if (pwaBaseUrl == null || pwaBaseUrl.isBlank()) {
            return ResponseEntity.status(500).body(Map.of(
                "error", "MARKETING_PWA_BASE_URL no está configurada. Setealá en las env vars del backend."
            ));
        }
        String url = pwaBaseUrl.replaceAll("/+$", "") + "/altacliente";
        return generateQrResponse(url, size, "enroll-qr.png");
    }

    @GetMapping(value = "/qr", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<?> qrFromUrl(@RequestParam("url") String url,
                                       @RequestParam(value = "size", defaultValue = "600") int size) {
        if (url == null || url.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "url requerida"));
        }
        return generateQrResponse(url, size, "qr.png");
    }

    private ResponseEntity<?> generateQrResponse(String url, int size, String filename) {
        // Clamp del size para evitar abuso (memoria + tiempo)
        int s = Math.max(100, Math.min(2000, size));
        try {
            byte[] png = generateQrPng(url, s);
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_PNG_VALUE)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300") // 5 min cache
                .body(png);
        } catch (Exception e) {
            log.error("Error generando QR para url={}", url, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "No se pudo generar el QR"));
        }
    }

    /**
     * Genera el PNG del QR con ECC alto y un quiet zone razonable.
     */
    private byte[] generateQrPng(String content, int size) throws WriterException, IOException {
        Map<EncodeHintType, Object> hints = new HashMap<>();
        // Nivel de corrección de error H = ~30% (aguanta logos superpuestos
        // y deterioro físico de la impresión).
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        // Margen blanco alrededor del QR (en módulos) — 2 es estándar.
        hints.put(EncodeHintType.MARGIN, 2);

        QRCodeWriter writer = new QRCodeWriter();
        BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(matrix, "PNG", baos);
        return baos.toByteArray();
    }
}
