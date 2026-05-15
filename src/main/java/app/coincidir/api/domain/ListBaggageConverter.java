package app.coincidir.api.domain;

import app.coincidir.api.web.dto.BaggageItem;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.ArrayList;
import java.util.List;

@Converter(autoApply = false)
public class ListBaggageConverter implements AttributeConverter<List<BaggageItem>, String> {
    private static final ObjectMapper M = new ObjectMapper();
    private static final TypeReference<List<BaggageItem>> T = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(List<BaggageItem> attribute) {
        try {
            if (attribute == null) return null;      // null explícito -> NULL
            return M.writeValueAsString(attribute);  // [] o lista -> JSON
        } catch (Exception e) {
            throw new IllegalArgumentException("luggage -> JSON", e);
        }
    }

    @Override
    public List<BaggageItem> convertToEntityAttribute(String dbData) {
        try {
            if (dbData == null || dbData.isBlank()) return new ArrayList<>();
            return M.readValue(dbData, T);           // "[]" -> lista vacía
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

}
