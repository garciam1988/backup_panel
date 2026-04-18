package app.coincidir.api.auth.dto;

/**
 * Request para cambio de contraseña del usuario autenticado.
 */
public record ChangePasswordRequest(String currentPassword, String newPassword) {
}
