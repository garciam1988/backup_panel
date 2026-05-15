package app.coincidir.api.web.admin;

import app.coincidir.api.web.admin.dto.CreateMemberPaymentRecordRequest;
import app.coincidir.api.web.admin.dto.MemberPaymentRecordDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/admin/groups/{groupId}/members/{memberId}/payments")
@RequiredArgsConstructor
public class MemberPaymentRecordsAdminController {

    private final MemberPaymentRecordsAdminService service;

    @GetMapping
    public List<MemberPaymentRecordDto> list(
            @PathVariable Long groupId,
            @PathVariable Long memberId
    ) {
        return service.list(groupId, memberId);
    }

    @PostMapping
    public MemberPaymentRecordDto create(
            @PathVariable Long groupId,
            @PathVariable Long memberId,
            @RequestBody CreateMemberPaymentRecordRequest body
    ) {
        return service.create(groupId, memberId, body);
    }

    @DeleteMapping("/{paymentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable Long groupId,
            @PathVariable Long memberId,
            @PathVariable Long paymentId
    ) {
        service.delete(groupId, memberId, paymentId);
    }

    @PostMapping(value = "/{paymentId}/receipt", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MemberPaymentRecordDto uploadReceipt(
            @PathVariable Long groupId,
            @PathVariable Long memberId,
            @PathVariable Long paymentId,
            @RequestPart(value = "file", required = false) MultipartFile file
    ) {
        return service.uploadReceipt(groupId, memberId, paymentId, file);
    }

    @GetMapping("/{paymentId}/receipt")
    public ResponseEntity<byte[]> downloadReceipt(
            @PathVariable Long groupId,
            @PathVariable Long memberId,
            @PathVariable Long paymentId
    ) {
        MemberPaymentRecordsAdminService.ReceiptDownload r = service.downloadReceipt(groupId, memberId, paymentId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + safeFileName(r.fileName()) + "\"")
                .contentType(MediaType.parseMediaType(r.contentType()))
                .body(r.bytes());
    }

    private String safeFileName(String name) {
        if (name == null) return "comprobante";
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
