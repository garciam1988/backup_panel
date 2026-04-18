package app.coincidir.api.web.admin.backups.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BackupSettingsDto {
    private boolean enabled;
    /** HH:mm */
    private String dailyTime;

    /** yyyy-MM-dd (uso interno para evitar re-ejecuciones el mismo día) */
    private String lastDailyRun;

    /** Ruta donde se generan/leen los dumps (puede ser absoluta o relativa). */
    private String dumpsDir;

    /** Compat: algunos frontends usan el nombre dumpPath. */
    @JsonProperty("dumpPath")
    public String getDumpPath() {
        return dumpsDir;
    }

    /** Compat: permitir setear dumpPath y que impacte en dumpsDir. */
    @JsonProperty("dumpPath")
    public void setDumpPath(String dumpPath) {
        this.dumpsDir = dumpPath;
    }
}
