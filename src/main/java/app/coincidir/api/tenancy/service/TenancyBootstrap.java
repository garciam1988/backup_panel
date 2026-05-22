package app.coincidir.api.tenancy.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * TenancyBootstrap — orquestador del seed mínimo al arrancar.
 *
 * Hoy solo dispara la creación de la marca si no existe. Todo lo demás
 * (sucursales, asignaciones de usuarios, etc.) lo hace DIOS desde la UI.
 *
 * Toda la lógica transaccional vive en {@link TenancyBootstrapService}
 * (bean separado) para que las anotaciones {@code @Transactional} funcionen
 * cuando son invocadas desde un @PostConstruct.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenancyBootstrap {

    private final TenancyBootstrapService service;

    @PostConstruct
    public void seedIfEmpty() {
        // Único paso obligatorio: garantizar que exista al menos una marca.
        // Si falla, la app NO arranca — sin marca no funciona nada de tenancy.
        service.ensureAtLeastOneBrand();
    }
}
