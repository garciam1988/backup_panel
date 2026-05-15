package app.coincidir.api.web.dto;

public record BaggageItem(
        String type,          // ej: "CHECKIN" | "CARRYON" | "SPECIAL"
        Integer pieces,       // cantidad
        Double weightKg,      // peso por pieza o total
        String notes          // notas opcionales
) {}
