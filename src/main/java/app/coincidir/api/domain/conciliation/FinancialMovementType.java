package app.coincidir.api.domain.conciliation;

/**
 * Type of financial movement to be conciliated.
 *
 * Prepared to support future expense/outgoing movements.
 */
public enum FinancialMovementType {
    MEMBER_PAYMENT_RECORD,
    EXPENSE_RECORD
}
