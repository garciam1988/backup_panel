package app.coincidir.api.web;

import app.coincidir.api.service.OperationsPaymentsService;
import app.coincidir.api.web.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/operations")
@RequiredArgsConstructor
public class OperationsPaymentsController {

    private final OperationsPaymentsService operationsPaymentsService;

    @GetMapping("/groups/{groupId}/service-items/{menuItemId}/payments")
    public ServicePaymentPlanDto getPayments(
            @PathVariable Long groupId,
            @PathVariable Long menuItemId
    ) {
        return operationsPaymentsService.getPayments(groupId, menuItemId);
    }

    @PutMapping("/groups/{groupId}/service-items/{menuItemId}/payments")
    public ServicePaymentPlanDto upsertPlan(
            @PathVariable Long groupId,
            @PathVariable Long menuItemId,
            @RequestBody @Valid UpsertServicePaymentPlanRequest body
    ) {
        return operationsPaymentsService.upsertPlan(groupId, menuItemId, body);
    }

    @PostMapping("/groups/{groupId}/service-items/{menuItemId}/payments/records")
    public ServicePaymentPlanDto createRecord(
            @PathVariable Long groupId,
            @PathVariable Long menuItemId,
            @RequestBody @Valid CreateServicePaymentRecordRequest body
    ) {
        return operationsPaymentsService.createRecord(groupId, menuItemId, body);
    }

    @PostMapping("/groups/{groupId}/service-items/{menuItemId}/payments/records/{recordId}/receipt")
    public ServicePaymentPlanDto uploadReceipt(
            @PathVariable Long groupId,
            @PathVariable Long menuItemId,
            @PathVariable Long recordId,
            @RequestParam(name = "file", required = false) MultipartFile file
    ) {
        return operationsPaymentsService.uploadReceipt(groupId, menuItemId, recordId, file);
    }

    @GetMapping("/groups/{groupId}/service-items/{menuItemId}/payments/records/{recordId}/receipt")
    public ResponseEntity<Resource> downloadReceipt(
            @PathVariable Long groupId,
            @PathVariable Long menuItemId,
            @PathVariable Long recordId
    ) {
        OperationsPaymentsService.ReceiptDownload dl = operationsPaymentsService.downloadReceipt(groupId, menuItemId, recordId);

        String ct = dl.contentType();
        if (ct == null || ct.isBlank()) ct = "application/octet-stream";
        String fn = dl.fileName();
        if (fn == null || fn.isBlank()) fn = "comprobante";

        String safeFileName = URLEncoder.encode(fn, StandardCharsets.UTF_8).replaceAll("\\+", "%20");

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(ct))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + safeFileName)
                .body(new ByteArrayResource(dl.bytes()));
    }

    @DeleteMapping("/groups/{groupId}/service-items/{menuItemId}/payments/records/{recordId}")
    public void deleteRecord(
            @PathVariable Long groupId,
            @PathVariable Long menuItemId,
            @PathVariable Long recordId
    ) {
        operationsPaymentsService.deleteRecord(groupId, menuItemId, recordId);
    }

    @GetMapping("/reservations/expiring")
    public List<ExpiringReservationDto> listExpiringReservations(
            @RequestParam(name = "withinHours", required = false, defaultValue = "48") int withinHours,
            @RequestParam(name = "status", required = false) String status
    ) {
        return operationsPaymentsService.findExpiringReservations(withinHours, status);
    }
}
