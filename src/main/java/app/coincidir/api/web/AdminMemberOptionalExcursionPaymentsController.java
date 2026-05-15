package app.coincidir.api.web;

import app.coincidir.api.domain.MemberOptionalServiceCode;
import app.coincidir.api.domain.MemberOptionalServiceMenuItem;
import app.coincidir.api.domain.payment.OptionalServicePaymentPlan;
import app.coincidir.api.domain.payment.OptionalServicePaymentRecord;
import app.coincidir.api.repository.MemberOptionalExcursionServiceRepository;
import app.coincidir.api.repository.MemberOptionalServiceMenuItemRepository;
import app.coincidir.api.repository.OptionalServicePaymentPlanRepository;
import app.coincidir.api.repository.OptionalServicePaymentRecordRepository;
import app.coincidir.api.service.OperationsPaymentsService;
import app.coincidir.api.web.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.springframework.http.HttpStatus.*;

@RestController
@RequestMapping("/api/admin/groups/{groupId}/members/{memberId}/optional-service-items/{itemId}/excursions/payments")
@RequiredArgsConstructor
public class AdminMemberOptionalExcursionPaymentsController {

    private final OperationsPaymentsService operationsPaymentsService;
    private final MemberOptionalServiceMenuItemRepository optionalMenuItemRepo;
    private final MemberOptionalExcursionServiceRepository memberOptionalExcursionRepo;
    private final OptionalServicePaymentPlanRepository optionalPlanRepo;
    private final OptionalServicePaymentRecordRepository optionalRecordRepo;

    @GetMapping
    public List<OptionalExcursionPaymentRecordDto> list(
            @PathVariable Long groupId,
            @PathVariable Long memberId,
            @PathVariable Long itemId
    ) {
        MemberOptionalServiceMenuItem item = getExcursionMenuItemOrThrow(groupId, memberId, itemId);

        OptionalServicePaymentPlan plan = optionalPlanRepo.findByMenuItemId(item.getId()).orElse(null);
        if (plan == null || plan.getId() == null) return List.of();

        List<OptionalServicePaymentRecord> records = optionalRecordRepo.findByPlanIdOrderByPaymentDateAscIdAsc(plan.getId());
        if (records == null || records.isEmpty()) return List.of();

        List<OptionalExcursionPaymentRecordDto> out = new ArrayList<>();
        for (OptionalServicePaymentRecord r : records) {
            OptionalExcursionPaymentRecordDto dto = OptionalExcursionPaymentRecordDto.fromEntity(r);
            if (dto != null) out.add(dto);
        }
        return out;
    }

    @PostMapping
    public OptionalExcursionPaymentRecordDto create(
            @PathVariable Long groupId,
            @PathVariable Long memberId,
            @PathVariable Long itemId,
            @RequestBody CreateOptionalExcursionPaymentRequest body
    ) {
        MemberOptionalServiceMenuItem item = getExcursionMenuItemOrThrow(groupId, memberId, itemId);

        // Validaciones mínimas (el servicio valida nuevamente y devuelve mensaje con faltantes)
        BigDecimal amountUsd = body != null ? body.amountUsd() : null;
        String paymentMethod = body != null ? body.paymentMethod() : null;
        String paymentDate = body != null ? body.paymentDate() : null;

        if (amountUsd == null || amountUsd.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(BAD_REQUEST, "Importe inválido");
        }
        if (paymentMethod == null || paymentMethod.isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "Método de pago requerido");
        }
        if (paymentDate == null || paymentDate.isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "Fecha de pago requerida");
        }
        try {
            LocalDate.parse(paymentDate.trim());
        } catch (Exception ex) {
            throw new ResponseStatusException(BAD_REQUEST, "Fecha de pago inválida");
        }

        // Venta (USD): si aún no fue persistida (el usuario la cargó pero no guardó el servicio),
        // usamos el importe del pago como fallback para permitir registrar el pago.
        BigDecimal sale = null;
        var svcOpt = memberOptionalExcursionRepo.findByMenuItemIdAndMemberId(item.getId(), memberId);
        if (svcOpt.isPresent()) {
            sale = svcOpt.get().getSale();
            if (sale == null || sale.compareTo(BigDecimal.ZERO) <= 0) {
                // Fallback conservador: en Excursiones el pago es único y el importe coincide con el total a cobrar.
                // Persistimos la venta para evitar que el flujo dependa de "Guardar cambios" previo.
                sale = amountUsd;
                svcOpt.get().setSale(sale);
                memberOptionalExcursionRepo.save(svcOpt.get());
            }
        }
        if (sale == null || sale.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(BAD_REQUEST, "Primero debe cargar la Venta (USD) del servicio");
        }

        // Asegurar que exista el plan (forma TOTAL) para este opcional
        if (optionalPlanRepo.findByMenuItemId(item.getId()).isEmpty()) {
            operationsPaymentsService.upsertPlan(groupId, item.getId(), new UpsertServicePaymentPlanRequest(
                    "TOTAL",
                    sale,
                    "USD",
                    null
            ));
        }

        CreateServicePaymentRecordRequest req = new CreateServicePaymentRecordRequest(
                amountUsd,
                "USD",
                paymentDate.trim(),
                null,
                paymentMethod.trim(),
                null,
                body != null ? body.receiptLast4() : null,
                null,
                null,
                null,
                null,
                body != null ? body.bankId() : null,
                null,
                body != null ? body.cardLast4() : null,
                null,
                null
        );

        operationsPaymentsService.createRecord(groupId, item.getId(), req);

        OptionalServicePaymentPlan plan = optionalPlanRepo.findByMenuItemId(item.getId()).orElse(null);
        if (plan == null || plan.getId() == null) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "No se pudo registrar el pago");
        }
        List<OptionalServicePaymentRecord> records = optionalRecordRepo.findByPlanIdOrderByPaymentDateAscIdAsc(plan.getId());
        if (records == null || records.isEmpty()) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "No se pudo registrar el pago");
        }
        OptionalServicePaymentRecord last = records.get(records.size() - 1);
        return OptionalExcursionPaymentRecordDto.fromEntity(last);
    }

    @DeleteMapping("/{paymentId}")
    public void delete(
            @PathVariable Long groupId,
            @PathVariable Long memberId,
            @PathVariable Long itemId,
            @PathVariable Long paymentId
    ) {
        getExcursionMenuItemOrThrow(groupId, memberId, itemId);
        operationsPaymentsService.deleteRecord(groupId, itemId, paymentId);
    }

    @PostMapping("/{paymentId}/receipt")
    public void uploadReceipt(
            @PathVariable Long groupId,
            @PathVariable Long memberId,
            @PathVariable Long itemId,
            @PathVariable Long paymentId,
            @RequestParam(name = "file", required = false) MultipartFile file
    ) {
        getExcursionMenuItemOrThrow(groupId, memberId, itemId);
        operationsPaymentsService.uploadReceipt(groupId, itemId, paymentId, file);
    }

    @GetMapping("/{paymentId}/receipt")
    public ResponseEntity<Resource> downloadReceipt(
            @PathVariable Long groupId,
            @PathVariable Long memberId,
            @PathVariable Long itemId,
            @PathVariable Long paymentId
    ) {
        getExcursionMenuItemOrThrow(groupId, memberId, itemId);

        OperationsPaymentsService.ReceiptDownload dl = operationsPaymentsService.downloadReceipt(groupId, itemId, paymentId);

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

    private MemberOptionalServiceMenuItem getExcursionMenuItemOrThrow(Long groupId, Long memberId, Long itemId) {
        if (groupId == null || memberId == null || itemId == null) {
            throw new ResponseStatusException(BAD_REQUEST, "Parámetros inválidos");
        }

        MemberOptionalServiceMenuItem item = optionalMenuItemRepo.findByIdAndMemberId(itemId, memberId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Menu item no encontrado"));

        Long gid = (item.getMember() != null && item.getMember().getGroup() != null) ? item.getMember().getGroup().getId() : null;
        if (gid == null || !gid.equals(groupId)) {
            throw new ResponseStatusException(NOT_FOUND, "Menu item no encontrado");
        }
        if (item.getServiceCode() != MemberOptionalServiceCode.EXCURSIONES) {
            throw new ResponseStatusException(NOT_FOUND, "Menu item no encontrado");
        }
        return item;
    }
}
