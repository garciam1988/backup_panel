package app.coincidir.api.web.admin.params.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ParamFieldDef(
        String key,
        String label,
        String type, // text | number | boolean | time
        Boolean required,
        Boolean editable,
        Object defaultValue
) {
}
