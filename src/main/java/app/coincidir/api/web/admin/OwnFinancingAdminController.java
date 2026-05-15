package app.coincidir.api.web.admin;

import app.coincidir.api.web.admin.dto.InstallmentCollectionRowDto;
import app.coincidir.api.web.admin.dto.InstallmentCollectionUpdateRequest;
import app.coincidir.api.web.admin.dto.PendingOwnFinancingInstallmentRowDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/own-financing")
@RequiredArgsConstructor
public class OwnFinancingAdminController {

    private final OwnFinancingAdminService service;

    @GetMapping("/pending-installments")
    public List<PendingOwnFinancingInstallmentRowDto> pendingInstallments() {
        return service.listPendingInstallments();
    }

    @GetMapping("/installments/collections")
    public List<InstallmentCollectionRowDto> installmentsCollections() {
        return service.listInstallmentsCollections();
    }

    @PostMapping("/installments/collections/notified")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markNotified(@RequestBody(required = false) InstallmentCollectionUpdateRequest body) {
        if (body == null) return;
        service.markInstallmentNotified(body.groupId(), body.memberId(), body.installmentNumber());
    }

    @PostMapping("/installments/collections/collected-sc")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markCollectedSc(@RequestBody(required = false) InstallmentCollectionUpdateRequest body) {
        if (body == null) return;
        service.markInstallmentCollectedSc(body.groupId(), body.memberId(), body.installmentNumber());
    }
}
