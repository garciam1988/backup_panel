package app.coincidir.api.repository;

import app.coincidir.api.domain.payment.MemberPaymentInstallment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberPaymentInstallmentRepository extends JpaRepository<MemberPaymentInstallment, Long> {
}
