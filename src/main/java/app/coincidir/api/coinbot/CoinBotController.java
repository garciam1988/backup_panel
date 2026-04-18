package app.coincidir.api.coinbot;

import app.coincidir.api.domain.*;
import app.coincidir.api.domain.payment.*;
import app.coincidir.api.repository.*;
import app.coincidir.api.security.JwtService;
import app.coincidir.api.service.GroupServiceMenuService;
import app.coincidir.api.web.dto.GroupServiceMenuDto;
import jakarta.mail.internet.MimeMessage;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * CoinBotController — API completa para el bot de post venta CoinBot.
 *
 * Endpoints:
 *   GET /api/coinbot/group/{groupId}              → info del grupo + lista de pasajeros
 *   GET /api/coinbot/member/{memberId}            → datos completos del pasajero
 *   GET /api/coinbot/voucher-pdf                  → proxy PDF real desde admin panel (server-to-server)
 *   POST /api/coinbot/voucher-email               → proxy email con PDF adjunto desde admin panel
 *   POST /api/coinbot/send-document-email         → envía un HTML por email (itinerario, etc.)
 *
 * Ubicación: src/main/java/app/coincidir/api/coinbot/CoinBotController.java
 */
@Slf4j
@RestController
@RequestMapping("/api/coinbot")
@RequiredArgsConstructor
public class CoinBotController {

    // ── Repositories ─────────────────────────────────────────────────────────
    private final TravelGroupRepository              travelGroupRepo;
    private final TravelRequestRepository            travelRequestRepo;
    private final MemberPaymentPlanRepository        paymentPlanRepo;
    private final MemberPaymentRecordRepository      paymentRecordRepo;
    private final MemberAccommodationServiceRepository accommodationRepo;
    private final GroupAccommodationRoomRepository   accommodationRoomRepo;
    private final MemberAirServiceRepository         airRepo;
    private final MemberTransferServiceRepository    transferRepo;
    private final MemberDestinationTransferServiceRepository destTransferRepo;
    private final MemberFerryServiceRepository       ferryRepo;
    private final MemberOptionalExcursionServiceRepository   excursionRepo;
    private final MemberOptionalTravelAssistanceServiceRepository assistanceRepo;
    private final MemberOptionalLuggageServiceRepository     luggageRepo;
    private final JavaMailSender                     mailSender;
    private final GroupServiceMenuService            groupServiceMenuService;
    private final JwtService                         jwtService;
    private final UserAccountRepository              userAccountRepo;

    @Value("${coincidir.mail-from:${coincidir.sales-email:no-reply@coincidir.com}}")
    private String mailFrom;

    @Value("${coincidir.admin-panel-url:http://localhost:3002}")
    private String adminPanelUrl;

    /** Cache del JWT admin para llamadas server-to-server al admin panel. */
    private volatile String cachedAdminToken = null;
    private volatile long   adminTokenExpiry  = 0;

    /**
     * Genera (o reutiliza) un JWT admin válido para llamadas server-to-server al admin panel.
     * Usa JwtService directamente — no hace HTTP a sí mismo ni depende de credenciales configuradas.
     * Busca el primer usuario con rol ADMIN o SELLER en la base de datos.
     */
    private String getAdminToken() {
        if (cachedAdminToken != null && System.currentTimeMillis() < adminTokenExpiry) {
            log.debug("[CoinBot] admin token: usando caché");
            return cachedAdminToken;
        }
        log.info("[CoinBot] admin token: generando JWT admin interno");
        // Buscar cualquier usuario con rol admin/seller para generar el token
        UserAccount adminUser = userAccountRepo.findAll().stream()
                .filter(u -> u.getRole() != null &&
                        (u.getRole().toUpperCase().contains("ADMIN") ||
                                u.getRole().toUpperCase().contains("SELLER")))
                .findFirst()
                .orElseThrow(() -> {
                    log.error("[CoinBot] admin token: no se encontró ningún usuario admin en la BD");
                    return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                            "No hay usuarios admin disponibles para generar vouchers.");
                });
        cachedAdminToken = jwtService.generate(
                adminUser.getEmail(),
                java.util.Map.of("uid", adminUser.getId(), "role", adminUser.getRole())
        );
        adminTokenExpiry = System.currentTimeMillis() + 50L * 60 * 1000; // 50 min
        log.info("[CoinBot] admin token: JWT generado para usuario={} role={}", adminUser.getEmail(), adminUser.getRole());
        return cachedAdminToken;
    }

    /** Cliente HTTP reutilizable para llamadas al admin panel (server-to-server). */
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(java.net.http.HttpClient.Version.HTTP_1_1)
            .build();

    @PersistenceContext
    private EntityManager em;

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/coinbot/group/{groupId}/service-menu
    // Expone el menú de servicios del grupo sin requerir rol admin.
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping("/group/{groupId}/service-menu")
    public ResponseEntity<GroupServiceMenuDto> getServiceMenu(@PathVariable Long groupId) {
        log.info("[CoinBot] GET /group/{}/service-menu", groupId);
        GroupServiceMenuDto menu = groupServiceMenuService.getMenu(groupId);
        log.info("[CoinBot] GET /group/{}/service-menu — OK: {} item(s)",
                groupId, menu.getItems() != null ? menu.getItems().size() : 0);
        return ResponseEntity.ok(menu);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/coinbot/voucher-data?groupId=X&serviceMenuItemId=Y&recipientMemberId=Z
    // Proxy server-to-server: obtiene los DATOS JSON del voucher desde el admin panel.
    // El bot los recibe y genera el PDF capturando el HTML localmente (dom-to-image),
    // produciendo el mismo resultado visual que el botón "Descargar PDF" del admin.
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping("/voucher-data")
    public ResponseEntity<String> proxyVoucherData(
            @RequestParam Long groupId,
            @RequestParam Long serviceMenuItemId,
            @RequestParam(required = false) Long recipientMemberId) {

        log.info("[CoinBot] GET /voucher-data — groupId={}, serviceMenuItemId={}, recipientMemberId={}",
                groupId, serviceMenuItemId, recipientMemberId);
        StringBuilder params = new StringBuilder("?serviceMenuItemId=").append(serviceMenuItemId);
        if (recipientMemberId != null) params.append("&recipientMemberId=").append(recipientMemberId);
        final String adminUrl   = adminPanelUrl + "/api/admin/groups/" + groupId + "/vouchers" + params;
        final String adminToken = getAdminToken();

        // Reintento: algunos vouchers (ej. aéreos con muchos segmentos/pasajeros)
        // pueden tardar más o cortar la conexión en la primera llamada.
        final int MAX_ATTEMPTS = 2;
        Exception lastEx = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                log.info("[CoinBot] voucher-data → admin panel GET {} (intento {}/{})", adminUrl, attempt, MAX_ATTEMPTS);

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(adminUrl))
                        .timeout(Duration.ofSeconds(60))
                        .header("Accept", "application/json")
                        .header("Authorization", "Bearer " + adminToken)
                        .GET()
                        .build();

                HttpResponse<String> response = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
                log.info("[CoinBot] voucher-data ← admin panel status={} (intento {})", response.statusCode(), attempt);

                if (response.statusCode() != 200) {
                    log.error("[CoinBot] voucher-data — admin panel error {}: {}", response.statusCode(), response.body());
                    throw new ResponseStatusException(
                            HttpStatus.BAD_GATEWAY,
                            "Admin panel respondió " + response.statusCode() + ": " + response.body());
                }

                log.info("[CoinBot] voucher-data — OK, datos obtenidos para svc={} (intento {})", serviceMenuItemId, attempt);
                return ResponseEntity.ok()
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .body(response.body());

            } catch (ResponseStatusException rse) {
                throw rse;
            } catch (java.io.IOException ioEx) {
                // Errores de red/conexión (incluye ConnectException): reintentar si queda intento, sino fallar
                lastEx = ioEx;
                log.warn("[CoinBot] voucher-data — fallo de red intento {}/{}: {}: {}",
                        attempt, MAX_ATTEMPTS, ioEx.getClass().getSimpleName(), ioEx.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    try { Thread.sleep(500L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            } catch (Exception ex) {
                String cause = ex.getClass().getSimpleName() + ": " + ex.getMessage();
                log.error("[CoinBot] voucher-data — EXCEPCION REAL: {}", cause, ex);
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Error obteniendo datos del voucher: " + cause);
            }
        }
        String cause = lastEx != null ? (lastEx.getClass().getSimpleName() + ": " + lastEx.getMessage()) : "desconocido";
        log.error("[CoinBot] voucher-data — agotados {} intentos: {}", MAX_ATTEMPTS, cause);
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Error obteniendo datos del voucher: " + cause);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/coinbot/voucher-pdf (mantenido para email — usa PDFKit server-side)
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping("/voucher-pdf")
    public ResponseEntity<byte[]> proxyVoucherPdf(
            @RequestParam Long groupId,
            @RequestParam Long serviceMenuItemId,
            @RequestParam(required = false) Long recipientMemberId) {

        log.info("[CoinBot] GET /voucher-pdf — groupId={}, serviceMenuItemId={}, recipientMemberId={}",
                groupId, serviceMenuItemId, recipientMemberId);
        try {
            String bodyJson   = buildVoucherRequestJson(serviceMenuItemId, "PDF", recipientMemberId);
            String adminUrl   = adminPanelUrl + "/api/admin/groups/" + groupId + "/vouchers";
            String adminToken = getAdminToken();
            log.info("[CoinBot] voucher-pdf → admin panel POST {}", adminUrl);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(adminUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + adminToken)
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                    .build();

            HttpResponse<InputStream> response = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofInputStream());
            log.info("[CoinBot] voucher-pdf ← admin panel status={}", response.statusCode());

            if (response.statusCode() != 200) {
                String errBody = new String(response.body().readAllBytes());
                log.error("[CoinBot] voucher-pdf — admin panel error {}: {}", response.statusCode(), errBody);
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Admin panel respondió " + response.statusCode() + ": " + errBody);
            }

            byte[] pdfBytes = response.body().readAllBytes();
            String filename = "voucher-op" + groupId + "-svc" + serviceMenuItemId + ".pdf";
            log.info("[CoinBot] voucher-pdf — OK, {} bytes", pdfBytes.length);

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                    .header("Cache-Control", "no-store")
                    .body(pdfBytes);

        } catch (ResponseStatusException rse) {
            throw rse;
        } catch (Exception ex) {
            String cause = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            log.error("[CoinBot] voucher-pdf — EXCEPCION REAL: {}", cause, ex);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Error generando PDF: " + cause);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/coinbot/voucher-email
    // Proxy server-to-server: le pide al admin panel que envíe el PDF por email.
    // Body: { groupId, serviceMenuItemId, recipientMemberId }
    // ─────────────────────────────────────────────────────────────────────────
    @PostMapping("/voucher-email")
    public ResponseEntity<?> proxyVoucherEmail(
            @RequestBody VoucherEmailProxyRequest body) {

        if (body == null || body.groupId() == null || body.serviceMenuItemId() == null) {
            log.warn("[CoinBot] POST /voucher-email — parámetros incompletos: {}", body);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "groupId y serviceMenuItemId son requeridos");
        }
        log.info("[CoinBot] POST /voucher-email — groupId={}, serviceMenuItemId={}, recipientMemberId={}",
                body.groupId(), body.serviceMenuItemId(), body.recipientMemberId());
        try {
            String bodyJson   = buildVoucherRequestJson(body.serviceMenuItemId(), "EMAIL", body.recipientMemberId());
            String adminUrl   = adminPanelUrl + "/api/admin/groups/" + body.groupId() + "/vouchers";
            String adminToken = getAdminToken();
            log.info("[CoinBot] voucher-email → admin panel POST {}", adminUrl);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(adminUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + adminToken)
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());

            log.info("[CoinBot] voucher-email ← admin panel status={} body={}",
                    response.statusCode(), response.body());

            if (response.statusCode() != 200) {
                log.error("[CoinBot] voucher-email — admin panel error {}: {}",
                        response.statusCode(), response.body());
                throw new ResponseStatusException(
                        HttpStatus.valueOf(response.statusCode()),
                        "Admin panel: " + response.body());
            }

            log.info("[CoinBot] voucher-email — OK, email enviado por admin panel");
            return ResponseEntity.ok(java.util.Map.of("ok", true));

        } catch (ResponseStatusException rse) {
            throw rse;
        } catch (Exception ex) {
            log.error("[CoinBot] voucher-email — ERROR inesperado: {}", ex.getMessage(), ex);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "No se pudo enviar el email del voucher. Intentá de nuevo.");
        }
    }

    public record VoucherEmailProxyRequest(Long groupId, Long serviceMenuItemId, Long recipientMemberId) {}

    /** Arma el JSON body que espera el admin panel Next.js */
    private String buildVoucherRequestJson(Long serviceMenuItemId, String delivery, Long recipientMemberId) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"serviceMenuItemId\":").append(serviceMenuItemId);
        sb.append(",\"delivery\":\"").append(delivery).append("\"");
        if (recipientMemberId != null) {
            sb.append(",\"recipientMemberId\":").append(recipientMemberId);
        }
        sb.append("}");
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/coinbot/group/{groupId}
    // Devuelve info del grupo + lista de pasajeros para identificar al cliente.
    // groupId = travel_group.id = número de operación que ve el cliente
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping("/group/{groupId}")
    public ResponseEntity<GroupInfoResponse> getGroupInfo(@PathVariable Long groupId) {
        log.info("[CoinBot] GET /group/{} — buscando operación", groupId);
        return travelGroupRepo.findById(groupId).map(group -> {
            GroupInfoResponse r = new GroupInfoResponse();
            r.setGroupId(groupId);
            r.setDestino(group.getDestination());
            r.setFechaSalida(group.getTravelStartDate());
            r.setFechaRegreso(group.getTravelEndDate());
            r.setEstado(group.getStatus() != null ? group.getStatus().name() : null);
            r.setOperacionConfirmada(group.isOperationConfirmed());

            List<TravelRequest> members = em.createQuery(
                            "SELECT m FROM TravelRequest m WHERE m.group.id = :gid ORDER BY m.id ASC",
                            TravelRequest.class)
                    .setParameter("gid", groupId).getResultList();

            r.setPasajeros(members.stream().map(m -> {
                PasajeroResumen p = new PasajeroResumen();
                p.setMemberId(m.getId());
                p.setNombre(m.getName());
                p.setEmail(m.getEmail());
                return p;
            }).collect(Collectors.toList()));

            log.info("[CoinBot] GET /group/{} — OK: destino={}, {} pasajero(s)",
                    groupId, group.getDestination(), r.getPasajeros().size());
            return ResponseEntity.ok(r);
        }).orElseGet(() -> {
            log.warn("[CoinBot] GET /group/{} — NOT FOUND", groupId);
            return ResponseEntity.notFound().build();
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/coinbot/member/{memberId}
    // Devuelve TODOS los datos del pasajero: info personal + todos los servicios
    // + pagos completos con cuotas e historial.
    // memberId = travel_request.id
    // ─────────────────────────────────────────────────────────────────────────
    @GetMapping("/member/{memberId}")
    public ResponseEntity<MemberServicesResponse> getMemberServices(@PathVariable Long memberId) {
        log.info("[CoinBot] GET /member/{} — buscando pasajero", memberId);
        return travelRequestRepo.findById(memberId).map(member -> {
            MemberServicesResponse r = new MemberServicesResponse();
            r.setMemberId(memberId);

            // ── Info del grupo ────────────────────────────────────────────
            if (member.getGroup() != null) {
                TravelGroup g = member.getGroup();
                r.setGroupId(g.getId());
                r.setDestino(g.getDestination());
                r.setFechaSalida(g.getTravelStartDate());
                r.setFechaRegreso(g.getTravelEndDate());
                r.setEstadoGrupo(g.getStatus() != null ? g.getStatus().name() : null);
                r.setOperacionConfirmada(g.isOperationConfirmed());
            }

            // ── Info personal completa del pasajero ───────────────────────
            DatosPersonales datos = new DatosPersonales();
            datos.setNombre(member.getName());
            datos.setEmail(member.getEmail());
            datos.setTelefono(member.getPhone());
            datos.setDni(member.getDni());
            datos.setFechaNacimiento(member.getBirthDate());
            datos.setGenero(member.getGender());
            datos.setPais(member.getCountry());
            datos.setProvincia(member.getProvince());
            datos.setCiudad(member.getCity());
            datos.setLocalidad(member.getLocality());
            datos.setCodigoPostal(member.getPostalCode());
            datos.setViajerosTotales(member.getTravelersTotal());
            datos.setViajerosAdultos(member.getTravelersAdults());
            datos.setViajerosMenores(member.getTravelersMinors());
            datos.setHabitacionCompartida(member.getSharedRoom());
            datos.setAsistenciaViajero(member.getTravelAssistance());
            datos.setIncluirExcursiones(member.getIncludesTours());
            datos.setEstado(member.getStatus() != null ? member.getStatus().name() : null);
            datos.setCreadoEn(member.getCreatedAt());
            r.setPasajero(datos);

            // ── Pagos: plan + cuotas + historial de cobros ────────────────
            r.setPagos(buildPagos(memberId, member.getGroup() != null ? member.getGroup().getId() : null));

            // ── Alojamiento con habitaciones ──────────────────────────────
            r.setAlojamiento(buildAlojamiento(memberId));

            // ── Vuelo ─────────────────────────────────────────────────────
            airRepo.findAll().stream()
                    .filter(a -> a.getMember().getId().equals(memberId))
                    .findFirst()
                    .ifPresent(air -> r.setVuelo(buildVuelo(air)));

            // ── Traslado origen/destino (BA) ──────────────────────────────
            transferRepo.findAll().stream()
                    .filter(t -> t.getMember().getId().equals(memberId))
                    .findFirst()
                    .ifPresent(t -> r.setTrasladoBA(buildTrasladoBA(t)));

            // ── Traslado en destino ───────────────────────────────────────
            destTransferRepo.findAll().stream()
                    .filter(t -> t.getMember().getId().equals(memberId))
                    .findFirst()
                    .ifPresent(t -> r.setTrasladoDestino(buildTrasladoDestino(t)));

            // ── Ferry ─────────────────────────────────────────────────────
            ferryRepo.findAll().stream()
                    .filter(f -> f.getMember().getId().equals(memberId))
                    .findFirst()
                    .ifPresent(f -> r.setFerry(buildFerry(f)));

            // ── Excursiones opcionales ────────────────────────────────────
            r.setExcursiones(buildExcursiones(memberId));

            // ── Asistencia al viajero ─────────────────────────────────────
            assistanceRepo.findAll().stream()
                    .filter(a -> a.getMember().getId().equals(memberId))
                    .findFirst()
                    .ifPresent(a -> r.setAsistencia(buildAsistencia(a)));

            // ── Equipaje opcional ─────────────────────────────────────────
            r.setEquipaje(buildEquipaje(memberId));

            log.info("[CoinBot] GET /member/{} — OK: nombre='{}', groupId={}, vuelo={}, alojamiento={}, excursiones={}",
                    memberId, member.getName(), r.getGroupId(),
                    r.getVuelo() != null, r.getAlojamiento() != null,
                    r.getExcursiones() != null ? r.getExcursiones().size() : 0);
            return ResponseEntity.ok(r);
        }).orElseGet(() -> {
            log.warn("[CoinBot] GET /member/{} — NOT FOUND", memberId);
            return ResponseEntity.notFound().build();
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/coinbot/send-document-email
    // Envía el HTML de voucher/itinerario generado en el frontend por email.
    // Body: { email, subject, htmlBody }
    // ─────────────────────────────────────────────────────────────────────────
    @PostMapping("/send-document-email")
    public ResponseEntity<?> sendDocumentEmail(@RequestBody SendDocumentEmailRequest body) {
        if (body == null || body.email() == null || body.email().isBlank()) {
            log.warn("[CoinBot] POST /send-document-email — email de destino vacío o nulo");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email de destino requerido");
        }
        if (body.htmlBody() == null || body.htmlBody().isBlank()) {
            log.warn("[CoinBot] POST /send-document-email — htmlBody vacío para dest={}", body.email());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Contenido del documento requerido");
        }
        log.info("[CoinBot] POST /send-document-email — enviando a={}, asunto='{}'",
                body.email(), body.subject());
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            if (mailFrom != null && !mailFrom.isBlank()) helper.setFrom(mailFrom);
            helper.setTo(body.email().trim());
            helper.setSubject(body.subject() != null ? body.subject() : "Tu documento de viaje — Coincidir");
            helper.setText(body.htmlBody(), true);
            mailSender.send(message);
            log.info("[CoinBot] POST /send-document-email — OK, mail entregado a={}", body.email());
            return ResponseEntity.ok(java.util.Map.of("ok", true));
        } catch (Exception ex) {
            log.error("[CoinBot] POST /send-document-email — ERROR enviando a={}: {}", body.email(), ex.getMessage(), ex);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "No se pudo enviar el mail. Intentá de nuevo o descargalo desde el chat.");
        }
    }

    public record SendDocumentEmailRequest(String email, String subject, String htmlBody) {}

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/coinbot/send-pdf-email
    // Envía un PDF ya generado en el cliente como adjunto por email.
    // Body: { email, subject, pdfBase64, filename }
    // ─────────────────────────────────────────────────────────────────────────
    @PostMapping("/send-pdf-email")
    public ResponseEntity<?> sendPdfEmail(@RequestBody SendPdfEmailRequest body) {
        if (body == null || body.email() == null || body.email().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email de destino requerido");
        }
        if (body.pdfBase64() == null || body.pdfBase64().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PDF requerido");
        }
        log.info("[CoinBot] POST /send-pdf-email — enviando a={}, asunto='{}'", body.email(), body.subject());
        try {
            byte[] pdfBytes = java.util.Base64.getDecoder().decode(body.pdfBase64());
            String filename = (body.filename() != null && !body.filename().isBlank()) ? body.filename() : "voucher.pdf";
            String subject  = (body.subject()  != null && !body.subject().isBlank())  ? body.subject()  : "Tu voucher — YES Travel";

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            if (mailFrom != null && !mailFrom.isBlank()) helper.setFrom(mailFrom);
            helper.setTo(body.email().trim());
            helper.setSubject(subject);
            String html = (body.htmlBody() != null && !body.htmlBody().isBlank()) ? body.htmlBody() : "<p>Hola,</p><p>Te enviamos adjunto el voucher de tu servicio.</p><p>Ante cualquier consulta respondé este correo.</p><p><strong>YES Travel</strong></p>";
            helper.setText(html, true);
            helper.addAttachment(filename, new org.springframework.core.io.ByteArrayResource(pdfBytes), "application/pdf");
            mailSender.send(message);
            log.info("[CoinBot] POST /send-pdf-email — OK, mail entregado a={}", body.email());
            return ResponseEntity.ok(java.util.Map.of("ok", true));
        } catch (Exception ex) {
            log.error("[CoinBot] POST /send-pdf-email — ERROR: {}", ex.getMessage(), ex);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "No se pudo enviar el mail.");
        }
    }

    public record SendPdfEmailRequest(String email, String subject, String htmlBody, String pdfBase64, String filename) {}

    // ─────────────────────────────────────────────────────────────────────────

    private PagosDto buildPagos(Long memberId, Long groupId) {
        PagosDto dto = new PagosDto();

        // Plan de pagos
        var planOpt = groupId != null
                ? paymentPlanRepo.findOneWithInstallments(groupId, memberId)
                : paymentPlanRepo.findUngroupedWithInstallments(memberId);

        planOpt.ifPresent(plan -> {
            dto.setTipoPlan(plan.getPlanType() != null ? plan.getPlanType().name() : null);
            dto.setMetodoPago(plan.getOneTimeMethod() != null ? plan.getOneTimeMethod().name() : null);
            dto.setMontoTotal(plan.getTotalAmount());
            dto.setMoneda(plan.getCurrency());
            dto.setNotas(plan.getNotes());

            // Cuotas
            if (plan.getInstallments() != null) {
                List<CuotaDto> cuotas = plan.getInstallments().stream().map(inst -> {
                    CuotaDto c = new CuotaDto();
                    c.setNroCuota(inst.getInstallmentNumber());
                    c.setMonto(inst.getAmount());
                    c.setFechaVencimiento(inst.getDueDate());
                    c.setFechaPago(inst.getPaidDate());
                    c.setEstado(inst.getStatus() != null ? inst.getStatus().name() : null);
                    return c;
                }).collect(Collectors.toList());
                dto.setCuotas(cuotas);

                // Calcular totales
                BigDecimal pagado = cuotas.stream()
                        .filter(c -> "PAID".equals(c.getEstado()))
                        .map(c -> c.getMonto() != null ? c.getMonto() : BigDecimal.ZERO)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                dto.setMontoPagado(pagado);
                dto.setMontoPendiente(plan.getTotalAmount() != null
                        ? plan.getTotalAmount().subtract(pagado) : null);

                long cuotasPendientes = cuotas.stream()
                        .filter(c -> "PLANNED".equals(c.getEstado())).count();
                dto.setCuotasPendientes((int) cuotasPendientes);
                dto.setPagoCompleto(cuotasPendientes == 0 && !cuotas.isEmpty());
            }
        });

        // Historial de cobros registrados
        List<MemberPaymentRecord> records = groupId != null
                ? paymentRecordRepo.findAllByGroupIdAndMemberIdOrderByPaymentDateDescIdDesc(groupId, memberId)
                : paymentRecordRepo.findAllByGroupIdIsNullAndMemberIdOrderByPaymentDateDescIdDesc(memberId);

        List<CobroRegistradoDto> cobros = records.stream().map(rec -> {
            CobroRegistradoDto c = new CobroRegistradoDto();
            c.setId(rec.getId());
            c.setNroCuota(rec.getInstallmentNumber());
            c.setMonto(rec.getAmount());
            c.setMoneda(rec.getCurrency());
            c.setFechaPago(rec.getPaymentDate());
            c.setUltimos4(rec.getReceiptLast4());
            c.setTieneComprobante(rec.getReceiptBlob() != null);
            return c;
        }).collect(Collectors.toList());
        dto.setCobrosRegistrados(cobros);

        return dto;
    }

    private AlojamientoDto buildAlojamiento(Long memberId) {
        try {
            MemberAccommodationService acc = em.createQuery(
                            "SELECT a FROM MemberAccommodationService a WHERE a.member.id = :mid",
                            MemberAccommodationService.class)
                    .setParameter("mid", memberId).setMaxResults(1).getSingleResult();

            AlojamientoDto dto = new AlojamientoDto();
            dto.setNombre(acc.getName());
            dto.setCheckInFecha(acc.getCheckInDate());
            dto.setCheckInHorario(acc.getCheckInTime());
            dto.setCheckOutFecha(acc.getCheckOutDate());
            dto.setCheckOutHorario(acc.getCheckOutTime());
            dto.setRegimen(acc.getRegimen() != null ? acc.getRegimen().name() : null);
            dto.setRegimenDescripcion(regimenLabel(acc.getRegimen()));
            dto.setCiudad(acc.getCity());
            dto.setPais(acc.getCountry());
            dto.setTipoContrato(acc.getContractType() != null ? acc.getContractType().name() : null);
            dto.setNombreTercero(acc.getThirdPartyName());
            dto.setCodigoReserva(acc.getReservationCode());

            // Habitaciones del grupo
            if (acc.getMenuItem().getId() != null) {
                // Buscar el GroupAccommodationService por menuItemId
                try {
                    GroupAccommodationService groupAcc = em.createQuery(
                                    "SELECT g FROM GroupAccommodationService g WHERE g.menuItem.id = :mid",
                                    GroupAccommodationService.class)
                            .setParameter("mid", acc.getMenuItem().getId())
                            .setMaxResults(1).getSingleResult();

                    List<GroupAccommodationRoom> rooms =
                            accommodationRoomRepo.findByAccommodationService_IdOrderByRoomNumberAsc(groupAcc.getId());

                    dto.setHabitaciones(rooms.stream().map(room -> {
                        HabitacionDto h = new HabitacionDto();
                        h.setNroHabitacion(room.getRoomNumber());
                        h.setTipo(room.getRoomType());
                        h.setAdultos(room.getAdults());
                        h.setMenores(room.getMinors());
                        return h;
                    }).collect(Collectors.toList()));
                } catch (NoResultException ignored) {}
            }

            return dto;
        } catch (NoResultException e) { return null; }
    }

    private VueloDto buildVuelo(MemberAirService air) {
        VueloDto dto = new VueloDto();
        dto.setAerolinea(air.getAirline());
        dto.setTipoViaje(air.getTripType());
        dto.setTipoViajeDescripcion("ROUND_TRIP".equals(air.getTripType()) ? "Ida y vuelta" : "Solo ida");
        dto.setOrigen(air.getOrigin());
        dto.setDestino(air.getDestination());
        dto.setFechaSalida(air.getDepartureDate());
        dto.setHoraSalida(air.getDepartureTime());
        dto.setHoraLlegadaIda(air.getDepartureArrivalTime());
        dto.setFechaRegreso(air.getReturnDate());
        dto.setHoraRegreso(air.getReturnDepartureTime());
        dto.setHoraLlegadaRegreso(air.getReturnArrivalTime());
        dto.setEquipaje(air.getBaggageAllowance());
        dto.setCodigoReserva(air.getReservationCode());
        return dto;
    }

    private TrasladoDto buildTrasladoBA(MemberTransferService t) {
        TrasladoDto dto = new TrasladoDto();
        dto.setTipo("TRASLADO_BA");
        dto.setTipoDescripcion("Traslado Buenos Aires");
        dto.setTipoViaje(t.getTripType() != null ? t.getTripType().name() : null);
        dto.setTipoViajeDescripcion(t.getTripType() == TransferTripType.ROUND_TRIP ? "Ida y vuelta" : "Solo ida");
        dto.setProveedor(t.getProvider());
        dto.setLugarPickup(t.getPickupPlace());
        dto.setPuntoPickup(t.getPickupPointName());
        dto.setLugarDestino(t.getDestinationPlace());
        dto.setPuntoDestino(t.getDestinationPointName());
        dto.setFechaSalida(t.getDepartureDate());
        dto.setHoraSalida(t.getDepartureTime());
        dto.setHoraLlegadaIda(t.getDepartureArrivalTime());
        dto.setFechaRegreso(t.getReturnDate());
        dto.setHoraRegreso(t.getReturnTime());
        dto.setHoraLlegadaRegreso(t.getReturnArrivalTime());
        dto.setCodigoReserva(t.getReservationCode());
        dto.setNotas(t.getNotes());
        dto.setPersonalizado(t.isOverridden());
        return dto;
    }

    private TrasladoDto buildTrasladoDestino(MemberDestinationTransferService t) {
        TrasladoDto dto = new TrasladoDto();
        dto.setTipo("TRASLADO_DESTINO");
        dto.setTipoDescripcion("Traslado en destino");
        dto.setTipoViaje(t.getTripType() != null ? t.getTripType().name() : null);
        dto.setTipoViajeDescripcion(t.getTripType() == TransferTripType.ROUND_TRIP ? "Ida y vuelta" : "Solo ida");
        dto.setProveedor(t.getProvider());
        dto.setLugarPickup(t.getPickupPlace());
        dto.setPuntoPickup(t.getPickupPointName());
        dto.setLugarDestino(t.getDestinationPlace());
        dto.setPuntoDestino(t.getDestinationPointName());
        dto.setFechaSalida(t.getDepartureDate());
        dto.setHoraSalida(t.getDepartureTime());
        dto.setHoraLlegadaIda(t.getDepartureArrivalTime());
        dto.setFechaRegreso(t.getReturnDate());
        dto.setHoraRegreso(t.getReturnTime());
        dto.setHoraLlegadaRegreso(t.getReturnArrivalTime());
        dto.setCiudad(t.getCity());
        dto.setPais(t.getCountry());
        dto.setCodigoReserva(t.getReservationCode());
        dto.setNotas(t.getNotes());
        dto.setPersonalizado(t.isOverridden());
        return dto;
    }

    private FerryDto buildFerry(MemberFerryService f) {
        FerryDto dto = new FerryDto();
        dto.setEmpresa(f.getFerryCompany());
        dto.setProveedor(f.getProvider());
        dto.setTipoViaje(f.getTripType() != null ? f.getTripType().name() : null);
        dto.setTipoViajeDescripcion(f.getTripType() == FerryTripType.ROUND_TRIP ? "Ida y vuelta" : "Solo ida");
        dto.setPuertoOrigen(f.getOriginPort());
        dto.setPuertoDestino(f.getDestinationPort());
        dto.setFechaSalida(f.getDepartureDate());
        dto.setHoraSalida(f.getDepartureTime());
        dto.setHoraLlegadaIda(f.getDepartureArrivalTime());
        dto.setFechaRegreso(f.getReturnDate());
        dto.setHoraRegreso(f.getReturnTime());
        dto.setHoraLlegadaRegreso(f.getReturnArrivalTime());
        dto.setCodigoReserva(f.getReservationCode());
        dto.setNotas(f.getNotes());
        dto.setPersonalizado(f.isOverridden());
        return dto;
    }

    private List<ExcursionDto> buildExcursiones(Long memberId) {
        return excursionRepo.findAll().stream()
                .filter(e -> e.getMember().getId().equals(memberId))
                .sorted((a, b) -> {
                    if (a.getExcursionDate() == null) return 1;
                    if (b.getExcursionDate() == null) return -1;
                    return a.getExcursionDate().compareTo(b.getExcursionDate());
                })
                .map(e -> {
                    ExcursionDto dto = new ExcursionDto();
                    dto.setNombre(e.getName());
                    dto.setFecha(e.getExcursionDate());
                    dto.setHoraSalida(e.getExcursionTime());
                    dto.setHoraRegreso(e.getExcursionReturnTime());
                    dto.setProveedor(e.getProvider());
                    dto.setNotas(e.getNotes());
                    dto.setCosto(e.getCost());
                    dto.setVenta(e.getSale());
                    dto.setMetodoPago(e.getPaymentMethod() != null ? e.getPaymentMethod().name() : null);
                    if (e.getPrestador() != null) {
                        dto.setPrestadorNombre(e.getPrestador().getNombre());
                    }
                    if (e.getExcursion() != null) {
                        dto.setDescripcion(e.getExcursion().getDescripcion());
                    }
                    return dto;
                }).collect(Collectors.toList());
    }

    private AsistenciaDto buildAsistencia(MemberOptionalTravelAssistanceService a) {
        AsistenciaDto dto = new AsistenciaDto();
        dto.setProveedor(a.getProvider());
        dto.setPlan(a.getPlan());
        dto.setNroPoliza(a.getPolicyNumber());
        dto.setTelefonoEmergencia(a.getEmergencyPhone());
        dto.setNotas(a.getNotes());
        dto.setCosto(a.getCost());
        dto.setVenta(a.getSale());
        return dto;
    }

    private List<EquipajeDto> buildEquipaje(Long memberId) {
        return luggageRepo.findAll().stream()
                .filter(l -> l.getMember().getId().equals(memberId))
                .map(l -> {
                    EquipajeDto dto = new EquipajeDto();
                    dto.setTipo(l.getType() != null ? l.getType().name() : null);
                    dto.setPesoKg(l.getWeightKg());
                    dto.setAerolinea(l.getAirline());
                    dto.setDimensiones(l.getDimensions());
                    dto.setNotas(l.getNotes());
                    dto.setCosto(l.getCost());
                    dto.setVenta(l.getSale());
                    return dto;
                }).collect(Collectors.toList());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper
    // ─────────────────────────────────────────────────────────────────────────
    private String regimenLabel(AccommodationRegimen r) {
        if (r == null) return null;
        return switch (r) {
            case BREAKFAST    -> "Desayuno incluido";
            case HALF_BOARD   -> "Media pensión";
            case FULL_BOARD   -> "Pensión completa";
            case ALL_INCLUSIVE -> "All inclusive";
            case ROOM_ONLY    -> "Solo alojamiento";
        };
    }

    // Acceso al menuItemId sin lazy loading problemático
    private Long getMenuItemId(MemberAccommodationService acc) {
        try {
            return (Long) em.createQuery(
                            "SELECT a.menuItem.id FROM MemberAccommodationService a WHERE a.id = :id")
                    .setParameter("id", acc.getId())
                    .getSingleResult();
        } catch (Exception e) { return null; }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DTOs — todos los campos expuestos
    // ─────────────────────────────────────────────────────────────────────────

    public static class GroupInfoResponse {
        private Long groupId; private String destino; private LocalDate fechaSalida;
        private LocalDate fechaRegreso; private String estado; private boolean operacionConfirmada;
        private List<PasajeroResumen> pasajeros = new ArrayList<>();
        public Long getGroupId() { return groupId; } public void setGroupId(Long v) { groupId=v; }
        public String getDestino() { return destino; } public void setDestino(String v) { destino=v; }
        public LocalDate getFechaSalida() { return fechaSalida; } public void setFechaSalida(LocalDate v) { fechaSalida=v; }
        public LocalDate getFechaRegreso() { return fechaRegreso; } public void setFechaRegreso(LocalDate v) { fechaRegreso=v; }
        public String getEstado() { return estado; } public void setEstado(String v) { estado=v; }
        public boolean isOperacionConfirmada() { return operacionConfirmada; } public void setOperacionConfirmada(boolean v) { operacionConfirmada=v; }
        public List<PasajeroResumen> getPasajeros() { return pasajeros; } public void setPasajeros(List<PasajeroResumen> v) { pasajeros=v; }
    }

    public static class PasajeroResumen {
        private Long memberId; private String nombre; private String email;
        public Long getMemberId() { return memberId; } public void setMemberId(Long v) { memberId=v; }
        public String getNombre() { return nombre; } public void setNombre(String v) { nombre=v; }
        public String getEmail() { return email; } public void setEmail(String v) { email=v; }
    }

    public static class MemberServicesResponse {
        private Long memberId; private Long groupId; private String destino;
        private LocalDate fechaSalida; private LocalDate fechaRegreso;
        private String estadoGrupo; private boolean operacionConfirmada;
        private DatosPersonales pasajero; private PagosDto pagos;
        private AlojamientoDto alojamiento; private VueloDto vuelo;
        private TrasladoDto trasladoBA; private TrasladoDto trasladoDestino;
        private FerryDto ferry; private List<ExcursionDto> excursiones = new ArrayList<>();
        private AsistenciaDto asistencia; private List<EquipajeDto> equipaje = new ArrayList<>();

        public Long getMemberId() { return memberId; } public void setMemberId(Long v) { memberId=v; }
        public Long getGroupId() { return groupId; } public void setGroupId(Long v) { groupId=v; }
        public String getDestino() { return destino; } public void setDestino(String v) { destino=v; }
        public LocalDate getFechaSalida() { return fechaSalida; } public void setFechaSalida(LocalDate v) { fechaSalida=v; }
        public LocalDate getFechaRegreso() { return fechaRegreso; } public void setFechaRegreso(LocalDate v) { fechaRegreso=v; }
        public String getEstadoGrupo() { return estadoGrupo; } public void setEstadoGrupo(String v) { estadoGrupo=v; }
        public boolean isOperacionConfirmada() { return operacionConfirmada; } public void setOperacionConfirmada(boolean v) { operacionConfirmada=v; }
        public DatosPersonales getPasajero() { return pasajero; } public void setPasajero(DatosPersonales v) { pasajero=v; }
        public PagosDto getPagos() { return pagos; } public void setPagos(PagosDto v) { pagos=v; }
        public AlojamientoDto getAlojamiento() { return alojamiento; } public void setAlojamiento(AlojamientoDto v) { alojamiento=v; }
        public VueloDto getVuelo() { return vuelo; } public void setVuelo(VueloDto v) { vuelo=v; }
        public TrasladoDto getTrasladoBA() { return trasladoBA; } public void setTrasladoBA(TrasladoDto v) { trasladoBA=v; }
        public TrasladoDto getTrasladoDestino() { return trasladoDestino; } public void setTrasladoDestino(TrasladoDto v) { trasladoDestino=v; }
        public FerryDto getFerry() { return ferry; } public void setFerry(FerryDto v) { ferry=v; }
        public List<ExcursionDto> getExcursiones() { return excursiones; } public void setExcursiones(List<ExcursionDto> v) { excursiones=v; }
        public AsistenciaDto getAsistencia() { return asistencia; } public void setAsistencia(AsistenciaDto v) { asistencia=v; }
        public List<EquipajeDto> getEquipaje() { return equipaje; } public void setEquipaje(List<EquipajeDto> v) { equipaje=v; }
    }

    public static class DatosPersonales {
        private String nombre; private String email; private String telefono;
        private String dni; private LocalDate fechaNacimiento; private String genero;
        private String pais; private String provincia; private String ciudad;
        private String localidad; private String codigoPostal;
        private Integer viajerosTotales; private Integer viajerosAdultos; private Integer viajerosMenores;
        private Boolean habitacionCompartida; private Boolean asistenciaViajero; private Boolean incluirExcursiones;
        private String estado; private LocalDateTime creadoEn;

        public String getNombre() { return nombre; } public void setNombre(String v) { nombre=v; }
        public String getEmail() { return email; } public void setEmail(String v) { email=v; }
        public String getTelefono() { return telefono; } public void setTelefono(String v) { telefono=v; }
        public String getDni() { return dni; } public void setDni(String v) { dni=v; }
        public LocalDate getFechaNacimiento() { return fechaNacimiento; } public void setFechaNacimiento(LocalDate v) { fechaNacimiento=v; }
        public String getGenero() { return genero; } public void setGenero(String v) { genero=v; }
        public String getPais() { return pais; } public void setPais(String v) { pais=v; }
        public String getProvincia() { return provincia; } public void setProvincia(String v) { provincia=v; }
        public String getCiudad() { return ciudad; } public void setCiudad(String v) { ciudad=v; }
        public String getLocalidad() { return localidad; } public void setLocalidad(String v) { localidad=v; }
        public String getCodigoPostal() { return codigoPostal; } public void setCodigoPostal(String v) { codigoPostal=v; }
        public Integer getViajerosTotales() { return viajerosTotales; } public void setViajerosTotales(Integer v) { viajerosTotales=v; }
        public Integer getViajerosAdultos() { return viajerosAdultos; } public void setViajerosAdultos(Integer v) { viajerosAdultos=v; }
        public Integer getViajerosMenores() { return viajerosMenores; } public void setViajerosMenores(Integer v) { viajerosMenores=v; }
        public Boolean getHabitacionCompartida() { return habitacionCompartida; } public void setHabitacionCompartida(Boolean v) { habitacionCompartida=v; }
        public Boolean getAsistenciaViajero() { return asistenciaViajero; } public void setAsistenciaViajero(Boolean v) { asistenciaViajero=v; }
        public Boolean getIncluirExcursiones() { return incluirExcursiones; } public void setIncluirExcursiones(Boolean v) { incluirExcursiones=v; }
        public String getEstado() { return estado; } public void setEstado(String v) { estado=v; }
        public LocalDateTime getCreadoEn() { return creadoEn; } public void setCreadoEn(LocalDateTime v) { creadoEn=v; }
    }

    public static class PagosDto {
        private String tipoPlan; private String metodoPago; private BigDecimal montoTotal;
        private BigDecimal montoPagado; private BigDecimal montoPendiente;
        private String moneda; private boolean pagoCompleto; private int cuotasPendientes;
        private String notas; private List<CuotaDto> cuotas = new ArrayList<>();
        private List<CobroRegistradoDto> cobrosRegistrados = new ArrayList<>();

        public String getTipoPlan() { return tipoPlan; } public void setTipoPlan(String v) { tipoPlan=v; }
        public String getMetodoPago() { return metodoPago; } public void setMetodoPago(String v) { metodoPago=v; }
        public BigDecimal getMontoTotal() { return montoTotal; } public void setMontoTotal(BigDecimal v) { montoTotal=v; }
        public BigDecimal getMontoPagado() { return montoPagado; } public void setMontoPagado(BigDecimal v) { montoPagado=v; }
        public BigDecimal getMontoPendiente() { return montoPendiente; } public void setMontoPendiente(BigDecimal v) { montoPendiente=v; }
        public String getMoneda() { return moneda; } public void setMoneda(String v) { moneda=v; }
        public boolean isPagoCompleto() { return pagoCompleto; } public void setPagoCompleto(boolean v) { pagoCompleto=v; }
        public int getCuotasPendientes() { return cuotasPendientes; } public void setCuotasPendientes(int v) { cuotasPendientes=v; }
        public String getNotas() { return notas; } public void setNotas(String v) { notas=v; }
        public List<CuotaDto> getCuotas() { return cuotas; } public void setCuotas(List<CuotaDto> v) { cuotas=v; }
        public List<CobroRegistradoDto> getCobrosRegistrados() { return cobrosRegistrados; } public void setCobrosRegistrados(List<CobroRegistradoDto> v) { cobrosRegistrados=v; }
    }

    public static class CuotaDto {
        private Integer nroCuota; private BigDecimal monto; private LocalDate fechaVencimiento;
        private LocalDate fechaPago; private String estado;
        public Integer getNroCuota() { return nroCuota; } public void setNroCuota(Integer v) { nroCuota=v; }
        public BigDecimal getMonto() { return monto; } public void setMonto(BigDecimal v) { monto=v; }
        public LocalDate getFechaVencimiento() { return fechaVencimiento; } public void setFechaVencimiento(LocalDate v) { fechaVencimiento=v; }
        public LocalDate getFechaPago() { return fechaPago; } public void setFechaPago(LocalDate v) { fechaPago=v; }
        public String getEstado() { return estado; } public void setEstado(String v) { estado=v; }
    }

    public static class CobroRegistradoDto {
        private Long id; private Integer nroCuota; private BigDecimal monto;
        private String moneda; private LocalDate fechaPago; private String ultimos4;
        private boolean tieneComprobante;
        public Long getId() { return id; } public void setId(Long v) { id=v; }
        public Integer getNroCuota() { return nroCuota; } public void setNroCuota(Integer v) { nroCuota=v; }
        public BigDecimal getMonto() { return monto; } public void setMonto(BigDecimal v) { monto=v; }
        public String getMoneda() { return moneda; } public void setMoneda(String v) { moneda=v; }
        public LocalDate getFechaPago() { return fechaPago; } public void setFechaPago(LocalDate v) { fechaPago=v; }
        public String getUltimos4() { return ultimos4; } public void setUltimos4(String v) { ultimos4=v; }
        public boolean isTieneComprobante() { return tieneComprobante; } public void setTieneComprobante(boolean v) { tieneComprobante=v; }
    }

    public static class AlojamientoDto {
        private String nombre; private LocalDate checkInFecha; private LocalTime checkInHorario;
        private LocalDate checkOutFecha; private LocalTime checkOutHorario;
        private String regimen; private String regimenDescripcion; private String ciudad; private String pais;
        private String tipoContrato; private String nombreTercero; private String codigoReserva;
        private LocalDate fechaVencimientoReserva; private List<HabitacionDto> habitaciones = new ArrayList<>();
        public String getNombre() { return nombre; } public void setNombre(String v) { nombre=v; }
        public LocalDate getCheckInFecha() { return checkInFecha; } public void setCheckInFecha(LocalDate v) { checkInFecha=v; }
        public LocalTime getCheckInHorario() { return checkInHorario; } public void setCheckInHorario(LocalTime v) { checkInHorario=v; }
        public LocalDate getCheckOutFecha() { return checkOutFecha; } public void setCheckOutFecha(LocalDate v) { checkOutFecha=v; }
        public LocalTime getCheckOutHorario() { return checkOutHorario; } public void setCheckOutHorario(LocalTime v) { checkOutHorario=v; }
        public String getRegimen() { return regimen; } public void setRegimen(String v) { regimen=v; }
        public String getRegimenDescripcion() { return regimenDescripcion; } public void setRegimenDescripcion(String v) { regimenDescripcion=v; }
        public String getCiudad() { return ciudad; } public void setCiudad(String v) { ciudad=v; }
        public String getPais() { return pais; } public void setPais(String v) { pais=v; }
        public String getTipoContrato() { return tipoContrato; } public void setTipoContrato(String v) { tipoContrato=v; }
        public String getNombreTercero() { return nombreTercero; } public void setNombreTercero(String v) { nombreTercero=v; }
        public String getCodigoReserva() { return codigoReserva; } public void setCodigoReserva(String v) { codigoReserva=v; }
        public LocalDate getFechaVencimientoReserva() { return fechaVencimientoReserva; } public void setFechaVencimientoReserva(LocalDate v) { fechaVencimientoReserva=v; }
        public List<HabitacionDto> getHabitaciones() { return habitaciones; } public void setHabitaciones(List<HabitacionDto> v) { habitaciones=v; }
    }

    public static class HabitacionDto {
        private Integer nroHabitacion; private String tipo; private Integer adultos; private Integer menores;
        public Integer getNroHabitacion() { return nroHabitacion; } public void setNroHabitacion(Integer v) { nroHabitacion=v; }
        public String getTipo() { return tipo; } public void setTipo(String v) { tipo=v; }
        public Integer getAdultos() { return adultos; } public void setAdultos(Integer v) { adultos=v; }
        public Integer getMenores() { return menores; } public void setMenores(Integer v) { menores=v; }
    }

    public static class VueloDto {
        private String aerolinea; private String tipoViaje; private String tipoViajeDescripcion;
        private String origen; private String destino; private LocalDate fechaSalida;
        private LocalTime horaSalida; private LocalTime horaLlegadaIda; private LocalDate fechaRegreso;
        private LocalTime horaRegreso; private LocalTime horaLlegadaRegreso;
        private String equipaje; private String codigoReserva; private String notas;
        public String getAerolinea() { return aerolinea; } public void setAerolinea(String v) { aerolinea=v; }
        public String getTipoViaje() { return tipoViaje; } public void setTipoViaje(String v) { tipoViaje=v; }
        public String getTipoViajeDescripcion() { return tipoViajeDescripcion; } public void setTipoViajeDescripcion(String v) { tipoViajeDescripcion=v; }
        public String getOrigen() { return origen; } public void setOrigen(String v) { origen=v; }
        public String getDestino() { return destino; } public void setDestino(String v) { destino=v; }
        public LocalDate getFechaSalida() { return fechaSalida; } public void setFechaSalida(LocalDate v) { fechaSalida=v; }
        public LocalTime getHoraSalida() { return horaSalida; } public void setHoraSalida(LocalTime v) { horaSalida=v; }
        public LocalTime getHoraLlegadaIda() { return horaLlegadaIda; } public void setHoraLlegadaIda(LocalTime v) { horaLlegadaIda=v; }
        public LocalDate getFechaRegreso() { return fechaRegreso; } public void setFechaRegreso(LocalDate v) { fechaRegreso=v; }
        public LocalTime getHoraRegreso() { return horaRegreso; } public void setHoraRegreso(LocalTime v) { horaRegreso=v; }
        public LocalTime getHoraLlegadaRegreso() { return horaLlegadaRegreso; } public void setHoraLlegadaRegreso(LocalTime v) { horaLlegadaRegreso=v; }
        public String getEquipaje() { return equipaje; } public void setEquipaje(String v) { equipaje=v; }
        public String getCodigoReserva() { return codigoReserva; } public void setCodigoReserva(String v) { codigoReserva=v; }
        public String getNotas() { return notas; } public void setNotas(String v) { notas=v; }
    }

    public static class TrasladoDto {
        private String tipo; private String tipoDescripcion; private String tipoViaje; private String tipoViajeDescripcion;
        private String proveedor; private String lugarPickup; private String puntoPickup;
        private String lugarDestino; private String puntoDestino;
        private LocalDate fechaSalida; private LocalTime horaSalida; private LocalTime horaLlegadaIda;
        private LocalDate fechaRegreso; private LocalTime horaRegreso; private LocalTime horaLlegadaRegreso;
        private String ciudad; private String pais; private String codigoReserva; private String notas;
        private boolean personalizado;
        public String getTipo() { return tipo; } public void setTipo(String v) { tipo=v; }
        public String getTipoDescripcion() { return tipoDescripcion; } public void setTipoDescripcion(String v) { tipoDescripcion=v; }
        public String getTipoViaje() { return tipoViaje; } public void setTipoViaje(String v) { tipoViaje=v; }
        public String getTipoViajeDescripcion() { return tipoViajeDescripcion; } public void setTipoViajeDescripcion(String v) { tipoViajeDescripcion=v; }
        public String getProveedor() { return proveedor; } public void setProveedor(String v) { proveedor=v; }
        public String getLugarPickup() { return lugarPickup; } public void setLugarPickup(String v) { lugarPickup=v; }
        public String getPuntoPickup() { return puntoPickup; } public void setPuntoPickup(String v) { puntoPickup=v; }
        public String getLugarDestino() { return lugarDestino; } public void setLugarDestino(String v) { lugarDestino=v; }
        public String getPuntoDestino() { return puntoDestino; } public void setPuntoDestino(String v) { puntoDestino=v; }
        public LocalDate getFechaSalida() { return fechaSalida; } public void setFechaSalida(LocalDate v) { fechaSalida=v; }
        public LocalTime getHoraSalida() { return horaSalida; } public void setHoraSalida(LocalTime v) { horaSalida=v; }
        public LocalTime getHoraLlegadaIda() { return horaLlegadaIda; } public void setHoraLlegadaIda(LocalTime v) { horaLlegadaIda=v; }
        public LocalDate getFechaRegreso() { return fechaRegreso; } public void setFechaRegreso(LocalDate v) { fechaRegreso=v; }
        public LocalTime getHoraRegreso() { return horaRegreso; } public void setHoraRegreso(LocalTime v) { horaRegreso=v; }
        public LocalTime getHoraLlegadaRegreso() { return horaLlegadaRegreso; } public void setHoraLlegadaRegreso(LocalTime v) { horaLlegadaRegreso=v; }
        public String getCiudad() { return ciudad; } public void setCiudad(String v) { ciudad=v; }
        public String getPais() { return pais; } public void setPais(String v) { pais=v; }
        public String getCodigoReserva() { return codigoReserva; } public void setCodigoReserva(String v) { codigoReserva=v; }
        public String getNotas() { return notas; } public void setNotas(String v) { notas=v; }
        public boolean isPersonalizado() { return personalizado; } public void setPersonalizado(boolean v) { personalizado=v; }
    }

    public static class FerryDto {
        private String empresa; private String proveedor; private String tipoViaje; private String tipoViajeDescripcion;
        private String puertoOrigen; private String puertoDestino;
        private LocalDate fechaSalida; private LocalTime horaSalida; private LocalTime horaLlegadaIda;
        private LocalDate fechaRegreso; private LocalTime horaRegreso; private LocalTime horaLlegadaRegreso;
        private String codigoReserva; private String notas; private boolean personalizado;
        public String getEmpresa() { return empresa; } public void setEmpresa(String v) { empresa=v; }
        public String getProveedor() { return proveedor; } public void setProveedor(String v) { proveedor=v; }
        public String getTipoViaje() { return tipoViaje; } public void setTipoViaje(String v) { tipoViaje=v; }
        public String getTipoViajeDescripcion() { return tipoViajeDescripcion; } public void setTipoViajeDescripcion(String v) { tipoViajeDescripcion=v; }
        public String getPuertoOrigen() { return puertoOrigen; } public void setPuertoOrigen(String v) { puertoOrigen=v; }
        public String getPuertoDestino() { return puertoDestino; } public void setPuertoDestino(String v) { puertoDestino=v; }
        public LocalDate getFechaSalida() { return fechaSalida; } public void setFechaSalida(LocalDate v) { fechaSalida=v; }
        public LocalTime getHoraSalida() { return horaSalida; } public void setHoraSalida(LocalTime v) { horaSalida=v; }
        public LocalTime getHoraLlegadaIda() { return horaLlegadaIda; } public void setHoraLlegadaIda(LocalTime v) { horaLlegadaIda=v; }
        public LocalDate getFechaRegreso() { return fechaRegreso; } public void setFechaRegreso(LocalDate v) { fechaRegreso=v; }
        public LocalTime getHoraRegreso() { return horaRegreso; } public void setHoraRegreso(LocalTime v) { horaRegreso=v; }
        public LocalTime getHoraLlegadaRegreso() { return horaLlegadaRegreso; } public void setHoraLlegadaRegreso(LocalTime v) { horaLlegadaRegreso=v; }
        public String getCodigoReserva() { return codigoReserva; } public void setCodigoReserva(String v) { codigoReserva=v; }
        public String getNotas() { return notas; } public void setNotas(String v) { notas=v; }
        public boolean isPersonalizado() { return personalizado; } public void setPersonalizado(boolean v) { personalizado=v; }
    }

    public static class ExcursionDto {
        private String nombre; private String descripcion; private LocalDate fecha;
        private LocalTime horaSalida; private LocalTime horaRegreso; private String proveedor;
        private String prestadorNombre; private String notas;
        private BigDecimal costo; private BigDecimal venta; private String metodoPago;
        public String getNombre() { return nombre; } public void setNombre(String v) { nombre=v; }
        public String getDescripcion() { return descripcion; } public void setDescripcion(String v) { descripcion=v; }
        public LocalDate getFecha() { return fecha; } public void setFecha(LocalDate v) { fecha=v; }
        public LocalTime getHoraSalida() { return horaSalida; } public void setHoraSalida(LocalTime v) { horaSalida=v; }
        public LocalTime getHoraRegreso() { return horaRegreso; } public void setHoraRegreso(LocalTime v) { horaRegreso=v; }
        public String getProveedor() { return proveedor; } public void setProveedor(String v) { proveedor=v; }
        public String getPrestadorNombre() { return prestadorNombre; } public void setPrestadorNombre(String v) { prestadorNombre=v; }
        public String getNotas() { return notas; } public void setNotas(String v) { notas=v; }
        public BigDecimal getCosto() { return costo; } public void setCosto(BigDecimal v) { costo=v; }
        public BigDecimal getVenta() { return venta; } public void setVenta(BigDecimal v) { venta=v; }
        public String getMetodoPago() { return metodoPago; } public void setMetodoPago(String v) { metodoPago=v; }
    }

    public static class AsistenciaDto {
        private String proveedor; private String plan; private String nroPoliza;
        private String telefonoEmergencia; private String notas;
        private BigDecimal costo; private BigDecimal venta;
        public String getProveedor() { return proveedor; } public void setProveedor(String v) { proveedor=v; }
        public String getPlan() { return plan; } public void setPlan(String v) { plan=v; }
        public String getNroPoliza() { return nroPoliza; } public void setNroPoliza(String v) { nroPoliza=v; }
        public String getTelefonoEmergencia() { return telefonoEmergencia; } public void setTelefonoEmergencia(String v) { telefonoEmergencia=v; }
        public String getNotas() { return notas; } public void setNotas(String v) { notas=v; }
        public BigDecimal getCosto() { return costo; } public void setCosto(BigDecimal v) { costo=v; }
        public BigDecimal getVenta() { return venta; } public void setVenta(BigDecimal v) { venta=v; }
    }

    public static class EquipajeDto {
        private String tipo; private BigDecimal pesoKg; private String aerolinea;
        private String dimensiones; private String notas; private BigDecimal costo; private BigDecimal venta;
        public String getTipo() { return tipo; } public void setTipo(String v) { tipo=v; }
        public BigDecimal getPesoKg() { return pesoKg; } public void setPesoKg(BigDecimal v) { pesoKg=v; }
        public String getAerolinea() { return aerolinea; } public void setAerolinea(String v) { aerolinea=v; }
        public String getDimensiones() { return dimensiones; } public void setDimensiones(String v) { dimensiones=v; }
        public String getNotas() { return notas; } public void setNotas(String v) { notas=v; }
        public BigDecimal getCosto() { return costo; } public void setCosto(BigDecimal v) { costo=v; }
        public BigDecimal getVenta() { return venta; } public void setVenta(BigDecimal v) { venta=v; }
    }
}
