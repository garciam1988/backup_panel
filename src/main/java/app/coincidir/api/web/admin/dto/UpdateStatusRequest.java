package app.coincidir.api.web.admin.dto;
import app.coincidir.api.domain.GroupStatus;

public record UpdateStatusRequest(GroupStatus status) {}