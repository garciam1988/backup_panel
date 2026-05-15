package app.coincidir.api.marketing.service;

import app.coincidir.api.marketing.domain.LoyaltyCustomer;
import app.coincidir.api.marketing.domain.LoyaltyProgram;
import app.coincidir.api.marketing.domain.NotificationLog;
import app.coincidir.api.marketing.event.CustomerEnrolledEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Manda email de bienvenida al cliente cuando se enrola al programa de
 * fidelización. Solo se dispara si el cliente cargó email durante el alta.
 *
 * Diseño:
 *  - Listener async @TransactionalEventListener(AFTER_COMMIT): no bloquea la
 *    creación del cliente y solo dispara si el COMMIT del enroll tuvo éxito
 *    (evita mandar email a un cliente que después rollbackeó).
 *  - Resiliente: cualquier error (SMTP caído, email mal formado) se loguea
 *    pero no propaga. El cliente quedó enrolado igual; el email es un
 *    "nice to have".
 *  - Solo para enrolamientos NUEVOS (alreadyExisted=false). Si era un
 *    cliente que ya estaba, no le mandamos welcome.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnrollmentWelcomeEmailListener {

    private final NotificationService notificationService;
    private final LoyaltyProgramService programService;

    @Value("${marketing.pwa-base-url:}")
    private String pwaBaseUrl;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCustomerEnrolled(CustomerEnrolledEvent event) {
        LoyaltyCustomer c = event.customer();
        if (c == null) return;
        if (c.getEmail() == null || c.getEmail().isBlank()) {
            return; // No cargó email — nada que mandar.
        }
        try {
            LoyaltyProgram program = programService.getActiveProgram();
            String brandName = program != null && program.getName() != null
                ? program.getName() : "tu club de beneficios";
            String cardUrl = buildCardUrl(c.getCustomerHash());

            String greeting = "¡Bienvenido" + (c.getFirstName() != null ? ", " + c.getFirstName() : "") + "!";
            String stampsInfo = "";
            if (program != null && Boolean.TRUE.equals(program.getStampsEnabled())
                && program.getStampsRequired() != null && program.getStampsRequired() > 0) {
                String rewardText = program.getStampsRewardText() != null && !program.getStampsRewardText().isBlank()
                    ? program.getStampsRewardText() : "un premio";
                stampsInfo = "<p style=\"margin: 12px 0 0; color: #475569; font-size: 14px; line-height: 1.6;\">"
                    + "Cada vez que venís sumás estampillas. Cuando llegues a <b>"
                    + program.getStampsRequired() + "</b>, tenés <b>" + rewardText + "</b>."
                    + "</p>";
            }

            String body =
                "<p style=\"margin: 0; color: #0f172a; font-size: 18px; font-weight: 700;\">" + greeting + "</p>" +
                "<p style=\"margin: 14px 0 0; color: #334155; font-size: 15px; line-height: 1.6;\">" +
                "Ya estás registrado en el club de beneficios de <b>" + escapeHtml(brandName) + "</b>. " +
                "Te creamos tu tarjeta digital — abrila en el botón de abajo y guardala en tu celular." +
                "</p>" +
                stampsInfo;

            String subject = "Tu tarjeta de " + brandName + " está lista 🎉";

            notificationService.sendEmailWithTemplate(
                NotificationLog.SourceType.TRANSACTIONAL,
                "enrollment:" + c.getId(),
                c.getId(),
                subject,
                body,
                cardUrl,
                null  // sin cupón
            );
            log.info("Welcome email enviado a customer_id={} email={}", c.getId(), c.getEmail());
        } catch (Exception e) {
            // No propagar: el cliente quedó enrolado igual, el email es opcional.
            log.warn("No se pudo mandar welcome email a customer_id={}: {}", c.getId(), e.getMessage());
        }
    }

    private String buildCardUrl(String hash) {
        if (pwaBaseUrl == null || pwaBaseUrl.isBlank()) return "/c/" + hash;
        return pwaBaseUrl.replaceAll("/+$", "") + "/c/" + hash;
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
