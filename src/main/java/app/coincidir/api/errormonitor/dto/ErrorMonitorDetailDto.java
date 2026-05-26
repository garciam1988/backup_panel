package app.coincidir.api.errormonitor.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * DTO de detalle completo del error — usado por el modal del frontend.
 * Incluye TODO: stack, breadcrumbs (acción previa), recomendación, etc.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorMonitorDetailDto {
    public Long    id;
    public Instant serverTs;
    public Instant clientTs;
    public String  level;
    public String  source;
    public String  category;
    public String  errorType;
    public String  shortDesc;
    public String  message;
    public String  detail;
    public String  recommendation;
    public String  previousAction;
    public String  breadcrumbsJson;
    public String  dataJson;
    public String  status;
    public String  fingerprint;
    public Integer occurrenceCount;
    public Integer httpStatus;
    public String  exceptionClass;
    public String  resolvedBy;
    public Instant resolvedAt;
    public String  resolutionNote;

    public Long    userId;
    public String  userEmail;
    public String  userRole;

    public String  url;
    public String  pathname;
    public String  userAgent;
    public String  platform;
    public String  ip;
    public String  sessionId;
    public String  requestId;
    public String  app;
    public String  env;
}
