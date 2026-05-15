package app.coincidir.api.marketing.service;

import app.coincidir.api.marketing.domain.StaffUser;
import app.coincidir.api.marketing.repository.StaffUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Gestión de StaffUser (mozos / cajeros / encargados).
 *
 * Operaciones desde /marketing (admin) — CRUD y reset de PIN.
 * Operación desde /staff (mozo) — autenticación con PIN.
 *
 * Seguridad del PIN:
 *   - PIN puede ser de 4-8 dígitos numéricos (validación a nivel UI).
 *   - Se hashea con BCrypt (mismo encoder que el resto del sistema).
 *   - El PIN en plano NUNCA se guarda en DB ni se logguea.
 *   - El admin que crea/edita un mozo ve el PIN una sola vez al crearlo
 *     (lo dice/anota); si lo pierde el mozo, hay que resetearlo.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaffUserService {

    private final StaffUserRepository repo;
    private final PasswordEncoder passwordEncoder;

    /** Lista todos (incluye inactivos) — para gestión en admin. */
    public List<StaffUser> listAll() {
        return repo.findAllByOrderByNameAsc();
    }

    public Optional<StaffUser> findById(Long id) {
        return repo.findById(id);
    }

    /**
     * Crea un nuevo mozo. Valida PIN básico (4-8 dígitos numéricos).
     * Si el PIN ya está en uso por otro mozo activo, lanza error
     * (porque sino el login sería ambiguo).
     */
    public StaffUser create(String name, String pin, String role) {
        validatePin(pin);
        String pinHash = passwordEncoder.encode(pin);

        // Verificar que no haya otro mozo activo con el mismo PIN.
        // Buscamos hasheando — pero como bcrypt es salted, no podemos
        // comparar hashes directamente. Iteramos y verificamos uno por uno.
        for (StaffUser existing : repo.findAll()) {
            if (Boolean.TRUE.equals(existing.getActive())
                && passwordEncoder.matches(pin, existing.getPinHash())) {
                throw new IllegalArgumentException(
                    "Ya hay un mozo activo con ese PIN. Elegí otro."
                );
            }
        }

        StaffUser s = StaffUser.builder()
            .name(name != null ? name.trim() : "")
            .pinHash(pinHash)
            .role(role != null && !role.isBlank() ? role.toUpperCase() : "MOZO")
            .active(true)
            .build();
        if (s.getName().isBlank()) {
            throw new IllegalArgumentException("El nombre es obligatorio.");
        }
        return repo.save(s);
    }

    /** Actualiza nombre y rol. NO toca el PIN (ver resetPin). */
    public StaffUser update(Long id, String name, String role, Boolean active) {
        StaffUser s = repo.findById(id).orElseThrow(
            () -> new IllegalArgumentException("Mozo no encontrado: " + id)
        );
        if (name != null && !name.isBlank()) s.setName(name.trim());
        if (role != null && !role.isBlank()) s.setRole(role.toUpperCase());
        if (active != null) s.setActive(active);
        return repo.save(s);
    }

    /**
     * Resetea el PIN. Se llama desde admin cuando el mozo lo olvidó.
     * Valida que el nuevo PIN no colisione con otro activo.
     */
    public StaffUser resetPin(Long id, String newPin) {
        validatePin(newPin);
        StaffUser s = repo.findById(id).orElseThrow(
            () -> new IllegalArgumentException("Mozo no encontrado: " + id)
        );

        // Anti-colisión: ningún OTRO mozo activo puede tener este PIN
        for (StaffUser existing : repo.findAll()) {
            if (!existing.getId().equals(id)
                && Boolean.TRUE.equals(existing.getActive())
                && passwordEncoder.matches(newPin, existing.getPinHash())) {
                throw new IllegalArgumentException(
                    "Otro mozo activo ya usa ese PIN. Elegí otro."
                );
            }
        }

        s.setPinHash(passwordEncoder.encode(newPin));
        return repo.save(s);
    }

    /** Soft delete: marca como inactivo. */
    public void deactivate(Long id) {
        StaffUser s = repo.findById(id).orElseThrow(
            () -> new IllegalArgumentException("Mozo no encontrado: " + id)
        );
        s.setActive(false);
        repo.save(s);
    }

    /** Hard delete — solo si nunca tuvo operaciones. */
    public void deleteHard(Long id) {
        repo.deleteById(id);
    }

    /**
     * Login: dado un PIN en plano, busca el StaffUser activo cuyo pinHash
     * matchea. Devuelve Optional vacío si no hay match.
     *
     * NOTA: por la naturaleza de bcrypt (salt único por hash), tenemos que
     * iterar y comparar uno por uno. Esto es O(N) sobre la cantidad de
     * mozos activos, pero N es muy chico (típicamente < 20 por local) así
     * que es aceptable. Si llegamos a > 100, conviene cachear los hashes
     * en memoria con un short-TTL.
     */
    public Optional<StaffUser> authenticate(String pin) {
        if (pin == null || pin.isBlank()) return Optional.empty();
        for (StaffUser s : repo.findAll()) {
            if (Boolean.TRUE.equals(s.getActive())
                && passwordEncoder.matches(pin, s.getPinHash())) {
                // Actualiza lastLogin
                s.setLastLoginAt(Instant.now());
                repo.save(s);
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    /**
     * Valida formato del PIN.
     *   - Solo dígitos.
     *   - Entre 4 y 8 caracteres.
     *   - No puede ser secuencia trivial: 1234, 0000, etc.
     */
    private void validatePin(String pin) {
        if (pin == null || pin.isBlank()) {
            throw new IllegalArgumentException("El PIN es obligatorio.");
        }
        if (!pin.matches("\\d{4,8}")) {
            throw new IllegalArgumentException(
                "El PIN debe ser 4 a 8 dígitos numéricos."
            );
        }
        // Bloquear PINs triviales (todos iguales o secuencia)
        if (pin.matches("(.)\\1+")) {
            throw new IllegalArgumentException(
                "El PIN no puede tener todos los dígitos iguales."
            );
        }
        if ("12345678".startsWith(pin) || "87654321".startsWith(pin)) {
            throw new IllegalArgumentException(
                "El PIN no puede ser una secuencia obvia (1234, 4321, etc.)."
            );
        }
    }
}
