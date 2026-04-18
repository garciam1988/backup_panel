package app.coincidir.api.web.admin;

import app.coincidir.api.domain.TravelRequest;
import app.coincidir.api.domain.MemberEmision;
import app.coincidir.api.repository.MemberEmisionRepository;
import app.coincidir.api.repository.TravelRequestRepository;
import app.coincidir.api.service.TravelRequestService;
import app.coincidir.api.web.admin.dto.CreateManualPassengerRequest;
import app.coincidir.api.web.admin.dto.CreateTravelRequestAirPaymentRequest;
import app.coincidir.api.web.admin.dto.TravelRequestAdminDto;
import app.coincidir.api.web.dto.AirServiceDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/requests")
@RequiredArgsConstructor
public class TravelRequestAdminController {

    private final GroupAdminService service;
    private final TravelRequestService travelRequestService;
    private final TravelRequestRepository travelRequestRepo;
private final MemberEmisionRepository memberEmisionRepo;
    private final ObjectMapper objectMapper;

    @GetMapping("/{id}")
    public TravelRequestAdminDto getOne(@PathVariable Long id) {
        return service.getRequestDetail(id);
    }

    

    @GetMapping("/unassigned")
    public java.util.List<app.coincidir.api.web.admin.dto.UnassignedPassengerDto> listUnassigned(
            @RequestParam(value = "mode", required = false) String mode
    ) {
        return service.listUnassignedRequests(mode);
    }
    @PutMapping("/{id}")
    public TravelRequestAdminDto update(
            @PathVariable Long id,
            @RequestBody TravelRequestAdminDto body
    ) {
        return service.updateRequestDetail(id, body);
    }

    // ============================================================
    // AEREOS - precarga por Pasajero
    // ============================================================

    @GetMapping("/{id}/air-service")
    public ResponseEntity<AirServiceDto> getPassengerAirService(@PathVariable Long id) {
        AirServiceDto dto = travelRequestService.getPassengerAirService(id);
        if (dto == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(dto);
    }

    @GetMapping("/{id}/air-services")
    public ResponseEntity<List<AirServiceDto>> getPassengerAirServices(@PathVariable Long id) {
        List<AirServiceDto> list = travelRequestService.getPassengerAirServices(id);
        return ResponseEntity.ok(list == null ? List.of() : list);
    }

    @PutMapping("/{id}/air-service")
    public ResponseEntity<AirServiceDto> upsertPassengerAirService(
            @PathVariable Long id,
            @RequestBody AirServiceDto body
    ) {
        return ResponseEntity.ok(travelRequestService.upsertPassengerAirService(id, body));
    }

    @PostMapping("/{id}/air-payments")
    public ResponseEntity<?> createPassengerAirPayment(
            @PathVariable Long id,
            @RequestBody CreateTravelRequestAirPaymentRequest body
    ) {
        Long expenseId = travelRequestService.createPassengerAirPayment(id, body);
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "expenseId", expenseId,
                "receiptLast4", body.receiptLast4()
        ));
    }

    /**
     * Carga manual de pasajero desde el panel de grupos (sin grupo asignado).
     * Crea la TravelRequest como si fuera el flujo de selección y registra datos de pago en deposit_*.
     */
    @PostMapping("/manual")
    public ResponseEntity<?> createManualPassenger(@RequestBody com.fasterxml.jackson.databind.JsonNode root) throws Exception {
        if (root == null || root.isNull()) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Body vacío");
        }

        // Parsear como árbol para tolerar keys alternativas / anidadas (según el frontend).
        CreateManualPassengerRequest body;
        String travelStartDateOverride;
        try {
            travelStartDateOverride = firstNonBlankDeep(root,
                    "travelStartDate",
                    "travel_start_date",
                    "travelDateStart",
                    "travel_date_start",
                    "travelStart",
                    "travelStartDateIso",
                    "travelStartDateISO",
                    "fechaInicioViaje",
                    "fecha_inicio_viaje",
                    "fechaInicioDeViaje",
                    "fecha_inicio_de_viaje",
                    "travelStartDateNew",
                    "travelDateStartNew",
                    "travelDateNew",
                    "travelDateInCourse",
                    "travelStartDateNueva",
                    "fechaViajeDesdeNueva",
                    "fechaInicio",
                    "fecha_inicio",
                    "startDate",
                    "start_date"
            );

            body = objectMapper.treeToValue(root, CreateManualPassengerRequest.class);
        } catch (Exception ex) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "No se pudo leer el JSON de la carga manual.",
                    ex
            );
        }

        final boolean isIndividualLoad = body.loadMode() != null && "INDIVIDUAL".equalsIgnoreCase(body.loadMode().trim());
        if (!isIndividualLoad) {
            // Para GRUPAL: sigue siendo obligatorio adjuntar comprobante.
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "El comprobante es obligatorio. Enviá la solicitud como multipart (data + file)."
            );
        }

        TravelRequest saved = createManualPassengerInternal(body, travelStartDateOverride);
        // En INDIVIDUAL no se registra pago desde esta pantalla.
        return ResponseEntity.ok(java.util.Map.of("id", saved.getId()));
    }


    /**
     * Variante multipart para permitir adjuntar comprobante al momento de crear el pasajero.
     *
     * - data: JSON (CreateManualPassengerRequest)
     * - file/receipt: MultipartFile (opcional)
     */
    @PostMapping(value = "/manual", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> createManualPassengerWithReceipt(
            @RequestPart("data") String data,
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestPart(value = "receipt", required = false) MultipartFile receipt
    ) throws Exception {
        MultipartFile f = (file != null && !file.isEmpty()) ? file : receipt;

        // Parsear JSON manualmente para evitar problemas de deserialización cuando el requestPart
        // llega con content-type inesperado (p.ej. application/octet-stream) y Spring no usa Jackson.
        CreateManualPassengerRequest body;
        String travelStartDateOverride = null;
        try {
            // Leemos como árbol para poder tolerar keys alternativas sin depender del content-type.
            final com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(data);
            travelStartDateOverride = firstNonBlankDeep(root,
                    "travelStartDate",
                    "travel_start_date",
                    "travelDateStart",
                    "travel_date_start",
                    "travelStart",
                    "travelStartDateIso",
                    "travelStartDateISO",
                    "fechaInicioViaje",
                    "fecha_inicio_viaje",
                    "fechaInicioDeViaje",
                    "fecha_inicio_de_viaje",
                    "travelStartDateNew",
                    "travelDateStartNew",
                    "travelDateNew",
                    "travelDateInCourse",
                    "travelStartDateNueva",
                    "fechaViajeDesdeNueva",
                    "fechaInicio",
                    "fecha_inicio",
                    "startDate",
                    "start_date"
            );

            body = objectMapper.treeToValue(root, CreateManualPassengerRequest.class);
        } catch (Exception ex) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "No se pudo leer el JSON de la carga manual.",
                    ex
            );
        }
        final boolean isIndividualLoad = body.loadMode() != null && "INDIVIDUAL".equalsIgnoreCase(body.loadMode().trim());
        if (!isIndividualLoad) {
            // En GRUPAL el comprobante + pago siguen siendo obligatorios.
            if (f == null || f.isEmpty()) {
                throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "El comprobante es obligatorio.");
            }
        }

        TravelRequest saved = createManualPassengerInternal(body, travelStartDateOverride);

        if (!isIndividualLoad) {
            travelRequestService.uploadDepositReceipt(saved.getId(), f);
            travelRequestService.materializeManualPassengerDepositPayment(saved.getId());
        } else {
            // En INDIVIDUAL el comprobante es opcional (por compat) y no se materializa pago.
            if (f != null && !f.isEmpty()) {
                travelRequestService.uploadDepositReceipt(saved.getId(), f);
            }
        }

        return ResponseEntity.ok(Map.of("id", saved.getId()));
    }

    @PostMapping(value = "/{id}/deposit-receipt", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadDepositReceipt(
            @PathVariable Long id,
            @RequestPart(value = "file", required = false) MultipartFile file
    ) {
        travelRequestService.uploadDepositReceipt(id, file);
        return ResponseEntity.ok().body(Map.of("ok", true));
    }

    @GetMapping("/{id}/deposit-receipt")
    public ResponseEntity<byte[]> downloadDepositReceipt(@PathVariable Long id) {
        TravelRequestService.ReceiptDownload r = travelRequestService.downloadDepositReceipt(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + safeFileName(r.fileName()) + "\"")
                .contentType(MediaType.parseMediaType(r.contentType()))
                .body(r.bytes());
    }

    private String safeFileName(String name) {
        if (name == null) return "comprobante";
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private LocalDate parseFlexibleLocalDate(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;

        // ISO local date: 2026-03-05
        try {
            if (s.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
                return LocalDate.parse(s);
            }
        } catch (Exception ignored) {
        }

        // dd/MM/yyyy or d/M/yyyy: 05/03/2026
        try {
            if (s.matches("^\\d{1,2}/\\d{1,2}/\\d{4}$")) {
                return LocalDate.parse(s, java.time.format.DateTimeFormatter.ofPattern("d/M/uuuu"));
            }
        } catch (Exception ignored) {
        }

        // ISO date-time with offset: 2026-03-05T00:00:00Z
        try {
            return java.time.OffsetDateTime.parse(s).toLocalDate();
        } catch (Exception ignored) {
        }

        // ISO instant: 2026-03-05T00:00:00.000Z
        try {
            return java.time.Instant.parse(s).atZone(java.time.ZoneOffset.UTC).toLocalDate();
        } catch (Exception ignored) {
        }

        // ISO local date-time (sin zona)
        try {
            return java.time.LocalDateTime.parse(s).toLocalDate();
        } catch (Exception ignored) {
        }

        return null;
    }

    private String firstNonBlank(com.fasterxml.jackson.databind.JsonNode root, String... keys) {
        if (root == null || keys == null) return null;
        for (String k : keys) {
            if (k == null || k.isBlank()) continue;
            com.fasterxml.jackson.databind.JsonNode n = root.get(k);
            if (n == null || n.isNull()) continue;
            String v;
            if (n.isTextual()) {
                v = n.asText();
            } else {
                v = n.toString();
            }
            if (v != null) {
                v = v.trim();
                // Si viene con comillas por un doble stringify, las limpiamos.
                if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
                    v = v.substring(1, v.length() - 1).trim();
                }
                if (!v.isBlank()) return v;
            }
        }
        return null;
    }

    private String firstNonBlankDeep(com.fasterxml.jackson.databind.JsonNode node, String... keys) {
        if (node == null) return null;

        String v = firstNonBlank(node, keys);
        if (v != null && !v.isBlank()) return v;

        if (node.isObject()) {
            java.util.Iterator<java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> it = node.fields();
            while (it.hasNext()) {
                java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> e = it.next();
                com.fasterxml.jackson.databind.JsonNode child = e.getValue();
                v = firstNonBlankDeep(child, keys);
                if (v != null && !v.isBlank()) return v;
            }
        } else if (node.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode child : node) {
                v = firstNonBlankDeep(child, keys);
                if (v != null && !v.isBlank()) return v;
            }
        }
        return null;
    }

    private Integer computeAgeFromBirthDate(LocalDate birthDate) {
        if (birthDate == null) return null;
        LocalDate today = LocalDate.now();
        if (birthDate.isAfter(today)) return null;
        return Period.between(birthDate, today).getYears();
    }

    private TravelRequest createManualPassengerInternal(CreateManualPassengerRequest body, String travelStartDateOverride) throws Exception {
        if (body == null) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Body vacío");
        }

        final boolean isIndividualLoad = body.loadMode() != null && "INDIVIDUAL".equalsIgnoreCase(body.loadMode().trim());

        String destination = body.destination() == null ? "" : body.destination().trim();
        String email = body.email() == null ? "" : body.email().trim();

        if (destination.isEmpty()) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenés que seleccionar un destino.");
        }
        if (email.isEmpty()) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenés que completar el email.");
        }
        // Validación de email:
        // - Si el mail no existe, permite avanzar.
        // - Si el mail existe y también existe asociado al mismo DNI, permite avanzar.
        // - Si el mail existe pero corresponde a otro DNI, bloquea.
        // - Si el mail es "-" (placeholder de menores), se omite la validación de unicidad.
        String emailNorm = email.toLowerCase();
        if (!emailNorm.equals("-")) {
            String docNumber = body.documentNumber() == null ? "" : body.documentNumber().trim().replaceAll("\\s+", "");
            boolean emailExists = travelRequestRepo.existsByEmailIgnoreCase(emailNorm);
            boolean sameDocumentForEmail = !docNumber.isBlank() && travelRequestRepo.existsByEmailIgnoreCaseAndDni(emailNorm, docNumber);

            if (emailExists && !sameDocumentForEmail) {
                throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "El mail ya existe para otro DNI en la base de datos.");
            }
        }
        String docNumber = body.documentNumber() == null ? "" : body.documentNumber().trim().replaceAll("\\s+", "");

        String datePresetId = body.datePresetId() == null ? "" : body.datePresetId().trim();
        String whenLabel = body.whenLabel() == null ? "" : body.whenLabel().trim();
        // Para carga INDIVIDUAL: se persiste en travel_request.travel_start_date.
        // Para carga GROUP/GRUPAL: NO se persiste en travel_request.travel_start_date (se usa para la "Fecha del viaje (desde)" del aéreo).
        // Se parsea de forma tolerante porque puede venir como ISO, dd/MM/yyyy o ISO date-time.
        String travelStartDateInputRaw = (travelStartDateOverride != null && !travelStartDateOverride.isBlank())
                ? travelStartDateOverride
                : body.travelStartDate();
        LocalDate travelStartDateInput = parseFlexibleLocalDate(travelStartDateInputRaw);
        LocalDate travelStartDate = isIndividualLoad ? travelStartDateInput : null;
        LocalDate groupTravelDateFromNewField = isIndividualLoad ? null : travelStartDateInput;

        // Servicio Aéreo (precarga por pasajero) - sólo aplica a carga GRUPAL.
        // En carga INDIVIDUAL hoy no se usa esta precarga.
        AirServiceDto airService = isIndividualLoad ? null : normalizeManualAir(body.airService());
        if (!isIndividualLoad) {
            if (datePresetId.isEmpty() || whenLabel.isEmpty()) {
                throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenés que seleccionar el mes de viaje.");
            }
        } else {
            // En carga individual el mes se deriva de la fecha inicio.
            if (travelStartDateInput == null) {
                throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenés que seleccionar la fecha inicio de viaje.");
            }
            final String[] months = new String[]{
                    "Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio",
                    "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre"
            };
            int mi = travelStartDateInput.getMonthValue() - 1;
            String monthName = (mi >= 0 && mi < months.length) ? months[mi] : null;
            String derived = (monthName == null) ? null : (monthName + " " + travelStartDateInput.getYear());
            datePresetId = derived;
            whenLabel = derived;
            travelStartDate = travelStartDateInput;
        }

        String firstName = body.firstName() == null ? "" : body.firstName().trim();
        String lastName = body.lastName() == null ? "" : body.lastName().trim();
        String fullName = (firstName + " " + lastName).trim();
        if (fullName.isEmpty()) {
            fullName = firstName.isEmpty() ? (lastName.isEmpty() ? null : lastName) : firstName;
        }

        String compPref = body.companionPreference() == null ? "ANY" : body.companionPreference().trim();
        if (compPref.isEmpty()) compPref = "ANY";

        // Documento / nacimiento
        String documentNumber = body.documentNumber() == null ? null : body.documentNumber().trim();
        if (documentNumber != null && documentNumber.isBlank()) documentNumber = null;
        LocalDate birthDate = body.birthDate();

        // Validación:
        // - Carga INDIVIDUAL: fecha de nacimiento obligatoria.
        // - Carga GRUPAL: opcional (si viene, validar >= 1 año).
        if (isIndividualLoad && birthDate == null) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenés que seleccionar la fecha de nacimiento.");
        }
        if (birthDate != null) {
            LocalDate oneYearAgo = LocalDate.now().minusYears(1);
            if (birthDate.isAfter(oneYearAgo)) {
                throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "La fecha de nacimiento no puede ser menor a 1 año.");
            }
        }

        Integer requestedAge = body.age();
        Integer birthDateAge = computeAgeFromBirthDate(birthDate);
        int effectiveAge = birthDateAge != null ? birthDateAge : (requestedAge == null ? -1 : requestedAge);

        if (isIndividualLoad) {
            if (effectiveAge < 1 || effectiveAge > 100) {
                throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenés que ingresar una edad válida (1 a 100).");
            }
        } else {
            if (effectiveAge < 18 || effectiveAge > 100) {
                throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenés que ingresar una edad válida (18 a 100).");
            }
        }

        final boolean passengerIsMinor = effectiveAge < 18;

        // Rangos oficiales de edad (como la App Coincidir)
        final int ageMin;
        final int ageMax;
        if (passengerIsMinor) {
            ageMin = 0;
            ageMax = 17;
        } else if (effectiveAge <= 25) {
            ageMin = 18;
            ageMax = 25;
        } else if (effectiveAge <= 40) {
            ageMin = 26;
            ageMax = 40;
        } else if (effectiveAge <= 55) {
            ageMin = 41;
            ageMax = 55;
        } else {
            ageMin = 56;
            ageMax = 100;
        }

        // Validación: en carga INDIVIDUAL el Nro de documento es obligatorio.
        String resolvedDocType = body.documentType() == null ? "DNI" : body.documentType().trim().toUpperCase();
        if (isIndividualLoad) {
            if (documentNumber == null || documentNumber.isBlank()) {
                throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenés que completar el Nro de documento.");
            }
            String dn = documentNumber.replaceAll("\\s+", "");
            // Validación numérica solo para DNI
            if ("DNI".equals(resolvedDocType) && !dn.matches("\\d{6,12}")) {
                throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "El Nro de documento (DNI) debe ser numérico (6 a 12 dígitos).");
            }
            documentNumber = dn;
        } else {
            // En carga GRUPAL, si se completa, normalizar a sólo dígitos.
            if (documentNumber != null) {
                String dn = documentNumber.replaceAll("\\s+", "");
                if (!dn.isBlank()) {
                    documentNumber = dn;
                }
            }
        }

        BigDecimal totalAmount = body.totalAmount();
        String planType = body.paymentPlanType() == null ? null : body.paymentPlanType().trim();
        String method = body.paymentMethod() == null ? null : body.paymentMethod().trim();
        String receiptLast4 = body.receiptLast4() == null ? null : body.receiptLast4().trim();
        String cardLast4 = body.cardLast4() == null ? null : body.cardLast4().trim();
        Long bankId = body.bankId();
        LocalDate paymentDate = body.paymentDate();

        // En la carga INDIVIDUAL no se cargan pagos desde la UI.
        // En GRUPAL/GRUPO se mantiene el flujo actual.
        BigDecimal depositAmountToSave = null;
        String depositPaymentMethodToSave = null;
        LocalDate depositDateToSave = null;
        String depositNotesToSave = null;

        final LocalDate travelStartLimit = travelStartDateInput; // "Fecha inicio de viaje" (o "Fecha del viaje desde" en grupales)

        final boolean isOwnFinancing = "OWN_FINANCING".equalsIgnoreCase(planType);
        List<CreateManualPassengerRequest.Installment> installments = body.installments();

        if (!isIndividualLoad) {
            if (totalAmount == null || totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenés que completar el total a cobrar.");
            }
            if (method == null || method.isBlank()) {
                throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenés que seleccionar el método de pago.");
            }
            if (receiptLast4 == null || !receiptLast4.matches("\\d{4}")) {
                throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenés que completar los últimos 4 dígitos del comprobante.");
            }

            boolean isCardMethod = "TARJETA_DEBITO".equalsIgnoreCase(method) || "TARJETA_CREDITO".equalsIgnoreCase(method);
            if (isCardMethod) {
                if (bankId == null) {
                    throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenés que seleccionar el banco.");
                }
                if (cardLast4 == null || !cardLast4.matches("\\d{4}")) {
                    throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenés que completar los últimos 4 dígitos de la tarjeta.");
                }
            }
            if (paymentDate == null) {
                throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenés que seleccionar la fecha de pago.");
            }

            // Cuota 1 (seña): no puede ser mayor a hoy
            if (paymentDate.isAfter(LocalDate.now())) {
                throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "La fecha de la cuota 1 no puede ser mayor a la fecha de hoy.");
            }

            // Nueva regla: ninguna cuota puede quedar con fecha mayor a la fecha inicio de viaje.
            if (travelStartLimit != null && paymentDate.isAfter(travelStartLimit)) {
                throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Las fechas de las cuotas no pueden ser mayor a la fecha inicio de viaje.");
            }

            if (isOwnFinancing) {
                if (installments == null || installments.isEmpty()) {
                    throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenés que definir la cantidad de cuotas y sus vencimientos.");
                }
                if (installments.size() > 10) {
                    throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "La cantidad de cuotas no puede ser mayor a 10.");
                }
                BigDecimal sum = BigDecimal.ZERO;
                for (int i = 0; i < installments.size(); i++) {
                    CreateManualPassengerRequest.Installment it = installments.get(i);
                    BigDecimal amt = it == null ? null : it.amount();
                    LocalDate due = it == null ? null : it.dueDate();
                    if (amt == null || amt.compareTo(BigDecimal.ZERO) <= 0) {
                        throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Cada cuota debe tener un importe válido.");
                    }
                    if (due == null) {
                        throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Cada cuota debe tener fecha de vencimiento.");
                    }

                    if (travelStartLimit != null && due.isAfter(travelStartLimit)) {
                        throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Las fechas de las cuotas no pueden ser mayor a la fecha inicio de viaje."
                        );
                    }
                    sum = sum.add(amt);
                }
                if (sum.compareTo(totalAmount) != 0) {
                    throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "La suma de las cuotas debe ser igual al total a cobrar.");
                }
            }

            // Persistimos los datos del pago en deposit_* (staging hasta que el request se asigne a un grupo)
            String depositPaymentMethod = (method != null && !method.isEmpty()) ? method : null;
            if (depositPaymentMethod != null && depositPaymentMethod.length() > 32) {
                depositPaymentMethod = depositPaymentMethod.substring(0, 32);
            }

            java.util.Map<String, Object> depositMeta = new java.util.LinkedHashMap<>();
            // Datos personales/doc (para poder mostrar en "Detalle" aunque TravelRequest no tenga columnas separadas)
            if (firstName != null && !firstName.isBlank()) depositMeta.put("firstName", firstName);
            if (lastName != null && !lastName.isBlank()) depositMeta.put("lastName", lastName);
            if (body.documentType() != null && !body.documentType().isBlank()) depositMeta.put("documentType", body.documentType().trim());
            if (documentNumber != null && !documentNumber.isBlank()) depositMeta.put("documentNumber", documentNumber);

            // Para carga GRUPAL, persistimos también la fecha de viaje (desde) como respaldo (UI "nueva")
            if (groupTravelDateFromNewField != null) {
                depositMeta.put("travelStartDateNew", groupTravelDateFromNewField.toString());
            }

            if (planType != null && !planType.isEmpty()) depositMeta.put("planType", planType);
            depositMeta.put("method", method);
            depositMeta.put("receiptLast4", receiptLast4);
            if (isCardMethod) {
                depositMeta.put("bankId", bankId);
                depositMeta.put("cardLast4", cardLast4);
            }
            depositMeta.put("paymentDate", paymentDate.toString());
            if (isOwnFinancing && installments != null && !installments.isEmpty()) {
                java.util.List<java.util.Map<String, Object>> inst = new java.util.ArrayList<>();
                for (CreateManualPassengerRequest.Installment it : installments) {
                    if (it == null) continue;
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("installmentNumber", it.installmentNumber());
                    m.put("amount", it.amount());
                    m.put("dueDate", it.dueDate() == null ? null : it.dueDate().toString());
                    inst.add(m);
                }
                depositMeta.put("installments", inst);
            }

            String depositNotes = null;
            if (!depositMeta.isEmpty()) {
                try {
                    depositNotes = new ObjectMapper().writeValueAsString(depositMeta);
                } catch (Exception ex) {
                    depositNotes = depositMeta.toString();
                }
            }

            depositAmountToSave = totalAmount;
            depositPaymentMethodToSave = depositPaymentMethod;
            depositDateToSave = paymentDate;
            depositNotesToSave = depositNotes;
        }
        // Para carga GRUPAL: persistimos la fecha de viaje seleccionada (nueva o en curso) en travel_request.travel_date_start
        // para poder reutilizarla al agregar nuevos pasajeros a la misma fecha.
        // Para carga GRUPAL: la fecha de viaje seleccionada (en curso o nueva) se resuelve en travelStartDateInput.
        LocalDate travelDateStartForGroup = isIndividualLoad ? null : travelStartDateInput;

        TravelRequest req = TravelRequest.builder()
                .destination(destination)
                .whenLabel(whenLabel)
                .datePresetId(datePresetId)
                .travelStartDate(travelStartDate)
                .travelEndDate(isIndividualLoad ? parseFlexibleLocalDate(body.travelEndDate()) : null)
                .travelDateStart(travelDateStartForGroup)
                .companionPreference(compPref)
                .ageMin(ageMin)
                .ageMax(ageMax)
                .paxMin(4)
                .paxMax(9)
                .smokeFree(true)
                .name(fullName)
                .email(emailNorm)
                .phone(body.phone() == null ? null : body.phone().trim())
                .phoneCountryCode(body.phoneCountryCode() == null ? null : body.phoneCountryCode().trim())
                .gender(body.gender() == null ? null : body.gender().trim())

                // Documento / nacimiento (opcionales)
                .dni(documentNumber)
                .documentType(resolvedDocType)
                .countryId(body.countryId())
                .country(body.country())
                .birthDate(birthDate)
                .documentExpiryDate(body.documentNoExpiry() != null && body.documentNoExpiry() ? null : body.documentExpiryDate())
                .documentNoExpiry(body.documentNoExpiry() != null && body.documentNoExpiry())
                .documentNotApplicable(body.documentNotApplicable() != null && body.documentNotApplicable())
                .travelersTotal(1)
                .travelersAdults(passengerIsMinor ? 0 : 1)
                .travelersMinors(passengerIsMinor ? 1 : 0)
                .depositAmount(depositAmountToSave)
                .depositPaymentMethod(depositPaymentMethodToSave)
                .depositDate(depositDateToSave)
                .depositNotes(depositNotesToSave)
                .build();

        TravelRequest saved = travelRequestService.saveWithoutNotify(req);

        // Si se cargaron datos del servicio (Aéreos), persistirlos para este pasajero.
        if (!isIndividualLoad && airService != null) {
            travelRequestService.upsertPassengerAirService(saved.getId(), airService);
        }

        // Registro histórico para emisión (Carga manual de pasajero)
        // En carga INDIVIDUAL no aplica (no hay datos de aéreos).
        if (!isIndividualLoad) {
            MemberEmision emision = new MemberEmision();
            emision.setRequestId(saved.getId());
            emision.setDestination(destination);
            emision.setTravelMonth(whenLabel);
            emision.setFullName(fullName);
            emision.setAge(effectiveAge);
            emision.setCompanionType(compPref);
            emision.setGender(body.gender() == null ? null : body.gender().trim());
            emision.setQuotedValue(body.quotedValue());
            if (body.quotedDate() != null) {
                emision.setQuotedAt(body.quotedDate().atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
            } else if (body.quotedValue() != null) {
                emision.setQuotedAt(java.time.Instant.now());
            }
            emision.setStatus("PENDIENTE");
            memberEmisionRepo.save(emision);
        }

        return saved;
    }

    // ============================================================
    // Helpers (Aéreos)
    // ============================================================

    private List<AirServiceDto> normalizeManualAirServices(List<AirServiceDto> raw) {
        if (raw == null || raw.isEmpty()) return java.util.Collections.emptyList();
        List<AirServiceDto> out = new ArrayList<>();
        for (AirServiceDto s : raw) {
            AirServiceDto n = normalizeManualAir(s);
            if (n != null) out.add(n);
        }
        return out;
    }

    private AirServiceDto normalizeManualAir(AirServiceDto airService) {
        if (airService == null) return null;

        boolean anyFilled =
                (airService.getOrigin() != null && !airService.getOrigin().isBlank())
                        || (airService.getDestination() != null && !airService.getDestination().isBlank())
                        || (airService.getAirline() != null && !airService.getAirline().isBlank())
                        || airService.getDepartureDate() != null
                        || airService.getDepartureTime() != null
                        || airService.getDepartureArrivalTime() != null
                        || airService.getReturnDate() != null
                        || airService.getReturnDepartureTime() != null
                        || airService.getReturnArrivalTime() != null;

        if (!anyFilled) {
            return null;
        }

        String tripType = (airService.getTripType() == null || airService.getTripType().isBlank())
                ? (airService.getReturnDate() != null ? "ROUND_TRIP" : "ONE_WAY")
                : airService.getTripType().trim().toUpperCase();

        if (!tripType.equals("ONE_WAY") && !tripType.equals("ROUND_TRIP")) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Tipo de viaje inválido. Use ONE_WAY o ROUND_TRIP."
            );
        }

        airService.setTripType(tripType);

        if (airService.getBaggageAllowance() == null || airService.getBaggageAllowance().isBlank()) {
            airService.setBaggageAllowance("6_KG");
        }

        if (airService.getOrigin() == null || airService.getOrigin().isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "El origen del servicio es obligatorio.");
        }
        if (airService.getDestination() == null || airService.getDestination().isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "El destino del servicio es obligatorio.");
        }
        if (airService.getAirline() == null || airService.getAirline().isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "La aerolínea del servicio es obligatoria.");
        }
        if (airService.getDepartureDate() == null || airService.getDepartureTime() == null) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Fecha y hora de salida (ida) son obligatorias en el servicio.");
        }
        if (tripType.equals("ROUND_TRIP")) {
            if (airService.getReturnDate() == null || airService.getReturnDepartureTime() == null) {
                throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "En ida y vuelta, fecha y hora de salida (regreso) son obligatorias en el servicio.");
            }
        }

        return airService;
    }

}
