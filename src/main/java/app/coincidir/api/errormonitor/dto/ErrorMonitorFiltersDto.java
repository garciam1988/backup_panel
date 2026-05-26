package app.coincidir.api.errormonitor.dto;

import java.util.List;

/**
 * Opciones disponibles para los selects del panel — pobladas desde la BD
 * para no mostrar opciones vacías.
 */
public class ErrorMonitorFiltersDto {
    public List<String> levels;
    public List<String> sources;
    public List<String> errorTypes;
    public List<String> apps;
    public List<String> statuses = List.of("open", "resolved", "ignored");
}
