package app.coincidir.api.web.admin.expenses;

import app.coincidir.api.common.exception.BadRequestException;
import app.coincidir.api.domain.Prestador;
import app.coincidir.api.repository.PrestadorRepository;
import app.coincidir.api.web.admin.expenses.dto.ExpenseDto;
import app.coincidir.api.web.admin.expenses.dto.ExpenseUpsertRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/expenses")
@RequiredArgsConstructor
public class ExpensesAdminController {

    private final ExpensesAdminService service;
    private final PrestadorRepository prestadorRepo;

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Long providerId,
            @RequestParam(required = false) String paymentMethod,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            @RequestParam(required = false, defaultValue = "date_desc") String sort,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "200") int size
    ) {
        LocalDate fromDate = parseDate(from, "from");
        LocalDate toDate = parseDate(to, "to");

        Pageable pageable = PageRequest.of(Math.max(0, page - 1), clamp(size, 1, 500), parseSort(sort));
        Page<ExpenseDto> p = service.list(q, fromDate, toDate, type, category, providerId, paymentMethod, status, minAmount, maxAmount, pageable);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", p.getContent());
        out.put("total", p.getTotalElements());
        out.put("page", page);
        out.put("size", pageable.getPageSize());
        return ResponseEntity.ok(out);
    }

    @PostMapping
    public ExpenseDto create(@RequestBody ExpenseUpsertRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    public ExpenseDto update(@PathVariable long id, @RequestBody ExpenseUpsertRequest req) {
        return service.update(id, req);
    }

    @PostMapping(path = "/{id}/receipt", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ExpenseDto uploadReceipt(
            @PathVariable long id,
            @RequestParam(value = "file", required = false) MultipartFile file
    ) {
        return service.uploadReceipt(id, file);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Proveedores para el combo de gastos.
     * spent_panel soporta {id, nombre|descripcion, activo}.
     */
    @GetMapping("/providers")
    public List<Map<String, Object>> providers(
            @RequestParam(required = false, defaultValue = "false") boolean includeInactive
    ) {
        List<Prestador> rows = includeInactive
                ? prestadorRepo.findAll(Sort.by(Sort.Direction.ASC, "nombre"))
                : prestadorRepo.findByActivoTrueOrderByNombreAsc();

        // IMPORTANT:
        // Map.of() puede inferir un tipo intersección para V (ej: Serializable & Comparable & Constable)
        // cuando mezclamos Long/String/Boolean, y eso rompe la asignación a Map<String, Object>.
        // Forzamos explícitamente V=Object.
        return rows.stream().map(p -> Map.<String, Object>of(
                "id", p.getId(),
                "nombre", p.getNombre(),
                "descripcion", p.getNombre(),
                "activo", p.isActivo()
        )).toList();
    }

    private static LocalDate parseDate(String value, String field) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.trim());
        } catch (Exception ex) {
            throw new BadRequestException("Fecha inválida para " + field + ": " + value);
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static Sort parseSort(String sort) {
        String s = (sort == null ? "" : sort.trim()).toLowerCase();
        return switch (s) {
            case "date_asc" -> Sort.by(Sort.Direction.ASC, "date");
            case "amount_asc" -> Sort.by(Sort.Direction.ASC, "amount");
            case "amount_desc" -> Sort.by(Sort.Direction.DESC, "amount");
            case "date_desc", "" -> Sort.by(Sort.Direction.DESC, "date");
            default -> Sort.by(Sort.Direction.DESC, "date");
        };
    }
}
