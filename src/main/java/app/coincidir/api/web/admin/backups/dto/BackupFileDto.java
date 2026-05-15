package app.coincidir.api.web.admin.backups.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BackupFileDto {
    private String fileName;
    private String filePath;
    private long sizeBytes;
    private String createdAt;
    private String trigger;
    private String status;
    private String message;
}
