package app.coincidir.api.web.dto;

public record FerryProviderDto(
        Long id,
        String name,
        String direccion,
        String telefono,
        String web
) {}
