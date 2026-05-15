package app.coincidir.api.web.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * DTO simple para sugerencias de grupos (compat con endpoints legacy /api/groups/*).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SuggestedGroupDto {
    public Long groupId;
    public String destination;
    public String whenLabel;
    public String status;
    public Integer memberCount;

    // opcional, útil para mostrar resumen de preferencias comunes
    public Map<String, String> commonPrefs;
}
