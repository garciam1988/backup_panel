package app.coincidir.api.web.dto;

import java.time.LocalDate;
import java.util.List;

public record TravelRequestCreateDto(

        String destination,
        String datePresetId,
        String whenLabel,
        Boolean sharedRoom,

        Integer luggageCount,

        List<BaggageItem> luggage,

        Boolean includesTours,
        Boolean travelAssistance,
        String companionPreference,
        Integer ageMin,
        Integer ageMax,
        Integer paxMin,
        Integer paxMax,
        Boolean smokeFree,
        String name,
        String email,
        String phone,
        String province,
        String locality,
        String postalCode,
        LocalDate birthDate,
        String city,      // opcional (si lo querés seguir mandando)
        String tz,
        String gender,
        TravelersDto travelers   // ⬅️ NUEVO

) {    }

