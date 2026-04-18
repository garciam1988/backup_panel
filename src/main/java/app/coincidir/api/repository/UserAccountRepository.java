package app.coincidir.api.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import app.coincidir.api.domain.UserAccount;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {
    Optional<UserAccount> findByEmail(String email);

    // Útil para validaciones (evita duplicados por mayúsculas/minúsculas)
    Optional<UserAccount> findByEmailIgnoreCase(String email);
}
