package app.coincidir.api.web.dto.clientlogs;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class ClientLogIngestRequest {
    private String app;
    private String env;
    private String sessionId;
    private ClientLogUserDto user;
    private ClientLogDeviceDto device;
    private ClientLogContextDto context;
    private List<ClientLogEntryDto> logs;

    @Data
    public static class ClientLogUserDto {
        private Object id;
        private String email;
        private String role;
    }

    @Data
    public static class ClientLogDeviceDto {
        private String userAgent;
        private String platform;
    }

    @Data
    public static class ClientLogContextDto {
        private String url;
        private String pathname;
        private String screen;
    }

    @Data
    public static class ClientLogEntryDto {
        private String ts;
        private String level;
        private String category;
        private String message;
        private Map<String, Object> data;
        private String requestId;
        private List<Map<String, Object>> breadcrumbs;
    }
}
