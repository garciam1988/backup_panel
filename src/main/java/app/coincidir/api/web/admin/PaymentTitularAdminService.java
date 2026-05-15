package app.coincidir.api.web.admin;

import app.coincidir.api.domain.TravelGroup;
import app.coincidir.api.domain.TravelRequest;
import app.coincidir.api.repository.MemberPaymentRecordRepository;
import app.coincidir.api.repository.TravelGroupRepository;
import app.coincidir.api.repository.TravelRequestRepository;
import app.coincidir.api.web.admin.dto.PaymentTitularDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PaymentTitularAdminService {

    private static final String KEY_TITULAR_MEMBER_ID = "paymentTitularMemberId";

    private final TravelGroupRepository groupRepo;
    private final TravelRequestRepository requestRepo;
    private final MemberPaymentRecordRepository paymentRecordRepo;

    @Transactional(readOnly = true)
    public PaymentTitularDto get(Long groupId) {
        TravelGroup g = groupRepo.findById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Grupo no encontrado"));

        Long memberId = readTitularMemberId(g);
        boolean hasPayment = false;
        if (memberId != null) {
            try {
                hasPayment = paymentRecordRepo.existsByGroupIdAndMemberId(groupId, memberId);
            } catch (Exception ignored) {
            }
        }
        return new PaymentTitularDto(memberId, hasPayment);
    }

    @Transactional
    public PaymentTitularDto set(Long groupId, Long memberId) {
        if (memberId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "memberId es requerido");
        }

        TravelGroup g = groupRepo.findById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Grupo no encontrado"));

        TravelRequest r = requestRepo.findById(memberId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pasajero no encontrado"));

        if (r.getGroup() == null || r.getGroup().getId() == null || !r.getGroup().getId().equals(groupId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El pasajero no pertenece al grupo");
        }

        Map<String, String> prefs = g.getCommonPrefs();
        if (prefs == null) prefs = new LinkedHashMap<>();
        prefs.put(KEY_TITULAR_MEMBER_ID, String.valueOf(memberId));
        g.setCommonPrefs(prefs);
        groupRepo.save(g);

        return get(groupId);
    }

    @Transactional
    public PaymentTitularDto clear(Long groupId) {
        TravelGroup g = groupRepo.findById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Grupo no encontrado"));
        Map<String, String> prefs = g.getCommonPrefs();
        if (prefs != null) {
            prefs.remove(KEY_TITULAR_MEMBER_ID);
            g.setCommonPrefs(prefs);
            groupRepo.save(g);
        }
        return new PaymentTitularDto(null, false);
    }

    private Long readTitularMemberId(TravelGroup g) {
        if (g == null) return null;
        Map<String, String> prefs = g.getCommonPrefs();
        if (prefs == null) return null;
        String v = prefs.get(KEY_TITULAR_MEMBER_ID);
        if (v == null || v.isBlank()) return null;
        try {
            return Long.parseLong(v.trim());
        } catch (Exception ignored) {
            return null;
        }
    }
}
