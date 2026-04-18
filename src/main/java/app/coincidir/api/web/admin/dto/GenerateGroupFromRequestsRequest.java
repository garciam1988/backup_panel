package app.coincidir.api.web.admin.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GenerateGroupFromRequestsRequest(
        List<Long> requestIds,
        Long seedRequestId,
        @JsonAlias({
                "travelDate",
                "travelStartDate",
                "travelStartDateNew",
                "departureDate",
                "fechaViaje",
                "fecha_viaje",
                "travel_date"
        })
        String travelDate,
        @JsonAlias({
                "paymentTitularMemberId",
                "titularMemberId",
                "tripHolderMemberId",
                "tripHolderRequestId",
                "holderMemberId",
                "holderRequestId"
        })
        Long paymentTitularMemberId,
        @JsonAlias({
                "forcedId",
                "forced_id",
                "operationId",
                "nroOperacion"
        })
        Long forcedId
) {}
