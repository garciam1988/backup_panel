package app.coincidir.api.marketing.event;

import app.coincidir.api.marketing.domain.LoyaltyCustomer;

/**
 * CustomerEnrolledEvent — Se publica cuando un cliente NUEVO se enrola al
 * programa de fidelidad (no en reactivaciones). El listener principal es
 * EarnBonusService que evalúa reglas con trigger="enrollment" y aplica el
 * bonus si corresponde.
 *
 * Usar eventos en vez de inyección directa evita ciclos potenciales entre
 * services y permite agregar más reacciones a futuro (campañas welcome,
 * cliente externo CRM, etc.) sin tocar LoyaltyCustomerService.
 */
public record CustomerEnrolledEvent(LoyaltyCustomer customer) {}
