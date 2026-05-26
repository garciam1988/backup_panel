package app.coincidir.api.errormonitor.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * DTO de listado del Error Monitor. Excluye campos pesados (detail,
 * breadcrumbsJson, recommendation) para que el response del listado no
 * llegue a varios MB con 50 errores grandes.
 *
 * El detalle completo se obtiene con GET /api/admin/error-monitor/{id}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorMonitorListDto {
    public Long    id;
    public Instant serverTs;
    public Instant clientTs;
    public String  level;
    public String  source;
    public String  errorType;
    public String  shortDesc;
    public String  status;
    public String  fingerprint;
    public String  userEmail;
    public String  userRole;
    public String  pathname;
    public String  app;
    public String  requestId;
    public Integer httpStatus;
    /** True si tiene stack/detail extenso disponible en el detalle. */
    public Boolean hasDetail;
    /** True si tiene breadcrumbs registrados (acción previa expandida). */
    public Boolean hasBreadcrumbs;
}
