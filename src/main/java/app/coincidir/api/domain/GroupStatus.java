package app.coincidir.api.domain;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum GroupStatus {
    NEW,        // recién creado por el motor
    OPEN,       // visible para ventas
    NEGOTIATION,// en negociación
    EN_COTIZACION,   // en cotización (estado inicial de operación individual)
    EN_CONCILIACION, // en conciliación
    CONCILIADO, // pagos conciliados, operación aún no confirmada
    EN_OPERACIONES_SC, // operación confirmada, pagos aún sin conciliar
    EN_OPERACIONES, // en operaciones
    PENDIENTE_CONCILIACION, // pendiente de conciliación
    CLOSED,     // grupo cerrado (listo para viajar)
    FINALIZED,  // viaje finalizado (automático al pasar la fecha de regreso)
    PAID,       // pagos completos (si se usa)
    FORMED,
    NOTIFIED,
    CANCELLED   // descartado

    ;

    /**
     * Acepta aliases usados por el frontend (por compatibilidad) al deserializar JSON.
     */
    @JsonCreator
    public static GroupStatus fromJson(String value) {
        if (value == null) return null;
        String v = value.trim().toUpperCase();

        // Aliases (frontend) por compatibilidad
        if (v.equals("IN_QUOTATION") || v.equals("EN_COTIZACION")) {
            return EN_COTIZACION;
        }

        if (v.equals("IN_CONCILIATION") || v.equals("IN_CONCILIACON") || v.equals("EN_CONCILIACION")) {
            return EN_CONCILIACION;
        }

        if (v.equals("CONCILIATED") || v.equals("CONCILIADO")) {
            return CONCILIADO;
        }

        if (v.equals("IN_OPERATIONS_S_C") || v.equals("IN_OPERATIONS_NO_CONCILIATION")
                || v.equals("EN_OPERACIONES_S/C") || v.equals("EN_OPERACIONES_SC")) {
            return EN_OPERACIONES_SC;
        }

        if (v.equals("IN_OPERATIONS") || v.equals("EN_OPERATIONS") || v.equals("OPERATIONS") || v.equals("EN_OPERACIONES")) {
            return EN_OPERACIONES;
        }

        if (v.equals("PENDIENTE_DE_CONCILIACION") || v.equals("PENDING_CONCILIATION")) {
            return PENDIENTE_CONCILIACION;
        }

        return GroupStatus.valueOf(v);
    }
}
