package app.coincidir.api.web.admin.backups;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "coincidir.backups")
public class BackupsProperties {

    /**
     * Carpeta donde se guardan los dumps (por defecto: "dumps").
     */
    private String dir = "dumps";

    /**
     * Código requerido para ejecutar restores.
     */
    private String adminCode = "1234";

    /**
     * Timezone opcional para ejecución del backup diario (ej: "America/Argentina/Buenos_Aires").
     * Si es null/vacío, se usa la zona del sistema.
     */
    private String timezone;
}
