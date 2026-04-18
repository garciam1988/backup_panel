package app.coincidir.api.web.admin.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import app.coincidir.api.web.dto.AirServiceDto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Carga manual de pasajero desde el panel de grupos (sin grupo asignado).
 *
 * Crea una TravelRequest (como si viniera del flujo de selección) y registra datos de pago
 * dentro de los campos de seña (deposit) de la solicitud.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateManualPassengerRequest(
        String destination,
        String datePresetId,
        String whenLabel,
        Integer age,
        String companionPreference,

        String firstName,
        String lastName,
        String email,
        String phone,
        String phoneCountryCode,
        String gender,

        // Documento / nacimiento (solo algunos flujos)
        String documentType,
        String documentNumber,
        LocalDate birthDate,
        LocalDate documentExpiryDate,
        Boolean documentNoExpiry,
        Boolean documentNotApplicable,

        // Nacionalidad / País (lookup)
        @JsonAlias({"countryId", "paisId", "country_id", "pais_id", "nationalityId", "nacionalidadId"})
        Long countryId,
        @JsonAlias({"country", "pais", "nationality", "nacionalidad"})
        String country,

        // Modo de carga (INDIVIDUAL | GROUP) - opcional
        String loadMode,

		// Preferencias de viaje (solo carga INDIVIDUAL)
		// También se usa como "Fecha de viaje desde (nueva)" en carga GRUPAL (se mappea/normaliza en el controller).
		// Nota: se recibe como String para tolerar variaciones de formato (ISO, dd/MM/yyyy, ISO date-time, etc.)
		@JsonAlias({
				"travelStartDate",
				"travel_start_date",
				"travelStart",
				"travelStartDateIso",
				"travelStartDateISO",
				"fechaInicioViaje",
				"fecha_inicio_viaje",
				"travelStartDateNew",
				"travelDateNew",
				"travelStartDateNueva",
				"fechaViajeDesdeNueva",
				"travelDateStart",
				"travel_date_start",
				"travelDateStartNew",
				"fechaInicioDeViaje",
				"fecha_inicio_de_viaje",
		})
		String travelStartDate,

        @JsonAlias({"travelEndDate", "travel_end_date", "fechaFinViaje", "fecha_fin_viaje"})
        String travelEndDate,

        String paymentPlanType,
        String paymentMethod,
        BigDecimal totalAmount,
        String receiptLast4,
        String cardLast4,
        Long bankId,
        LocalDate paymentDate,

        @JsonAlias({"quotedValue", "valorCotizado", "valor_cotizado"})
        BigDecimal quotedValue,

        @JsonAlias({"quotedDate", "quotedAt", "fechaCotizacion", "fecha_cotizacion"})
        LocalDate quotedDate,

        AirServiceDto airService,

        // Multi-aéreos (solo algunos destinos)
        List<AirServiceDto> airServices,

        List<Installment> installments
) {
    public record Installment(
            Integer installmentNumber,
            BigDecimal amount,
            LocalDate dueDate
    ) {}
}
