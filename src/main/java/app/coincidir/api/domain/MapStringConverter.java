package app.coincidir.api.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.*;

@Converter
public class MapStringConverter implements AttributeConverter<Map<String,String>, String> {
    private static final ObjectMapper M = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(Map<String, String> attribute) {
        try { return attribute == null ? null : M.writeValueAsString(attribute); }
        catch (Exception e) { throw new IllegalArgumentException("commonPrefs -> JSON", e); }
    }

    @Override
    public Map<String, String> convertToEntityAttribute(String dbData) {
        try {
            if (dbData == null || dbData.isBlank()) return new LinkedHashMap<>();
            return M.readValue(dbData, M.getTypeFactory()
                    .constructMapType(LinkedHashMap.class, String.class, String.class));
        } catch (Exception e) { return new LinkedHashMap<>(); }
    }
}
