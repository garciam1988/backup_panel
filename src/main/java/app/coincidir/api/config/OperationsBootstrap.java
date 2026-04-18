package app.coincidir.api.config;

import app.coincidir.api.domain.ServiceCode;
import app.coincidir.api.domain.operations.*;
import app.coincidir.api.repository.ServiceOperationStatusDefinitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Carga inicial de estados de operación por servicio.
 *
 * Se ejecuta de forma idempotente: si el registro ya existe (service_code + status_code), no lo recrea.
 */
@Component
@RequiredArgsConstructor
public class OperationsBootstrap implements CommandLineRunner {

    private final ServiceOperationStatusDefinitionRepository repo;

    @Override
    public void run(String... args) {
        seed(ServiceCode.ALOJAMIENTOS, List.of(
                def(ServiceCode.ALOJAMIENTOS, OperationStatusCode.PENDIENTE,   "Pendiente",  ServiceStatusColor.RED,    0),
                def(ServiceCode.ALOJAMIENTOS, OperationStatusCode.SOLICITADO,  "Solicitado", ServiceStatusColor.VIOLET, 1),
                def(ServiceCode.ALOJAMIENTOS, OperationStatusCode.RESERVADO,   "Reservado",  ServiceStatusColor.SKY,    2),
                def(ServiceCode.ALOJAMIENTOS, OperationStatusCode.SENADO,      "Señado",     ServiceStatusColor.YELLOW, 3),
                def(ServiceCode.ALOJAMIENTOS, OperationStatusCode.PAGADO,      "Pagado",     ServiceStatusColor.GREEN,  4)
        ));

        seed(ServiceCode.AEREOS, List.of(
                def(ServiceCode.AEREOS, OperationStatusCode.PENDIENTE, "Pendiente", ServiceStatusColor.RED, 0),
                def(ServiceCode.AEREOS, OperationStatusCode.EMITIDO, "Emitido", ServiceStatusColor.GREEN, 1)
        ));

        seed(ServiceCode.TRASLADOS, List.of(
                def(ServiceCode.TRASLADOS, OperationStatusCode.PENDIENTE,  "Pendiente",  ServiceStatusColor.RED,    0),
                def(ServiceCode.TRASLADOS, OperationStatusCode.SOLICITADO, "Solicitado", ServiceStatusColor.VIOLET, 1),
                def(ServiceCode.TRASLADOS, OperationStatusCode.SENADO,     "Señado",     ServiceStatusColor.YELLOW, 2),
                def(ServiceCode.TRASLADOS, OperationStatusCode.PAGADO,     "Pagado",     ServiceStatusColor.GREEN,  3)
        ));

        seed(ServiceCode.TRASLADOS_DESTINO, List.of(
                def(ServiceCode.TRASLADOS_DESTINO, OperationStatusCode.PENDIENTE,  "Pendiente",  ServiceStatusColor.RED,    0),
                def(ServiceCode.TRASLADOS_DESTINO, OperationStatusCode.SOLICITADO, "Solicitado", ServiceStatusColor.VIOLET, 1),
                def(ServiceCode.TRASLADOS_DESTINO, OperationStatusCode.SENADO,     "Señado",     ServiceStatusColor.YELLOW, 2),
                def(ServiceCode.TRASLADOS_DESTINO, OperationStatusCode.PAGADO,     "Pagado",     ServiceStatusColor.GREEN,  3)
        ));

        seed(ServiceCode.FERRY, List.of(
                def(ServiceCode.FERRY, OperationStatusCode.PENDIENTE, "Pendiente", ServiceStatusColor.RED, 0),
                def(ServiceCode.FERRY, OperationStatusCode.EMITIDO, "Emitido", ServiceStatusColor.GREEN, 1)
        ));
    }

    private ServiceOperationStatusDefinition def(ServiceCode serviceCode, OperationStatusCode statusCode, String label, ServiceStatusColor color, int sortOrder) {
        return ServiceOperationStatusDefinition.builder()
                .serviceCode(serviceCode)
                .statusCode(statusCode)
                .label(label)
                .color(color)
                .sortOrder(sortOrder)
                .active(true)
                .build();
    }

    private void seed(ServiceCode serviceCode, List<ServiceOperationStatusDefinition> defs) {
        for (ServiceOperationStatusDefinition d : defs) {
            boolean exists = repo.findByServiceCodeAndStatusCode(serviceCode, d.getStatusCode()).isPresent();
            if (!exists) repo.save(d);
        }
    }
}
