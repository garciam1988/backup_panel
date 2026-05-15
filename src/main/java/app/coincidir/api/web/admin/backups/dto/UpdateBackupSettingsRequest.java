package app.coincidir.api.web.admin.backups.dto;

import lombok.Data;

@Data
public class UpdateBackupSettingsRequest {
    private Boolean enabled;
    /** HH:mm */
    private String dailyTime;

    /** Ruta donde se generan/leen los dumps (puede ser absoluta o relativa). */
    private String dumpsDir;

    /** Alias de compatibilidad con frontend que usa dumpPath. */
    private String dumpPath;
}
