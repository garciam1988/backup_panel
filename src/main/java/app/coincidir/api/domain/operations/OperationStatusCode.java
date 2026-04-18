package app.coincidir.api.domain.operations;

/**
 * Estados de operación por servicio (instancia) dentro de un grupo.
 *
 * Aunque los estados se parametrizan por BD (ServiceOperationStatusDefinition),
 * estos códigos se mantienen como enum para validación y compatibilidad.
 */
public enum OperationStatusCode {
    PENDIENTE,
    SOLICITADO,
    RESERVADO,
    SENADO,
    PAGADO,
    EMITIDO
}
