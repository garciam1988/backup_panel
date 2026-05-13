package app.coincidir.api.marketing.service;

import app.coincidir.api.marketing.domain.LoyaltyCard;
import app.coincidir.api.marketing.domain.LoyaltyCustomer;
import app.coincidir.api.marketing.repository.LoyaltyCardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * LoyaltyCardService — Lectura de tarjetas. La ESCRITURA va siempre vía
 * LoyaltyTransactionService.record() para garantizar el audit log.
 *
 * Este service es delgado a propósito. Existe principalmente para que
 * controllers (admin, staff, público) no dependan directamente del
 * repository, y por si más adelante hay que agregar:
 *   - Cálculo de tier basado en lifetime_stamps
 *   - Cache de cards en Redis si la lectura se vuelve hot
 *   - Snapshots periódicos
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoyaltyCardService {

    private final LoyaltyCardRepository cardRepo;
    private final LoyaltyCustomerService customerService;

    public Optional<LoyaltyCard> findByCustomerId(Long customerId) {
        return cardRepo.findByCustomerId(customerId);
    }

    public LoyaltyCard getOrCreate(LoyaltyCustomer customer) {
        return customerService.ensureCard(customer);
    }
}
