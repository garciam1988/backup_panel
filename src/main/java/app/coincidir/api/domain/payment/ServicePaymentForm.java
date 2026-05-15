package app.coincidir.api.domain.payment;

public enum ServicePaymentForm {
    SENA,
    TOTAL;

    public static ServicePaymentForm fromString(String raw) {
        if (raw == null) return null;
        String v = raw.trim().toUpperCase();
        return ServicePaymentForm.valueOf(v);
    }
}
