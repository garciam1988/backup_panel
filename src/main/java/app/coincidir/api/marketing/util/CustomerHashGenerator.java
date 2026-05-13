package app.coincidir.api.marketing.util;

import java.security.SecureRandom;

/**
 * Genera identificadores opacos URL-safe para clientes (customerHash) y
 * códigos cortos legibles para canjes (redemptionCode).
 *
 * customerHash: 21 chars URL-safe (alfanumérico + guión/underscore).
 *   Espacio de búsqueda 64^21 ≈ 1e38, equivalente a UUID4 en colisiones.
 *   Se usa en la URL pública de la PWA: loyalty.cliente.com/c/{hash}
 *
 * redemptionCode: 7 chars alfanuméricos SIN caracteres ambiguos.
 *   Se eliminan 0/O, 1/I/L para que el cliente lea/dicte sin errores
 *   y el mozo no se equivoque al tipear. Espacio ~30^7 ≈ 2.2e10
 *   (suficiente para millones de canjes pendientes simultáneos).
 */
public final class CustomerHashGenerator {

    private static final SecureRandom RNG = new SecureRandom();

    // 64 chars URL-safe (nanoid alphabet sin -)
    private static final char[] HASH_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-".toCharArray();

    // Sin 0/O ni 1/I/L (caracteres ambiguos)
    private static final char[] CODE_ALPHABET =
        "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();

    private CustomerHashGenerator() {}

    /** Genera un customerHash de 21 chars URL-safe. */
    public static String newCustomerHash() {
        char[] out = new char[21];
        for (int i = 0; i < out.length; i++) {
            out[i] = HASH_ALPHABET[RNG.nextInt(HASH_ALPHABET.length)];
        }
        return new String(out);
    }

    /**
     * Genera un redemption code de 7 chars sin caracteres ambiguos.
     * Ejemplo: "K7P2X4N". Fácil de leer y tipear.
     */
    public static String newRedemptionCode() {
        char[] out = new char[7];
        for (int i = 0; i < out.length; i++) {
            out[i] = CODE_ALPHABET[RNG.nextInt(CODE_ALPHABET.length)];
        }
        return new String(out);
    }
}
