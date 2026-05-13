package app.coincidir.api.marketing.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MarketingModuleBootstrap — Asegura que el seed mínimo del módulo
 * exista al arrancar la app.
 *
 * Por qué este enfoque: el proyecto usa `ddl-auto: update` (sin Flyway
 * activo). Hibernate crea las tablas a partir de las entidades, pero no
 * ejecuta INSERTs. Como el programa singleton (id=1) tiene que existir
 * para que el resto del módulo funcione, lo creamos vía ApplicationRunner
 * la primera vez que la app arranca con el módulo presente.
 *
 * Es idempotente: si el registro ya existe (porque corriste el SQL a mano
 * o ya arrancó antes), no hace nada.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class MarketingModuleBootstrap {

    @Bean
    public ApplicationRunner ensureMarketingSeed(LoyaltyProgramService programService) {
        return args -> {
            try {
                var p = programService.getOrCreateDefault();
                log.info("Marketing module bootstrap OK. ProgramId={} active={}",
                    p.getId(), p.getActive());
            } catch (Exception e) {
                log.warn("No se pudo inicializar el seed del módulo Marketing: {}", e.getMessage());
            }
        };
    }
}
