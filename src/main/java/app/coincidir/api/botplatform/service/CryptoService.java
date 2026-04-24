package app.coincidir.api.botplatform.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * CryptoService — encripta/desencripta credenciales sensibles con AES-256-GCM.
 *
 * La clave maestra viene de la env var COINCIDIR_CRYPTO_KEY (recomendado):
 *   - Si no está presente, usa un fallback derivado del hostname del servidor.
 *     Esto NO es seguro para producción — es un fallback para desarrollo.
 *
 * Formato del output: Base64 de (nonce 12 bytes + ciphertext + tag).
 * GCM provee authenticated encryption: si alguien modifica el ciphertext, el
 * decrypt falla. No usamos CBC ni ECB (inseguros).
 */
@Slf4j
@Service
public class CryptoService {

    @Value("${coincidir.crypto-key:}")
    private String cryptoKeyFromEnv;

    private byte[] aesKey;
    private final SecureRandom random = new SecureRandom();

    private static final int NONCE_BYTES = 12;   // GCM standard
    private static final int TAG_BITS = 128;      // auth tag
    private static final int KEY_BYTES = 32;      // AES-256

    @PostConstruct
    void init() {
        String source = cryptoKeyFromEnv;
        if (source == null || source.isBlank()) {
            // Fallback inseguro: deriva una clave del hostname del server.
            // Perfecto para dev/local. En prod se DEBE setear COINCIDIR_CRYPTO_KEY.
            log.warn("[Crypto] COINCIDIR_CRYPTO_KEY no configurada. Usando fallback derivado del host (INSEGURO para prod).");
            try {
                source = java.net.InetAddress.getLocalHost().getHostName() + "-coincidir-fallback-v1";
            } catch (Exception e) {
                source = "coincidir-default-fallback-v1";
            }
        }

        // Derivar clave AES-256 con SHA-256(source) — determinístico pero suficiente
        // para una clave de 32 bytes a partir de un string variable.
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            aesKey = sha.digest(source.getBytes(StandardCharsets.UTF_8));
            log.info("[Crypto] Clave AES-256 inicializada ({} bytes).", aesKey.length);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo derivar la clave AES", e);
        }
    }

    /**
     * Encripta un string. Retorna Base64(nonce || ciphertext || tag).
     * Si el input es null o vacío, retorna null.
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) return null;
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(aesKey, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[nonce.length + ct.length];
            System.arraycopy(nonce, 0, out, 0, nonce.length);
            System.arraycopy(ct, 0, out, nonce.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new RuntimeException("Error encriptando", e);
        }
    }

    /**
     * Desencripta lo que devolvió encrypt(). Si el input es null, retorna null.
     * Si el input está corrupto o usa una clave distinta, tira excepción.
     */
    public String decrypt(String encoded) {
        if (encoded == null || encoded.isEmpty()) return null;
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            if (all.length < NONCE_BYTES + 16) throw new IllegalArgumentException("ciphertext demasiado corto");
            byte[] nonce = new byte[NONCE_BYTES];
            byte[] ct = new byte[all.length - NONCE_BYTES];
            System.arraycopy(all, 0, nonce, 0, NONCE_BYTES);
            System.arraycopy(all, NONCE_BYTES, ct, 0, ct.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(aesKey, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            byte[] pt = cipher.doFinal(ct);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Error desencriptando (posible clave cambiada o ciphertext corrupto)", e);
        }
    }
}
