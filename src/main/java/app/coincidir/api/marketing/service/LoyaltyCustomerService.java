package app.coincidir.api.marketing.service;

import app.coincidir.api.marketing.domain.LoyaltyCard;
import app.coincidir.api.marketing.domain.LoyaltyCustomer;
import app.coincidir.api.marketing.repository.LoyaltyCardRepository;
import app.coincidir.api.marketing.repository.LoyaltyCustomerRepository;
import app.coincidir.api.marketing.util.CustomerHashGenerator;
import app.coincidir.api.marketing.util.PhoneNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * LoyaltyCustomerService — Enrolamiento y gestión de clientes loyalty.
 *
 * Reglas clave:
 *   - Dedup por phone (único índice). enrollOrReactivate() es idempotente:
 *     si el phone ya existe, actualiza datos faltantes y devuelve el existente.
 *   - Si el cliente había sido borrado (deleted_at), lo reactiva.
 *   - Crea automáticamente la LoyaltyCard asociada (1-1).
 *   - El customerHash se genera UNA vez al enrolar. NUNCA cambia después
 *     (las URLs públicas dependen de él).
 *   - Si el cliente viene del bot tras una reserva, se guardan
 *     reservationTableSlug + reservationRecordId para trazabilidad.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoyaltyCustomerService {

    private final LoyaltyCustomerRepository customerRepo;
    private final LoyaltyCardRepository cardRepo;
    private final LoyaltyProgramService programService;

    @Transactional
    public EnrollResult enrollOrReactivate(EnrollInput in) {
        String normalizedPhone = PhoneNormalizer.normalize(in.phone());
        if (normalizedPhone == null) {
            throw new IllegalArgumentException("Teléfono inválido");
        }
        if (in.firstName() == null || in.firstName().isBlank()) {
            throw new IllegalArgumentException("Nombre es requerido");
        }

        Optional<LoyaltyCustomer> existing = customerRepo.findByPhone(normalizedPhone);
        if (existing.isPresent()) {
            LoyaltyCustomer c = existing.get();
            boolean updated = mergeIfMissing(c, in);
            if (c.getDeletedAt() != null) {
                c.setDeletedAt(null);
                c.setActive(true);
                updated = true;
            }
            if (updated) customerRepo.save(c);
            return new EnrollResult(c, ensureCard(c), true);
        }

        // Nuevo cliente
        LoyaltyCustomer c = new LoyaltyCustomer();
        c.setCustomerHash(generateUniqueHash());
        c.setPhone(normalizedPhone);
        c.setFirstName(in.firstName().trim());
        c.setLastName(in.lastName() != null ? in.lastName().trim() : null);
        c.setEmail(in.email() != null ? in.email().trim().toLowerCase() : null);
        c.setBirthDate(in.birthDate());
        c.setEnrolledSource(in.source());
        c.setEnrolledBranch(in.branchId());
        c.setReservationTableSlug(in.reservationTableSlug());
        c.setReservationRecordId(in.reservationRecordId());
        c.setLastActivityAt(Instant.now());

        LoyaltyCustomer saved = customerRepo.save(c);
        LoyaltyCard card = ensureCard(saved);
        log.info("Enrolado nuevo loyalty_customer id={} phone={} source={}",
            saved.getId(), saved.getPhone(), saved.getEnrolledSource());
        return new EnrollResult(saved, card, false);
    }

    private boolean mergeIfMissing(LoyaltyCustomer c, EnrollInput in) {
        boolean updated = false;
        if ((c.getFirstName() == null || c.getFirstName().isBlank()) && in.firstName() != null) {
            c.setFirstName(in.firstName().trim()); updated = true;
        }
        if ((c.getLastName() == null || c.getLastName().isBlank()) && in.lastName() != null) {
            c.setLastName(in.lastName().trim()); updated = true;
        }
        if ((c.getEmail() == null || c.getEmail().isBlank()) && in.email() != null) {
            c.setEmail(in.email().trim().toLowerCase()); updated = true;
        }
        if (c.getBirthDate() == null && in.birthDate() != null) {
            c.setBirthDate(in.birthDate()); updated = true;
        }
        return updated;
    }

    /** Garantiza que el cliente tenga su LoyaltyCard. Si no, la crea. */
    @Transactional
    public LoyaltyCard ensureCard(LoyaltyCustomer customer) {
        return cardRepo.findByCustomerId(customer.getId()).orElseGet(() -> {
            LoyaltyCard card = new LoyaltyCard();
            card.setCustomerId(customer.getId());
            card.setProgramId(programService.getActiveProgram().getId());
            card.setCurrentStamps(0);
            card.setCurrentPoints(0);
            card.setCashbackBalance(BigDecimal.ZERO);
            card.setLifetimeStamps(0);
            card.setLifetimePoints(0);
            card.setLifetimeCashback(BigDecimal.ZERO);
            return cardRepo.save(card);
        });
    }

    private String generateUniqueHash() {
        for (int i = 0; i < 5; i++) {
            String hash = CustomerHashGenerator.newCustomerHash();
            if (customerRepo.findByCustomerHash(hash).isEmpty()) return hash;
        }
        throw new IllegalStateException("No se pudo generar customerHash único (5 colisiones)");
    }

    public Optional<LoyaltyCustomer> findByHash(String hash) {
        return customerRepo.findByCustomerHash(hash);
    }

    public Optional<LoyaltyCustomer> findById(Long id) {
        return customerRepo.findById(id);
    }

    public Optional<LoyaltyCustomer> findByPhone(String phone) {
        String n = PhoneNormalizer.normalize(phone);
        return n == null ? Optional.empty() : customerRepo.findByPhone(n);
    }

    public Page<LoyaltyCustomer> list(String q, Pageable pageable) {
        if (q != null && !q.isBlank()) return customerRepo.search(q.trim(), pageable);
        return customerRepo.findByDeletedAtIsNullOrderByEnrolledAtDesc(pageable);
    }

    @Transactional
    public LoyaltyCustomer touchActivity(Long customerId) {
        LoyaltyCustomer c = customerRepo.findById(customerId).orElseThrow();
        c.setLastActivityAt(Instant.now());
        c.setTotalVisits(c.getTotalVisits() + 1);
        return customerRepo.save(c);
    }

    @Transactional
    public LoyaltyCustomer updateCommunicationPrefs(String hash, Boolean wpp, Boolean email, Boolean push) {
        LoyaltyCustomer c = customerRepo.findByCustomerHash(hash)
            .orElseThrow(() -> new IllegalArgumentException("Cliente no encontrado"));
        if (wpp != null) c.setAcceptsWhatsapp(wpp);
        if (email != null) c.setAcceptsEmail(email);
        if (push != null) c.setAcceptsPush(push);
        return customerRepo.save(c);
    }

    @Transactional
    public void saveWebPushSubscription(String hash, String subscriptionJson) {
        LoyaltyCustomer c = customerRepo.findByCustomerHash(hash)
            .orElseThrow(() -> new IllegalArgumentException("Cliente no encontrado"));
        c.setWebPushSubscription(subscriptionJson);
        customerRepo.save(c);
    }

    // ── DTOs internos ─────────────────────────────────────────────────────

    public record EnrollInput(
        String phone,
        String firstName,
        String lastName,
        String email,
        LocalDate birthDate,
        String branchId,
        String source,
        String reservationTableSlug,
        Long reservationRecordId
    ) {}

    public record EnrollResult(
        LoyaltyCustomer customer,
        LoyaltyCard card,
        boolean alreadyExisted
    ) {}
}
