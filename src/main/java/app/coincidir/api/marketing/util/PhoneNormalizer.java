package app.coincidir.api.marketing.util;

/**
 * Normaliza números de teléfono para deduplicar clientes.
 *
 * El cliente puede ingresar el teléfono de muchas formas:
 *   "11 1234-5678", "+54 9 11 1234 5678", "(011) 1234-5678", "541112345678"
 *
 * Todas representan el mismo número. Para evitar duplicados en
 * loyalty_customer (que tiene UNIQUE en phone), normalizamos a un formato
 * canónico: solo dígitos, con prefijo de país si está detectable.
 *
 * Regla actual (Argentina-friendly, pero compatible internacional):
 *   1. Quitar todo lo que no sea dígito o '+'.
 *   2. Si arranca con '+', mantenerlo y devolver "+\d+".
 *   3. Si arranca con '00', quitarlo y prefijar '+'.
 *   4. Si tiene 10-11 dígitos sin prefijo, asumir AR (+54).
 *   5. Si tiene 13 dígitos arrancando con 54, agregar '+'.
 *   6. Otros casos: devolver tal cual (solo dígitos).
 *
 * Ejemplos:
 *   "11 1234-5678"       → "+541112345678"
 *   "+54 9 11 1234 5678" → "+5491112345678"
 *   "(011) 1234-5678"    → "+541112345678"
 *   "5491112345678"      → "+5491112345678"
 */
public final class PhoneNormalizer {

    private PhoneNormalizer() {}

    public static String normalize(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;

        boolean hadPlus = trimmed.startsWith("+");
        // Strip todo lo que no sea dígito
        StringBuilder sb = new StringBuilder();
        for (char c : trimmed.toCharArray()) {
            if (Character.isDigit(c)) sb.append(c);
        }
        String digits = sb.toString();
        if (digits.isEmpty()) return null;

        // 00 prefix (intl dialing) → +
        if (digits.startsWith("00")) {
            return "+" + digits.substring(2);
        }

        if (hadPlus) {
            return "+" + digits;
        }

        // Sin prefijo internacional: heurística Argentina
        if (digits.startsWith("54") && (digits.length() == 12 || digits.length() == 13)) {
            return "+" + digits;
        }
        if (digits.length() >= 10 && digits.length() <= 11) {
            return "+54" + digits;
        }

        // Otros casos: devolver con + igual para mantener consistencia
        return "+" + digits;
    }

    /** Devuelve true si dos teléfonos normalizan al mismo valor. */
    public static boolean sameNumber(String a, String b) {
        String na = normalize(a);
        String nb = normalize(b);
        return na != null && na.equals(nb);
    }
}
