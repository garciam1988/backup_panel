package app.coincidir.api.web.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BaggageRuleOptionDto {
    private Long id;
    private Integer weightKg;
    private String dimensions;
    private String label;
}
