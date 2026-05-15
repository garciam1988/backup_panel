package app.coincidir.api.web.admin.params.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ParamTableDef(
        String key,
        String label,
        String category,
        List<String> primaryKey,
        List<ParamFieldDef> fields
) {
}
