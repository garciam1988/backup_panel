package app.coincidir.api.marketing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.jce.spec.ECNamedCurveSpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * WebPushService — Envía notificaciones Web Push (W3C) con autenticación VAPID.
 *
 * IMPLEMENTACIÓN NATIVA (sin libs externas): usa solo APIs estándar de
 * Java 17+ y BouncyCastle para operaciones con curvas elípticas con nombre
 * (P-256/secp256r1) que la JDK no expone directamente.
 *
 * El protocolo Web Push se compone de 3 RFCs:
 *   - RFC 8030: protocolo HTTP base (POST al endpoint del Push Service).
 *   - RFC 8291: cifrado del payload con AES-128-GCM y derivación HKDF.
 *   - RFC 8292: autenticación VAPID con JWT firmado en ECDSA P-256.
 *
 * Cómo funciona el envío:
 *   1. ECDH entre nuestras claves efímeras y la pubkey del cliente.
 *   2. HKDF para derivar clave AES-128 (CEK) y nonce.
 *   3. AES-128-GCM para cifrar el payload.
 *   4. Firmar JWT con la clave privada VAPID (ECDSA P-256).
 *   5. POSTear al endpoint del Push Service con headers especiales.
 *
 * Si las claves VAPID no están seteadas, isConfigured() devuelve false y
 * el módulo Marketing sigue funcionando con los pushes en QUEUED.
 */
@Slf4j
@Service
public class WebPushService {

    private static final String CURVE_NAME = "secp256r1";
    private static final String CONTENT_ENCODING = "aes128gcm";
    private static final int SALT_LENGTH = 16;
    private static final int CEK_LENGTH = 16;
    private static final int NONCE_LENGTH = 12;
    private static final int RS_DEFAULT = 4096;
    private static final Duration JWT_TTL = Duration.ofHours(12);

    private final String publicKeyB64;
    private final String privateKeyB64;
    private final String subject;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;

    // Caché de claves parseadas
    private byte[] vapidPublicKeyBytes;
    private ECPrivateKey vapidPrivateKey;

    public WebPushService(
        @Value("${coincidir.vapid.public-key:}") String publicKey,
        @Value("${coincidir.vapid.private-key:}") String privateKey,
        @Value("${coincidir.vapid.subject:mailto:info@coincidir.app}") String subject
    ) {
        this.publicKeyB64 = publicKey;
        this.privateKeyB64 = privateKey;
        this.subject = subject;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    @PostConstruct
    public void init() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }

        if (!isConfigured()) {
            log.warn("WebPushService NO configurado (faltan VAPID_PUBLIC_KEY / VAPID_PRIVATE_KEY).");
            try {
                GeneratedKeyPair sample = generateNewKeyPair();
                log.warn("══════════════════════════════════════════════════════════════════");
                log.warn("  Para activar Web Push, copiá ESTAS claves a tus env vars y reiniciá:");
                log.warn("    VAPID_PUBLIC_KEY={}", sample.publicKey());
                log.warn("    VAPID_PRIVATE_KEY={}", sample.privateKey());
                log.warn("    VAPID_SUBJECT=mailto:tu@email.com");
                log.warn("  Mientras tanto, los Web Push del módulo Marketing quedan QUEUED.");
                log.warn("══════════════════════════════════════════════════════════════════");
            } catch (Exception e) {
                log.warn("No se pudieron generar claves de muestra: {}", e.getMessage());
            }
            return;
        }
        try {
            this.vapidPublicKeyBytes = Base64.getUrlDecoder().decode(publicKeyB64);
            this.vapidPrivateKey = decodePrivateKey(privateKeyB64);
            log.info("WebPushService configurado. subject={}", subject);
        } catch (Exception e) {
            log.error("Error inicializando WebPushService: {}", e.getMessage(), e);
        }
    }

    public boolean isConfigured() {
        return publicKeyB64 != null && !publicKeyB64.isBlank()
            && privateKeyB64 != null && !privateKeyB64.isBlank();
    }

    public String getPublicKey() {
        return publicKeyB64;
    }

    /**
     * Envía un push a la subscription dada con un payload JSON.
     */
    public Result send(String subscriptionJson, String payload) {
        if (!isConfigured() || vapidPrivateKey == null) {
            return Result.failure("Web Push no configurado (faltan VAPID keys)");
        }
        if (subscriptionJson == null || subscriptionJson.isBlank()) {
            return Result.failure("Subscription vacía");
        }
        try {
            JsonNode node = objectMapper.readTree(subscriptionJson);
            String endpoint = node.path("endpoint").asText();
            String clientP256dh = node.path("keys").path("p256dh").asText();
            String clientAuth = node.path("keys").path("auth").asText();
            if (endpoint.isBlank() || clientP256dh.isBlank() || clientAuth.isBlank()) {
                return Result.failure("Subscription JSON inválida: faltan campos");
            }

            byte[] clientPublicKeyBytes = Base64.getUrlDecoder().decode(clientP256dh);
            byte[] authSecret = Base64.getUrlDecoder().decode(clientAuth);

            byte[] payloadBytes = payload == null ? new byte[0] : payload.getBytes(StandardCharsets.UTF_8);
            byte[] encryptedBody = encryptPayload(payloadBytes, clientPublicKeyBytes, authSecret);

            String jwt = generateVapidJwt(endpoint);
            String authHeader = "vapid t=" + jwt + ", k=" + base64UrlNoPad(vapidPublicKeyBytes);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/octet-stream")
                .header("Content-Encoding", CONTENT_ENCODING)
                .header("TTL", "86400")
                .header("Authorization", authHeader)
                .POST(HttpRequest.BodyPublishers.ofByteArray(encryptedBody))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code >= 200 && code < 300) {
                return Result.success(code);
            }
            if (code == 404 || code == 410) {
                return Result.failureExpired(code, "Subscription expirada o inválida (HTTP " + code + ")");
            }
            String detail = response.body() == null ? "" : response.body();
            if (detail.length() > 200) detail = detail.substring(0, 200);
            return Result.failure("Push Service HTTP " + code + (detail.isBlank() ? "" : ": " + detail));
        } catch (Exception e) {
            log.warn("Error enviando push: {}", e.getMessage(), e);
            return Result.failure(e.getMessage());
        }
    }

    // ── Cifrado del payload (RFC 8291 - aes128gcm) ───────────────────────

    private byte[] encryptPayload(byte[] plaintext, byte[] clientPublicKeyBytes, byte[] authSecret) throws Exception {
        // 1) Claves efímeras nuevas para este envío
        KeyPair ephemeralKeyPair = generateEcKeyPair();
        ECPublicKey ephemeralPub = (ECPublicKey) ephemeralKeyPair.getPublic();
        byte[] ephemeralPubBytes = encodePublicKeyUncompressed(ephemeralPub);

        // 2) ECDH para shared secret
        PublicKey clientPub = decodePublicKeyFromUncompressed(clientPublicKeyBytes);
        KeyAgreement ka = KeyAgreement.getInstance("ECDH", BouncyCastleProvider.PROVIDER_NAME);
        ka.init(ephemeralKeyPair.getPrivate());
        ka.doPhase(clientPub, true);
        byte[] sharedSecret = ka.generateSecret();

        // 3) Salt aleatorio
        byte[] salt = new byte[SALT_LENGTH];
        new SecureRandom().nextBytes(salt);

        // 4) PRK_key = HKDF-Extract(authSecret, sharedSecret)
        byte[] prkKey = hkdfExtract(authSecret, sharedSecret);

        // 5) keyInfo per RFC 8291 §3.3
        byte[] keyInfo = concat(
            "WebPush: info\0".getBytes(StandardCharsets.UTF_8),
            clientPublicKeyBytes,
            ephemeralPubBytes
        );

        // 6) IKM = HKDF-Expand(PRK_key, keyInfo, 32)
        byte[] ikm = hkdfExpand(prkKey, keyInfo, 32);

        // 7) PRK = HKDF-Extract(salt, IKM)
        byte[] prk = hkdfExtract(salt, ikm);

        // 8) CEK y NONCE
        byte[] cek = hkdfExpand(prk, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.UTF_8), CEK_LENGTH);
        byte[] nonce = hkdfExpand(prk, "Content-Encoding: nonce\0".getBytes(StandardCharsets.UTF_8), NONCE_LENGTH);

        // 9) Padding: byte 0x02 al final = "último record"
        byte[] padded = new byte[plaintext.length + 1];
        System.arraycopy(plaintext, 0, padded, 0, plaintext.length);
        padded[plaintext.length] = 0x02;

        // 10) AES-128-GCM
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] ciphertext = cipher.doFinal(padded);

        // 11) Header del body: salt(16) + rs(4) + keyidLen(1) + keyid(65) + ciphertext
        ByteBuffer bb = ByteBuffer.allocate(SALT_LENGTH + 4 + 1 + 65 + ciphertext.length);
        bb.put(salt);
        bb.putInt(RS_DEFAULT);
        bb.put((byte) 65);
        bb.put(ephemeralPubBytes);
        bb.put(ciphertext);
        return bb.array();
    }

    // ── HKDF (RFC 5869) ──────────────────────────────────────────────────

    private static byte[] hkdfExtract(byte[] salt, byte[] ikm) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        return mac.doFinal(ikm);
    }

    private static byte[] hkdfExpand(byte[] prk, byte[] info, int length) throws Exception {
        if (length > 32) throw new IllegalArgumentException("HKDF-Expand simplificado solo soporta length <= 32");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        byte[] input = concat(info, new byte[]{0x01});
        byte[] full = mac.doFinal(input);
        byte[] out = new byte[length];
        System.arraycopy(full, 0, out, 0, length);
        return out;
    }

    // ── JWT VAPID (RFC 8292) ─────────────────────────────────────────────

    private String generateVapidJwt(String endpoint) throws Exception {
        URI uri = URI.create(endpoint);
        String aud = uri.getScheme() + "://" + uri.getHost();
        long expEpoch = Instant.now().plus(JWT_TTL).getEpochSecond();

        String headerJson = "{\"alg\":\"ES256\",\"typ\":\"JWT\"}";
        String payloadJson = String.format("{\"aud\":\"%s\",\"exp\":%d,\"sub\":\"%s\"}",
            aud, expEpoch, escapeJsonString(subject));

        String headerB64 = base64UrlNoPad(headerJson.getBytes(StandardCharsets.UTF_8));
        String payloadB64 = base64UrlNoPad(payloadJson.getBytes(StandardCharsets.UTF_8));
        String signingInput = headerB64 + "." + payloadB64;

        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(vapidPrivateKey);
        sig.update(signingInput.getBytes(StandardCharsets.UTF_8));
        byte[] derSignature = sig.sign();
        byte[] p1363 = derToP1363(derSignature, 32);

        return signingInput + "." + base64UrlNoPad(p1363);
    }

    // ── Generación / parsing de claves ──────────────────────────────────

    private static KeyPair generateEcKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("ECDH", BouncyCastleProvider.PROVIDER_NAME);
        ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec(CURVE_NAME);
        gen.initialize(spec, new SecureRandom());
        return gen.generateKeyPair();
    }

    private static byte[] encodePublicKeyUncompressed(ECPublicKey publicKey) {
        BigInteger x, y;
        if (publicKey instanceof org.bouncycastle.jce.interfaces.ECPublicKey bcKey) {
            org.bouncycastle.math.ec.ECPoint q = bcKey.getQ().normalize();
            x = q.getAffineXCoord().toBigInteger();
            y = q.getAffineYCoord().toBigInteger();
        } else {
            x = publicKey.getW().getAffineX();
            y = publicKey.getW().getAffineY();
        }
        byte[] xBytes = toFixedBytes(x, 32);
        byte[] yBytes = toFixedBytes(y, 32);
        byte[] out = new byte[65];
        out[0] = 0x04;
        System.arraycopy(xBytes, 0, out, 1, 32);
        System.arraycopy(yBytes, 0, out, 33, 32);
        return out;
    }

    private static PublicKey decodePublicKeyFromUncompressed(byte[] uncompressed) throws Exception {
        if (uncompressed.length != 65 || uncompressed[0] != 0x04) {
            throw new IllegalArgumentException("Clave pública debe ser uncompressed point (65 bytes empezando con 0x04)");
        }
        BigInteger x = new BigInteger(1, java.util.Arrays.copyOfRange(uncompressed, 1, 33));
        BigInteger y = new BigInteger(1, java.util.Arrays.copyOfRange(uncompressed, 33, 65));

        ECNamedCurveParameterSpec bcSpec = ECNamedCurveTable.getParameterSpec(CURVE_NAME);
        ECNamedCurveSpec jceSpec = new ECNamedCurveSpec(CURVE_NAME,
            bcSpec.getCurve(), bcSpec.getG(), bcSpec.getN(), bcSpec.getH());

        ECPublicKeySpec keySpec = new ECPublicKeySpec(new ECPoint(x, y), jceSpec);
        KeyFactory kf = KeyFactory.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
        return kf.generatePublic(keySpec);
    }

    private ECPrivateKey decodePrivateKey(String b64) throws Exception {
        byte[] bytes = Base64.getUrlDecoder().decode(b64);
        BigInteger s = new BigInteger(1, bytes);

        ECNamedCurveParameterSpec bcSpec = ECNamedCurveTable.getParameterSpec(CURVE_NAME);
        ECNamedCurveSpec jceSpec = new ECNamedCurveSpec(CURVE_NAME,
            bcSpec.getCurve(), bcSpec.getG(), bcSpec.getN(), bcSpec.getH());

        ECPrivateKeySpec keySpec = new ECPrivateKeySpec(s, jceSpec);
        KeyFactory kf = KeyFactory.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
        return (ECPrivateKey) kf.generatePrivate(keySpec);
    }

    // ── Generador de claves VAPID (one-shot al setup) ────────────────────

    public static GeneratedKeyPair generateNewKeyPair() {
        try {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(new BouncyCastleProvider());
            }
            KeyPair kp = generateEcKeyPair();

            ECPublicKey pubKey = (ECPublicKey) kp.getPublic();
            org.bouncycastle.jce.interfaces.ECPrivateKey privKey =
                (org.bouncycastle.jce.interfaces.ECPrivateKey) kp.getPrivate();

            byte[] pubBytes = encodePublicKeyUncompressed(pubKey);
            byte[] privBytes = toFixedBytes(privKey.getD(), 32);

            return new GeneratedKeyPair(
                base64UrlNoPad(pubBytes),
                base64UrlNoPad(privBytes)
            );
        } catch (Exception e) {
            throw new RuntimeException("Error generando claves VAPID: " + e.getMessage(), e);
        }
    }

    // ── Utilidades ───────────────────────────────────────────────────────

    private static byte[] toFixedBytes(BigInteger n, int len) {
        byte[] raw = n.toByteArray();
        if (raw.length == len) return raw;
        if (raw.length == len + 1 && raw[0] == 0) {
            byte[] trimmed = new byte[len];
            System.arraycopy(raw, 1, trimmed, 0, len);
            return trimmed;
        }
        if (raw.length < len) {
            byte[] padded = new byte[len];
            System.arraycopy(raw, 0, padded, len - raw.length, raw.length);
            return padded;
        }
        throw new IllegalStateException("Entero más grande que " + len + " bytes");
    }

    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) total += a.length;
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, out, pos, a.length);
            pos += a.length;
        }
        return out;
    }

    private static String base64UrlNoPad(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Convierte una firma ECDSA del formato DER (que escupe la JDK) al
     * formato P1363 (r||s, ambos en `len` bytes), que es lo que pide JWS
     * para ES256.
     */
    private static byte[] derToP1363(byte[] der, int len) {
        if (der[0] != 0x30) throw new IllegalArgumentException("DER inválido: no es SEQUENCE");
        int offset = 2;
        if ((der[1] & 0x80) != 0) offset = 2 + (der[1] & 0x7f);

        if (der[offset] != 0x02) throw new IllegalArgumentException("DER inválido: r no es INTEGER");
        int rLen = der[offset + 1];
        int rStart = offset + 2;
        BigInteger r = new BigInteger(1, java.util.Arrays.copyOfRange(der, rStart, rStart + rLen));

        int sOffset = rStart + rLen;
        if (der[sOffset] != 0x02) throw new IllegalArgumentException("DER inválido: s no es INTEGER");
        int sLen = der[sOffset + 1];
        int sStart = sOffset + 2;
        BigInteger s = new BigInteger(1, java.util.Arrays.copyOfRange(der, sStart, sStart + sLen));

        byte[] out = new byte[2 * len];
        System.arraycopy(toFixedBytes(r, len), 0, out, 0, len);
        System.arraycopy(toFixedBytes(s, len), 0, out, len, len);
        return out;
    }

    private static String escapeJsonString(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ── Tipos públicos ───────────────────────────────────────────────────

    public record Result(boolean accepted, int statusCode, String errorReason, boolean expired) {
        public static Result success(int statusCode) {
            return new Result(true, statusCode, null, false);
        }
        public static Result failure(String reason) {
            return new Result(false, 0, reason, false);
        }
        public static Result failureExpired(int statusCode, String reason) {
            return new Result(false, statusCode, reason, true);
        }
    }

    public record GeneratedKeyPair(String publicKey, String privateKey) {}
}
