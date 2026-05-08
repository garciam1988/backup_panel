package app.coincidir.api.security;

import io.jsonwebtoken.io.Decoders;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;

/**
 * JwtSecretDecoder — utilitario para convertir el string del secret JWT
 * (que viene de la env var {@code JWT_SECRET}) en bytes para HMAC-SHA.
 *
 * Acepta tres formatos automáticamente:
 *   1. Base64 estándar:  {@code "8k8l0p0vC9k14y3w3m1k+T1bJ3Vq1Q9WZzqQyV6f1s8="}
 *   2. Base64 URL-safe:  {@code "abc_-DEF123"} (con guiones y guiones bajos)
 *   3. Texto plano:      {@code "una-frase-larga-que-es-mi-secret"}
 *
 * Esto soluciona el problema de que algunas env vars generadas con
 * {@code openssl rand -base64 32} contienen caracteres URL-safe que el
 * decoder Base64 estándar de jjwt rechaza con
 * "Illegal base64 character: '-'".
 *
 * Para HMAC-SHA-256 hacen falta al menos 32 bytes de clave. Si el secret
 * resultante es más corto, se loguea un warning (jjwt va a tirar una
 * excepción al usar la key, lo cual es lo correcto).
 */
@Slf4j
public final class JwtSecretDecoder {

    private JwtSecretDecoder() {}

    /**
     * Decodifica el string del secret a bytes. NUNCA tira excepción por
     * formato — siempre devuelve algo utilizable. La validación de longitud
     * de la clave la hace jjwt cuando se construye el SecretKey.
     */
    public static byte[] decode(String secret) {
        if (secret == null || secret.isBlank()) {
            // En lugar de tirar, devolvemos un array vacío. jjwt va a fallar
            // al construir el SecretKey con un mensaje claro de "key length
            // less than 256 bits". El admin sabrá que falta configurar el secret.
            log.error("[JwtSecretDecoder] JWT secret está vacío. " +
                    "Configurá la variable de entorno JWT_SECRET con al menos 32 bytes.");
            return new byte[0];
        }

        // Caso 1: Base64 URL-safe (contiene '-' o '_')
        if (secret.contains("-") || secret.contains("_")) {
            try {
                byte[] bytes = Decoders.BASE64URL.decode(secret);
                log.debug("[JwtSecretDecoder] Secret decodificado como Base64 URL-safe ({} bytes)", bytes.length);
                return bytes;
            } catch (Exception e) {
                // Si tiene guiones pero NO es Base64URL válido, es texto plano
                log.debug("[JwtSecretDecoder] Secret tiene guiones pero no es Base64URL, usando texto plano");
                return secret.getBytes(StandardCharsets.UTF_8);
            }
        }

        // Caso 2: Base64 estándar (sin caracteres URL-safe). Probamos a decodificar.
        // Si el string parece Base64 (largo razonable, caracteres válidos), lo
        // decodificamos. Si no, lo tratamos como texto plano.
        if (looksLikeBase64(secret)) {
            try {
                byte[] bytes = Decoders.BASE64.decode(secret);
                if (bytes.length >= 16) {
                    log.debug("[JwtSecretDecoder] Secret decodificado como Base64 estándar ({} bytes)", bytes.length);
                    return bytes;
                }
                // Decodificó pero quedó muy corto: probablemente era texto plano que casualmente parecía Base64
            } catch (Exception ignored) {
                // No era Base64 válido, cae a texto plano
            }
        }

        // Caso 3: texto plano (frase, contraseña larga, etc.)
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        log.debug("[JwtSecretDecoder] Secret usado como texto plano ({} bytes)", bytes.length);
        return bytes;
    }

    /**
     * Heurística simple para distinguir Base64 de texto plano.
     * Base64 estándar tiene solo [A-Za-z0-9+/=] y la longitud es múltiplo de 4
     * (con padding). Si NO matchea, asumimos texto plano.
     */
    private static boolean looksLikeBase64(String s) {
        if (s.length() < 4 || s.length() % 4 != 0) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean valid = (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '+' || c == '/' || c == '=';
            if (!valid) return false;
        }
        return true;
    }
}
