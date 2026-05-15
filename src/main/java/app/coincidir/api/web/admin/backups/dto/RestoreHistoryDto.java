package app.coincidir.api.web.admin.backups.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RestoreHistoryDto {
    private String startedAt;
    private String finishedAt;
    private String dumpFileName;
    private String status;
    private String message;
    private String performedBy;
}
