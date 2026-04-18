package app.coincidir.api.web.admin;

import app.coincidir.api.domain.AccommodationRegimen;
import app.coincidir.api.repository.FerryScheduleRepository;
import app.coincidir.api.repository.FerryProviderRepository;
import app.coincidir.api.web.dto.FerryScheduleDto;
import app.coincidir.api.repository.LocationXPointRepository;
import app.coincidir.api.repository.TransferBaProviderRepository;
import app.coincidir.api.repository.TransferDestinoProviderRepository;
import app.coincidir.api.web.dto.BaggageRuleOptionDto;
import app.coincidir.api.web.dto.FerryProviderDto;
import app.coincidir.api.web.dto.LookupOptionDto;
import app.coincidir.api.web.dto.RegimenOptionDto;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/admin/lookups")
@RequiredArgsConstructor
public class LookupsAdminController {

    private final FerryProviderRepository ferryProviderRepo;
    private final FerryScheduleRepository ferryScheduleRepo;
    private final TransferBaProviderRepository transferBaProviderRepo;
    private final TransferDestinoProviderRepository transferDestinoProviderRepo;
    private final LocationXPointRepository locationXPointRepo;
    private final JdbcTemplate jdbc;

    @GetMapping({"/baggage-options", "/baggage/options"})
    public List<BaggageRuleOptionDto> listBaggageOptions(
            @RequestParam String airline,
            @RequestParam(required = false, defaultValue = "CHECKED") String type
    ) {
        final String a = Objects.toString(airline, "").trim();
        final String t = Objects.toString(type, "CHECKED").trim();
        if (a.isBlank()) return List.of();

        final String typeNorm = t.toUpperCase(Locale.ROOT);

        // Intentar distintas variantes de esquema (según DB)..
        final List<Supplier<List<BaggageRuleOptionDto>>> attempts = List.of(
                // airline_baggage_rule(airline, type)
                () -> jdbc.query(
                        "SELECT id, weight_kg, dimensions FROM airline_baggage_rule WHERE LOWER(TRIM(airline)) = LOWER(?) AND UPPER(TRIM(type)) = ? ORDER BY weight_kg",
                        (rs, i) -> new BaggageRuleOptionDto(
                                rs.getLong("id"),
                                (Integer) rs.getObject("weight_kg"),
                                rs.getString("dimensions"),
                                null
                        ),
                        a,
                        typeNorm
                ),
                // airline_baggage_rule(airline_name, type)
                () -> jdbc.query(
                        "SELECT id, weight_kg, dimensions FROM airline_baggage_rule WHERE LOWER(TRIM(airline_name)) = LOWER(?) AND UPPER(TRIM(type)) = ? ORDER BY weight_kg",
                        (rs, i) -> new BaggageRuleOptionDto(
                                rs.getLong("id"),
                                (Integer) rs.getObject("weight_kg"),
                                rs.getString("dimensions"),
                                null
                        ),
                        a,
                        typeNorm
                ),
                // airline_baggage_rule(airline_id) + join airlines(nombre/name)
                () -> jdbc.query(
                        "SELECT r.id, r.weight_kg, r.dimensions " +
                                "FROM airline_baggage_rule r " +
                                "JOIN airlines al ON al.id = r.airline_id " +
                                "WHERE LOWER(TRIM(al.name)) = LOWER(?) AND UPPER(TRIM(r.type)) = ? " +
                                "ORDER BY r.weight_kg",
                        (rs, i) -> new BaggageRuleOptionDto(
                                rs.getLong("id"),
                                (Integer) rs.getObject("weight_kg"),
                                rs.getString("dimensions"),
                                null
                        ),
                        a,
                        typeNorm
                ),
                // airline_baggage_rule(airline_id) + join aerolineas(nombre)
                () -> jdbc.query(
                        "SELECT r.id, r.weight_kg, r.dimensions " +
                                "FROM airline_baggage_rule r " +
                                "JOIN aerolineas al ON al.id = r.airline_id " +
                                "WHERE LOWER(TRIM(al.nombre)) = LOWER(?) AND UPPER(TRIM(r.type)) = ? " +
                                "ORDER BY r.weight_kg",
                        (rs, i) -> new BaggageRuleOptionDto(
                                rs.getLong("id"),
                                (Integer) rs.getObject("weight_kg"),
                                rs.getString("dimensions"),
                                null
                        ),
                        a,
                        typeNorm
                )
        );

        for (Supplier<List<BaggageRuleOptionDto>> s : attempts) {
            try {
                final List<BaggageRuleOptionDto> out = s.get();
                if (out != null && !out.isEmpty()) {
                    out.forEach(o -> {
                        if (o.getLabel() == null || o.getLabel().isBlank()) {
                            final Integer kg = o.getWeightKg();
                            o.setLabel(kg != null ? (kg + " kg") : "");
                        }
                    });
                    return out;
                }
            } catch (Exception ignored) {
                // siguiente query
            }
        }

        return List.of();
    }

    @GetMapping("/ferry-providers")
    public List<FerryProviderDto> listFerryProviders() {
        return ferryProviderRepo.findAllByActivoTrueOrderByNombreAsc()
                .stream()
                .map(p -> new FerryProviderDto(p.getId(), p.getNombre(), p.getDireccion(), p.getTelefono(), p.getWeb()))
                .toList();
    }

    @GetMapping("/ferry-schedules/debug")
    public Map<String, Object> debugFerrySchedules() {
        final Map<String, Object> result = new LinkedHashMap<>();
        try {
            final Long count = jdbc.queryForObject("SELECT COUNT(*) FROM ferry_schedules", Long.class);
            result.put("count", count);
            final List<Map<String, Object>> sample = jdbc.queryForList("SELECT * FROM ferry_schedules LIMIT 3");
            result.put("sample", sample);
            if (!sample.isEmpty()) {
                result.put("columns", sample.get(0).keySet());
            }
        } catch (Exception e) {
            result.put("error", e.getMessage());
            result.put("cause", e.getClass().getName());
        }
        return result;
    }

    @GetMapping("/ferry-schedules")
    public List<FerryScheduleDto> listFerrySchedules(
            @RequestParam String provider,
            @RequestParam(required = false, defaultValue = "") String busOrigin,
            @RequestParam(required = false, defaultValue = "") String busDestination,
            @RequestParam String ferryOrigin,
            @RequestParam String ferryDestination
    ) {
        if (provider == null || provider.isBlank()
                || ferryOrigin == null || ferryOrigin.isBlank()
                || ferryDestination == null || ferryDestination.isBlank()) {
            return List.of();
        }

        final String p  = provider.trim().toLowerCase(Locale.ROOT);
        final String fo = ferryOrigin.trim().toLowerCase(Locale.ROOT);
        final String fd = ferryDestination.trim().toLowerCase(Locale.ROOT);
        final String bo = busOrigin == null ? "" : busOrigin.trim().toLowerCase(Locale.ROOT);
        final String bd = busDestination == null ? "" : busDestination.trim().toLowerCase(Locale.ROOT);
        final boolean noBus = bo.isEmpty() && bd.isEmpty();

        try {
            final List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM ferry_schedules");
            return rows.stream()
                .filter(r -> {
                    if (!norm(str(r, "provider")).equals(p)) return false;
                    if (!norm(str(r, "ferry_origin")).equals(fo)) return false;
                    if (!norm(str(r, "ferry_destination")).equals(fd)) return false;
                    final Object act = r.get("activo");
                    if (act != null) {
                        final String sv = act.toString().trim();
                        if (sv.equals("0") || sv.equalsIgnoreCase("false")) return false;
                    }
                    if (noBus) {
                        return str(r, "bus_origin").isBlank() && str(r, "bus_destination").isBlank();
                    }
                    return norm(str(r, "bus_origin")).equals(bo) && norm(str(r, "bus_destination")).equals(bd);
                })
                .sorted(Comparator.comparing(r -> str(r, "ferry_departure_time")))
                .map(r -> new FerryScheduleDto(
                    r.get("id") instanceof Number n ? n.longValue() : 0L,
                    str(r, "provider"),
                    str(r, "bus_origin"),
                    str(r, "bus_destination"),
                    str(r, "ferry_origin"),
                    str(r, "ferry_destination"),
                    fmtTimeObj(r.get("bus_departure_time")),
                    fmtTimeObj(r.get("bus_arrival_time")),
                    fmtTimeObj(r.get("ferry_departure_time")),
                    fmtTimeObj(r.get("ferry_arrival_time"))
                ))
                .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String str(Map<String, Object> row, String key) {
        // Busca la key case-insensitive para tolerar variaciones de naming
        if (row.containsKey(key)) {
            final Object v = row.get(key);
            return v == null ? "" : v.toString();
        }
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey().equalsIgnoreCase(key)) {
                return e.getValue() == null ? "" : e.getValue().toString();
            }
        }
        return "";
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private String fmtTimeObj(Object value) {
        if (value == null) return "";
        if (value instanceof LocalTime t) return String.format("%02d:%02d", t.getHour(), t.getMinute());
        if (value instanceof java.sql.Time t) return String.format("%02d:%02d", t.toLocalTime().getHour(), t.toLocalTime().getMinute());
        final String raw = value.toString().trim();
        if (raw.isBlank()) return "";
        final java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d{1,2}):(\\d{2})").matcher(raw);
        if (m.find()) return String.format("%02d:%02d", Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        return raw;
    }

    @GetMapping("/ferry-ports")
    public List<LookupOptionDto> listFerryPorts() {
        // No hay tabla catálogo de puertos; devolver un listado dinámico desde los servicios cargados.
        // (data-driven desde DB). Si la tabla member_ferry_service no existe en alguna BD vieja,
        // fallback a solo group_ferry_service.

        final String qWithMembers =
                "SELECT DISTINCT TRIM(port) AS name " +
                        "FROM (" +
                        "  SELECT origin_port AS port FROM group_ferry_service WHERE origin_port IS NOT NULL AND TRIM(origin_port) <> '' " +
                        "  UNION " +
                        "  SELECT destination_port AS port FROM group_ferry_service WHERE destination_port IS NOT NULL AND TRIM(destination_port) <> '' " +
                        "  UNION " +
                        "  SELECT origin_port AS port FROM member_ferry_service WHERE origin_port IS NOT NULL AND TRIM(origin_port) <> '' " +
                        "  UNION " +
                        "  SELECT destination_port AS port FROM member_ferry_service WHERE destination_port IS NOT NULL AND TRIM(destination_port) <> '' " +
                        ") t " +
                        "ORDER BY name";

        final String qGroupOnly =
                "SELECT DISTINCT TRIM(port) AS name " +
                        "FROM (" +
                        "  SELECT origin_port AS port FROM group_ferry_service WHERE origin_port IS NOT NULL AND TRIM(origin_port) <> '' " +
                        "  UNION " +
                        "  SELECT destination_port AS port FROM group_ferry_service WHERE destination_port IS NOT NULL AND TRIM(destination_port) <> '' " +
                        ") t " +
                        "ORDER BY name";

        try {
            return jdbc.query(qWithMembers, (rs, rowNum) -> new LookupOptionDto((long) (rowNum + 1), rs.getString("name")));
        } catch (Exception ignored) {
            return jdbc.query(qGroupOnly, (rs, rowNum) -> new LookupOptionDto((long) (rowNum + 1), rs.getString("name")));
        }
    }

    @GetMapping("/transfer-ba-providers")
    public List<LookupOptionDto> listTransferBaProviders(
            @RequestParam(value = "city", required = false) String city
    ) {
        final String c = city == null ? "" : city.trim();
        if (!c.isEmpty()) {
            String cityWhere = buildCityWhereClause("ci.descripcion", c);
            return jdbc.query(
                "SELECT t.id, t.nombre " +
                "FROM transfer_ba_providers t " +
                "JOIN ciudades ci ON ci.id = t.id_ciudad " +
                "WHERE t.activo = 1 AND (" + cityWhere + ") " +
                "ORDER BY t.nombre",
                (rs, rowNum) -> new LookupOptionDto(rs.getLong("id"), rs.getString("nombre")),
                buildCityArgs(null, c)
            );
        }
        return transferBaProviderRepo.findAllByActivoTrueOrderByNombreAsc()
                .stream()
                .map(p -> new LookupOptionDto(p.getId(), p.getNombre()))
                .toList();
    }

    @GetMapping("/transfer-ba-providers/details")
    public Map<String, String> getTransferBaProviderDetails(@RequestParam(value = "name") String name) {
        return transferBaProviderRepo.findAllByActivoTrueOrderByNombreAsc().stream()
                .filter(p -> p.getNombre() != null && p.getNombre().trim().equalsIgnoreCase(name.trim()))
                .findFirst()
                .map(p -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("telefono", p.getTelefono() != null ? p.getTelefono() : "");
                    m.put("web",      p.getWeb()      != null ? p.getWeb()      : "");
                    return m;
                })
                .orElse(Map.of("telefono", "", "web", ""));
    }

    @GetMapping("/transfer-destino-providers")
    public List<LookupOptionDto> listTransferDestinoProviders(
            @RequestParam(value = "city", required = false) String city
    ) {
        final String c = city == null ? "" : city.trim();
        if (!c.isEmpty()) {
            String cityWhere = buildCityWhereClause("ci.descripcion", c);
            return jdbc.query(
                "SELECT t.id, t.nombre " +
                "FROM transfer_destino_providers t " +
                "JOIN ciudades ci ON ci.id = t.id_ciudad " +
                "WHERE t.activo = 1 AND (" + cityWhere + ") " +
                "ORDER BY t.nombre",
                (rs, rowNum) -> new LookupOptionDto(rs.getLong("id"), rs.getString("nombre")),
                buildCityArgs(null, c)
            );
        }
        return transferDestinoProviderRepo.findAllByActivoTrueOrderByNombreAsc()
                .stream()
                .map(p -> new LookupOptionDto(p.getId(), p.getNombre()))
                .toList();
    }

    @GetMapping("/transfer-destino-providers/details")
    public Map<String, String> getTransferDestinoProviderDetails(@RequestParam(value = "name") String name) {
        return transferDestinoProviderRepo.findAllByActivoTrueOrderByNombreAsc().stream()
                .filter(p -> p.getNombre() != null && p.getNombre().trim().equalsIgnoreCase(name.trim()))
                .findFirst()
                .map(p -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("telefono", p.getTelefono() != null ? p.getTelefono() : "");
                    m.put("web",      p.getWeb()      != null ? p.getWeb()      : "");
                    return m;
                })
                .orElse(Map.of("telefono", "", "web", ""));
    }

    @GetMapping({"/transfer-locations", "/transfer-places", "/transfers/places"})
    public List<LookupOptionDto> listTransferLocations() {
        List<LookupOptionDto> fromCatalog = tryCatalogPlaces(
                List.of(
                        "SELECT id, name FROM transfer_locations WHERE active = 1 ORDER BY name",
                        "SELECT id, nombre AS name FROM transfer_locations WHERE activo = 1 ORDER BY nombre",
                        "SELECT id, name FROM transfer_location WHERE active = 1 ORDER BY name",
                        "SELECT id, nombre AS name FROM transfer_location WHERE activo = 1 ORDER BY nombre",
                        "SELECT id, name FROM transfer_ba_places WHERE active = 1 ORDER BY name",
                        "SELECT id, nombre AS name FROM transfer_ba_places WHERE activo = 1 ORDER BY nombre",
                        "SELECT id, nombre AS name FROM transfer_ba_lugares WHERE activo = 1 ORDER BY nombre",
                        "SELECT id, nombre AS name FROM traslados_ba_lugares WHERE activo = 1 ORDER BY nombre",
                        "SELECT id, name FROM transfer_destino_places WHERE active = 1 ORDER BY name",
                        "SELECT id, nombre AS name FROM transfer_destino_places WHERE activo = 1 ORDER BY nombre",
                        "SELECT id, nombre AS name FROM transfer_destino_lugares WHERE activo = 1 ORDER BY nombre",
                        "SELECT id, nombre AS name FROM traslados_destino_lugares WHERE activo = 1 ORDER BY nombre"
                )
        );
        if (fromCatalog != null) return dedupeLookupOptions(fromCatalog);

        final String qWithMembers =
                "SELECT DISTINCT TRIM(p) AS name FROM (" +
                        "  SELECT pickup_place AS p FROM group_transfer_service WHERE pickup_place IS NOT NULL AND TRIM(pickup_place) <> ''" +
                        "  UNION " +
                        "  SELECT destination_place AS p FROM group_transfer_service WHERE destination_place IS NOT NULL AND TRIM(destination_place) <> ''" +
                        "  UNION " +
                        "  SELECT pickup_place AS p FROM member_transfer_service WHERE pickup_place IS NOT NULL AND TRIM(pickup_place) <> ''" +
                        "  UNION " +
                        "  SELECT destination_place AS p FROM member_transfer_service WHERE destination_place IS NOT NULL AND TRIM(destination_place) <> ''" +
                        "  UNION " +
                        "  SELECT pickup_place AS p FROM group_destination_transfer_service WHERE pickup_place IS NOT NULL AND TRIM(pickup_place) <> ''" +
                        "  UNION " +
                        "  SELECT destination_place AS p FROM group_destination_transfer_service WHERE destination_place IS NOT NULL AND TRIM(destination_place) <> ''" +
                        "  UNION " +
                        "  SELECT pickup_place AS p FROM member_destination_transfer_service WHERE pickup_place IS NOT NULL AND TRIM(pickup_place) <> ''" +
                        "  UNION " +
                        "  SELECT destination_place AS p FROM member_destination_transfer_service WHERE destination_place IS NOT NULL AND TRIM(destination_place) <> ''" +
                        ") t ORDER BY name";

        final String qGroupOnly =
                "SELECT DISTINCT TRIM(p) AS name FROM (" +
                        "  SELECT pickup_place AS p FROM group_transfer_service WHERE pickup_place IS NOT NULL AND TRIM(pickup_place) <> ''" +
                        "  UNION " +
                        "  SELECT destination_place AS p FROM group_transfer_service WHERE destination_place IS NOT NULL AND TRIM(destination_place) <> ''" +
                        "  UNION " +
                        "  SELECT pickup_place AS p FROM group_destination_transfer_service WHERE pickup_place IS NOT NULL AND TRIM(pickup_place) <> ''" +
                        "  UNION " +
                        "  SELECT destination_place AS p FROM group_destination_transfer_service WHERE destination_place IS NOT NULL AND TRIM(destination_place) <> ''" +
                        ") t ORDER BY name";

        try {
            return jdbc.query(qWithMembers, (rs, rowNum) -> new LookupOptionDto((long) (rowNum + 1), rs.getString("name")));
        } catch (Exception ignored) {
            return jdbc.query(qGroupOnly, (rs, rowNum) -> new LookupOptionDto((long) (rowNum + 1), rs.getString("name")));
        }
    }

    /**
     * GET /api/admin/lookups/transfer-points-by-location?locationId=<id>
     *   OR ?locationName=<name>  (fallback resolves by name → id first)
     *
     * Queries:
     *   SELECT tp.id, tp.name
     *   FROM transfer_points tp
     *   JOIN locations_x_points lxp ON lxp.transfer_point_id = tp.id
     *   WHERE lxp.transfer_location_id = :locationId
     *   ORDER BY tp.name
     */
    @GetMapping({"/transfer-points-by-location", "/transfer-points"})
    public List<LookupOptionDto> listTransferPointsByLocation(
            @RequestParam(value = "locationId", required = false) Long locationId,
            @RequestParam(value = "location_id", required = false) Long locationIdAlt,
            @RequestParam(value = "locationName", required = false) String locationName,
            @RequestParam(value = "location_name", required = false) String locationNameAlt
    ) {
        final Long resolvedId = locationId != null ? locationId : locationIdAlt;
        final String resolvedName = locationName != null ? locationName : (locationNameAlt != null ? locationNameAlt : "");

        // Resolve locationId from name if not provided directly
        Long finalId = resolvedId;
        if (finalId == null && !resolvedName.isBlank()) {
            finalId = resolveTransferLocationIdByName(resolvedName);
        }

        if (finalId == null || finalId <= 0) {
            return List.of();
        }

        final Long locationIdFinal = finalId;

        // Primary: use the JPA repository
        try {
            return locationXPointRepo
                    .findAllByTransferLocationIdOrderByTransferPointNameAsc(locationIdFinal)
                    .stream()
                    .filter(lxp -> lxp.getTransferPoint() != null && lxp.getTransferPoint().isActive())
                    .map(lxp -> new LookupOptionDto(lxp.getTransferPoint().getId(), lxp.getTransferPoint().getName()))
                    .toList();
        } catch (Exception ignored) {
            // fallback to raw JDBC
        }

        // Fallback: raw SQL in case of lazy loading or schema mismatch
        final List<String[]> sqlCandidates = List.of(
                new String[]{
                        "SELECT tp.id, tp.name FROM transfer_points tp " +
                        "JOIN locations_x_points lxp ON lxp.transfer_point_id = tp.id " +
                        "WHERE lxp.transfer_location_id = ? AND tp.active = 1 ORDER BY tp.name",
                        "id", "name"
                },
                new String[]{
                        "SELECT tp.id, tp.nombre AS name FROM transfer_points tp " +
                        "JOIN locations_x_points lxp ON lxp.transfer_point_id = tp.id " +
                        "WHERE lxp.transfer_location_id = ? AND tp.activo = 1 ORDER BY tp.nombre",
                        "id", "name"
                },
                new String[]{
                        "SELECT tp.id, tp.name FROM transfer_points tp " +
                        "JOIN locations_x_points lxp ON lxp.transfer_point_id = tp.id " +
                        "WHERE lxp.transfer_location_id = ? ORDER BY tp.name",
                        "id", "name"
                }
        );

        for (String[] candidate : sqlCandidates) {
            try {
                final String sql = candidate[0];
                final String idCol = candidate[1];
                final String nameCol = candidate[2];
                List<LookupOptionDto> result = jdbc.query(
                        sql,
                        (rs, rowNum) -> new LookupOptionDto(rs.getLong(idCol), rs.getString(nameCol)),
                        locationIdFinal
                );
                if (result != null && !result.isEmpty()) return result;
            } catch (Exception ignored) {
            }
        }

        return List.of();
    }

    /** Resolves a transfer_location id by name (case-insensitive). */
    private Long resolveTransferLocationIdByName(String name) {
        if (name == null || name.isBlank()) return null;
        final List<String> sqls = List.of(
                "SELECT id FROM transfer_locations WHERE LOWER(TRIM(name)) = LOWER(TRIM(?)) AND active = 1 LIMIT 1",
                "SELECT id FROM transfer_locations WHERE LOWER(TRIM(nombre)) = LOWER(TRIM(?)) AND activo = 1 LIMIT 1",
                "SELECT id FROM transfer_location WHERE LOWER(TRIM(name)) = LOWER(TRIM(?)) AND active = 1 LIMIT 1",
                "SELECT id FROM transfer_ba_places WHERE LOWER(TRIM(name)) = LOWER(TRIM(?)) AND active = 1 LIMIT 1",
                "SELECT id FROM transfer_locations WHERE LOWER(TRIM(name)) = LOWER(TRIM(?)) LIMIT 1"
        );
        for (String sql : sqls) {
            try {
                List<Long> ids = jdbc.query(sql, (rs, i) -> rs.getLong("id"), name);
                if (!ids.isEmpty() && ids.get(0) != null && ids.get(0) > 0) return ids.get(0);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    @GetMapping({"/transfer-ba-places", "/transfer-ba-pickup-destinations"})
    public List<LookupOptionDto> listTransferBaPlaces() {
        // Preferir tabla catálogo si existe (DB nueva). Si no existe, devolver un listado dinámico
        // en base a los servicios cargados (group/member).
        List<LookupOptionDto> fromCatalog = tryCatalogPlaces(
                List.of(
                        "SELECT id, name FROM transfer_locations WHERE active = 1 ORDER BY name",
                        "SELECT id, nombre AS name FROM transfer_locations WHERE activo = 1 ORDER BY nombre",
                        "SELECT id, name FROM transfer_location WHERE active = 1 ORDER BY name",
                        "SELECT id, nombre AS name FROM transfer_location WHERE activo = 1 ORDER BY nombre",
                        "SELECT id, name FROM transfer_ba_places WHERE active = 1 ORDER BY name",
                        "SELECT id, nombre AS name FROM transfer_ba_places WHERE activo = 1 ORDER BY nombre",
                        "SELECT id, nombre AS name FROM transfer_ba_lugares WHERE activo = 1 ORDER BY nombre",
                        "SELECT id, nombre AS name FROM traslados_ba_lugares WHERE activo = 1 ORDER BY nombre"
                )
        );
        if (fromCatalog != null) return fromCatalog;

        final String qWithMembers =
                "SELECT DISTINCT TRIM(p) AS name FROM (" +
                        "  SELECT pickup_place AS p FROM group_transfer_service WHERE pickup_place IS NOT NULL AND TRIM(pickup_place) <> ''" +
                        "  UNION " +
                        "  SELECT destination_place AS p FROM group_transfer_service WHERE destination_place IS NOT NULL AND TRIM(destination_place) <> ''" +
                        "  UNION " +
                        "  SELECT pickup_place AS p FROM member_transfer_service WHERE pickup_place IS NOT NULL AND TRIM(pickup_place) <> ''" +
                        "  UNION " +
                        "  SELECT destination_place AS p FROM member_transfer_service WHERE destination_place IS NOT NULL AND TRIM(destination_place) <> ''" +
                        ") t ORDER BY name";

        final String qGroupOnly =
                "SELECT DISTINCT TRIM(p) AS name FROM (" +
                        "  SELECT pickup_place AS p FROM group_transfer_service WHERE pickup_place IS NOT NULL AND TRIM(pickup_place) <> ''" +
                        "  UNION " +
                        "  SELECT destination_place AS p FROM group_transfer_service WHERE destination_place IS NOT NULL AND TRIM(destination_place) <> ''" +
                        ") t ORDER BY name";

        try {
            return jdbc.query(qWithMembers, (rs, rowNum) -> new LookupOptionDto((long) (rowNum + 1), rs.getString("name")));
        } catch (Exception ignored) {
            return jdbc.query(qGroupOnly, (rs, rowNum) -> new LookupOptionDto((long) (rowNum + 1), rs.getString("name")));
        }
    }

    @GetMapping({"/transfer-destino-places", "/transfer-destino-pickup-destinations"})
    public List<LookupOptionDto> listTransferDestinoPlaces() {
        // Preferir tabla catálogo si existe (DB nueva). Si no existe, devolver un listado dinámico
        // en base a los servicios cargados (group/member).
        List<LookupOptionDto> fromCatalog = tryCatalogPlaces(
                List.of(
                        "SELECT id, name FROM transfer_locations WHERE active = 1 ORDER BY name",
                        "SELECT id, nombre AS name FROM transfer_locations WHERE activo = 1 ORDER BY nombre",
                        "SELECT id, name FROM transfer_location WHERE active = 1 ORDER BY name",
                        "SELECT id, nombre AS name FROM transfer_location WHERE activo = 1 ORDER BY nombre",
                        "SELECT id, name FROM transfer_destino_places WHERE active = 1 ORDER BY name",
                        "SELECT id, nombre AS name FROM transfer_destino_places WHERE activo = 1 ORDER BY nombre",
                        "SELECT id, nombre AS name FROM transfer_destino_lugares WHERE activo = 1 ORDER BY nombre",
                        "SELECT id, nombre AS name FROM traslados_destino_lugares WHERE activo = 1 ORDER BY nombre"
                )
        );
        if (fromCatalog != null) return fromCatalog;

        final String qWithMembers =
                "SELECT DISTINCT TRIM(p) AS name FROM (" +
                        "  SELECT pickup_place AS p FROM group_destination_transfer_service WHERE pickup_place IS NOT NULL AND TRIM(pickup_place) <> ''" +
                        "  UNION " +
                        "  SELECT destination_place AS p FROM group_destination_transfer_service WHERE destination_place IS NOT NULL AND TRIM(destination_place) <> ''" +
                        "  UNION " +
                        "  SELECT pickup_place AS p FROM member_destination_transfer_service WHERE pickup_place IS NOT NULL AND TRIM(pickup_place) <> ''" +
                        "  UNION " +
                        "  SELECT destination_place AS p FROM member_destination_transfer_service WHERE destination_place IS NOT NULL AND TRIM(destination_place) <> ''" +
                        ") t ORDER BY name";

        final String qGroupOnly =
                "SELECT DISTINCT TRIM(p) AS name FROM (" +
                        "  SELECT pickup_place AS p FROM group_destination_transfer_service WHERE pickup_place IS NOT NULL AND TRIM(pickup_place) <> ''" +
                        "  UNION " +
                        "  SELECT destination_place AS p FROM group_destination_transfer_service WHERE destination_place IS NOT NULL AND TRIM(destination_place) <> ''" +
                        ") t ORDER BY name";

        try {
            return jdbc.query(qWithMembers, (rs, rowNum) -> new LookupOptionDto((long) (rowNum + 1), rs.getString("name")));
        } catch (Exception ignored) {
            return jdbc.query(qGroupOnly, (rs, rowNum) -> new LookupOptionDto((long) (rowNum + 1), rs.getString("name")));
        }
    }

    private List<LookupOptionDto> dedupeLookupOptions(List<LookupOptionDto> input) {
        if (input == null || input.isEmpty()) return List.of();

        java.util.LinkedHashMap<String, LookupOptionDto> unique = new java.util.LinkedHashMap<>();
        for (LookupOptionDto item : input) {
            if (item == null || item.name() == null) continue;
            String normalized = item.name().trim();
            if (normalized.isBlank()) continue;
            unique.putIfAbsent(normalized.toLowerCase(Locale.ROOT), new LookupOptionDto(item.id(), normalized));
        }
        return new java.util.ArrayList<>(unique.values());
    }

    private List<LookupOptionDto> tryCatalogPlaces(List<String> sqlCandidates) {
        if (sqlCandidates == null || sqlCandidates.isEmpty()) return null;
        for (String sql : sqlCandidates) {
            try {
                List<LookupOptionDto> res = jdbc.query(sql, (rs, rowNum) -> new LookupOptionDto(rs.getLong("id"), rs.getString("name")));
                // Si existe tabla pero está vacía, considerar fallback dinámico
                if (res != null && !res.isEmpty() && res.stream().allMatch(Objects::nonNull)) {
                    return dedupeLookupOptions(res);
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    @GetMapping("/banks")
    public List<LookupOptionDto> listBanks() {
        return jdbc.query(
                "SELECT id, name FROM banks WHERE active = 1 ORDER BY name",
                (rs, rowNum) -> new LookupOptionDto(rs.getLong("id"), rs.getString("name"))
        );
    }

    @GetMapping("/banks/{bankId}/cards")
    public List<LookupOptionDto> listBankCards(@PathVariable long bankId) {
        // Si existe la columna nro_tarjeta (DB nueva), concatenar: "name - nro_tarjeta".
        // Si no existe (DB vieja), fallback a solo name.
        try {
            return jdbc.query(
                    "SELECT c.id, CASE WHEN c.nro_tarjeta IS NULL OR TRIM(c.nro_tarjeta) = '' THEN c.name ELSE CONCAT(c.name, ' - ', c.nro_tarjeta) END AS name " +
                            "FROM bank_cards c WHERE c.bank_id = ? AND c.active = 1 ORDER BY c.name",
                    (rs, rowNum) -> new LookupOptionDto(rs.getLong("id"), rs.getString("name")),
                    bankId
            );
        } catch (Exception ignored) {
            return jdbc.query(
                    "SELECT c.id, c.name FROM bank_cards c WHERE c.bank_id = ? AND c.active = 1 ORDER BY c.name",
                    (rs, rowNum) -> new LookupOptionDto(rs.getLong("id"), rs.getString("name")),
                    bankId
            );
        }
    }


    @GetMapping("/assistance-providers")
    public List<LookupOptionDto> listAssistanceProviders() {
        return jdbc.query(
                "SELECT id, nombre FROM proveedores_asistencia WHERE activo = 1 ORDER BY nombre",
                (rs, rowNum) -> new LookupOptionDto(rs.getLong("id"), rs.getString("nombre"))
        );
    }

    @GetMapping("/assistance-providers/{providerId}/plans")
    public List<LookupOptionDto> listAssistancePlans(@PathVariable long providerId) {
        return jdbc.query(
                "SELECT p.id, p.nombre " +
                        "FROM planes_asistencia p " +
                        "JOIN proveedores_planes_asistencia ppa ON ppa.id_plan = p.id " +
                        "WHERE ppa.id_proveedor = ? AND p.activo = 1 " +
                        "ORDER BY p.nombre",
                (rs, rowNum) -> new LookupOptionDto(rs.getLong("id"), rs.getString("nombre")),
                providerId
        );
    }

    @GetMapping("/airlines")
    public List<LookupOptionDto> listAirlines() {
        return jdbc.query(
                "SELECT id, descripcion FROM aerolineas WHERE activo = 1 ORDER BY descripcion",
                (rs, rowNum) -> new LookupOptionDto(rs.getLong("id"), rs.getString("descripcion"))
        );
    }

    @GetMapping({"/airlines/details", "/airlines/contact"})
    public Map<String, String> getAirlineDetails(@RequestParam(value = "name", required = false) String name,
                                                  @RequestParam(value = "id",   required = false) Long id) {
        try {
            Map<String, String> result = null;
            if (id != null) {
                result = jdbc.query(
                    "SELECT descripcion, telefono, web, iata_code FROM aerolineas WHERE id = ? AND activo = 1",
                    rs -> {
                        if (!rs.next()) return null;
                        Map<String, String> m = new LinkedHashMap<>();
                        m.put("nombre",    rs.getString("descripcion"));
                        m.put("telefono",  rs.getString("telefono"));
                        m.put("web",       rs.getString("web"));
                        m.put("iataCode",  rs.getString("iata_code"));
                        return m;
                    },
                    id
                );
            }
            if (result == null && name != null && !name.isBlank()) {
                result = jdbc.query(
                    "SELECT descripcion, telefono, web, iata_code FROM aerolineas WHERE descripcion = ? AND activo = 1 LIMIT 1",
                    rs -> {
                        if (!rs.next()) return null;
                        Map<String, String> m = new LinkedHashMap<>();
                        m.put("nombre",    rs.getString("descripcion"));
                        m.put("telefono",  rs.getString("telefono"));
                        m.put("web",       rs.getString("web"));
                        m.put("iataCode",  rs.getString("iata_code"));
                        return m;
                    },
                    name.trim()
                );
            }
            if (result == null && name != null && !name.isBlank()) {
                // fallback: búsqueda parcial
                result = jdbc.query(
                    "SELECT descripcion, telefono, web, iata_code FROM aerolineas WHERE descripcion LIKE ? AND activo = 1 LIMIT 1",
                    rs -> {
                        if (!rs.next()) return null;
                        Map<String, String> m = new LinkedHashMap<>();
                        m.put("nombre",    rs.getString("descripcion"));
                        m.put("telefono",  rs.getString("telefono"));
                        m.put("web",       rs.getString("web"));
                        m.put("iataCode",  rs.getString("iata_code"));
                        return m;
                    },
                    "%" + name.trim() + "%"
                );
            }
            return result != null ? result : Map.of("telefono", "", "web", "", "iataCode", "");
        } catch (Exception e) {
            return Map.of("telefono", "", "web", "", "iataCode", "");
        }
    }

    @GetMapping("/accommodations")
    public List<LookupOptionDto> listAccommodations(
            @RequestParam(value = "provider", required = false) String provider,
            @RequestParam(value = "city",     required = false) String city
    ) {
        final String p = provider == null ? "" : provider.trim();
        final String c = city     == null ? "" : city.trim();

        // Filtro por proveedor Y ciudad
        if (!p.isEmpty() && !c.isEmpty()) {
            String cityWhere = buildCityWhereClause("ci.descripcion", c);
            return jdbc.query(
                "SELECT a.id, a.descripcion AS nombre " +
                "FROM alojamientos a " +
                "JOIN ciudades ci ON ci.id = a.id_ciudad " +
                "JOIN prestadores_proveedores pp ON pp.id_prestador = a.id " +
                "JOIN proveedores_alojamientos pa ON pa.id = pp.id_proveedor " +
                "WHERE pa.activo = 1 AND a.activo = 1 " +
                "AND LOWER(pa.nombre) = LOWER(?) " +
                "AND (" + cityWhere + ") " +
                "ORDER BY a.descripcion",
                (rs, rowNum) -> new LookupOptionDto(rs.getLong("id"), rs.getString("nombre")),
                buildCityArgs(p, c)
            );
        }

        // Solo por proveedor
        if (!p.isEmpty()) {
            return listAccommodationPrestadoresByProviderName(p);
        }

        // Solo por ciudad (incluyendo ciudades compuestas ej: "ushuaia-calafate")
        if (!c.isEmpty()) {
            String cityWhere = buildCityWhereClause("ci.descripcion", c);
            return jdbc.query(
                "SELECT a.id, a.descripcion " +
                "FROM alojamientos a " +
                "JOIN ciudades ci ON ci.id = a.id_ciudad " +
                "WHERE a.activo = 1 AND (" + cityWhere + ") " +
                "ORDER BY a.descripcion",
                (rs, rowNum) -> new LookupOptionDto(rs.getLong("id"), rs.getString("descripcion")),
                buildCityArgs(null, c)
            );
        }

        // Sin filtro — todos
        return jdbc.query(
                "SELECT id, descripcion FROM alojamientos WHERE activo = 1 ORDER BY descripcion",
                (rs, rowNum) -> new LookupOptionDto(rs.getLong("id"), rs.getString("descripcion"))
        );
    }

    @GetMapping({"/accommodation-providers", "/proveedores-alojamientos", "/providers/accommodations"})
    public List<LookupOptionDto> listAccommodationProviders() {
        return jdbc.query(
                "SELECT id, nombre FROM proveedores_alojamientos WHERE activo = 1 ORDER BY nombre",
                (rs, rowNum) -> new LookupOptionDto(rs.getLong("id"), rs.getString("nombre"))
        );
    }

    @GetMapping("/accommodation-providers/{providerId}/accommodations")
    public List<LookupOptionDto> listAccommodationPrestadoresByProviderId(
            @PathVariable long providerId
    ) {
        return jdbc.query(
                "SELECT a.id, a.descripcion AS nombre " +
                        "FROM alojamientos a " +
                        "JOIN prestadores_proveedores pp ON pp.id_prestador = a.id " +
                        "JOIN proveedores_alojamientos pa ON pa.id = pp.id_proveedor " +
                        "WHERE pa.activo = 1 AND a.activo = 1 AND pp.id_proveedor = ? " +
                        "ORDER BY a.descripcion",
                (rs, rowNum) -> new LookupOptionDto(rs.getLong("id"), rs.getString("nombre")),
                providerId
        );
    }
    @GetMapping({"/accommodation-prestadores", "/prestadores"})
    public List<LookupOptionDto> listAccommodationPrestadoresByProvider(
            @RequestParam(value = "provider") String provider
    ) {
        return listAccommodationPrestadoresByProviderName(provider);
    }

    @GetMapping("/providers/{provider}/prestadores")
    public List<LookupOptionDto> listAccommodationPrestadoresByProviderPath(
            @PathVariable String provider
    ) {
        return listAccommodationPrestadoresByProviderName(provider);
    }

    private List<LookupOptionDto> listAccommodationPrestadoresByProviderName(String provider) {
        final String p = provider == null ? "" : provider.trim();
        if (p.isEmpty()) return List.of();

        return jdbc.query(
                "SELECT a.id, a.descripcion AS nombre " +
                        "FROM alojamientos a " +
                        "JOIN prestadores_proveedores pp ON pp.id_prestador = a.id " +
                        "JOIN proveedores_alojamientos pa ON pa.id = pp.id_proveedor " +
                        "WHERE pa.activo = 1 AND a.activo = 1 AND LOWER(pa.nombre) = LOWER(?) " +
                        "ORDER BY a.descripcion",
                (rs, rowNum) -> new LookupOptionDto(rs.getLong("id"), rs.getString("nombre")),
                p
        );
    }

    @GetMapping("/cities")
    public List<LookupOptionDto> listCities(@RequestParam(value = "country", required = false) String country) {
        final String c = country == null ? "" : country.trim();

        if (!c.isEmpty()) {
            List<LookupOptionDto> byCountry = jdbc.query(
                    "SELECT c.id, c.descripcion " +
                            "FROM ciudades c " +
                            "JOIN paises_x_ciudades pxc ON pxc.id_ciudad = c.id " +
                            "JOIN paises p ON p.id = pxc.id_pais " +
                            "WHERE p.descripcion = ? AND p.activo = 1 AND c.activo = 1 " +
                            "ORDER BY c.descripcion",
                    (rs, rowNum) -> new LookupOptionDto(rs.getLong("id"), rs.getString("descripcion")),
                    c
            );

            if (!byCountry.isEmpty()) {
                return byCountry;
            }
        }

        return jdbc.query(
                "SELECT id, descripcion FROM ciudades WHERE activo = 1 ORDER BY descripcion",
                (rs, rowNum) -> new LookupOptionDto(rs.getLong("id"), rs.getString("descripcion"))
        );
    }

    @GetMapping({"/countries", "/paises"})
    public List<LookupOptionDto> listCountries() {
        return jdbc.query(
                "SELECT id, descripcion FROM paises WHERE activo = 1 ORDER BY descripcion",
                (rs, rowNum) -> new LookupOptionDto(rs.getLong("id"), rs.getString("descripcion"))
        );
    }

    @GetMapping({"/air-destinations-by-city", "/destinos-aereos-por-ciudad"})
    public List<LookupOptionDto> listAirDestinationsByCity(
            @RequestParam(value = "city", required = false) String city
    ) {
        final String cityParam = city == null ? "" : city.trim();
        if (cityParam.isEmpty()) return listAirDestinations();

        // La tabla destinos_aereos tiene campo "ciudad" con texto libre (ej: "San Carlos de Bariloche")
        // Intentamos con distintas variantes de columna iata y ciudad
        final List<String[]> candidates = List.of(
            // campo ciudad + aeropuerto como nombre, iata como código
            new String[]{"ciudad", "aeropuerto", "iata"},
            new String[]{"ciudad", "aeropuerto", "iata_code"},
            new String[]{"ciudad", "aeropuerto", null},
            // campo ciudad + descripcion, iata_code
            new String[]{"ciudad", "descripcion", "iata_code"},
            new String[]{"ciudad", "descripcion", "iata"},
            new String[]{"ciudad", "descripcion", null}
        );

        for (String[] cand : candidates) {
            try {
                String cityCol   = cand[0]; // columna para filtrar por ciudad
                String nameCol   = cand[1]; // columna para el nombre del aeropuerto
                String iataCol   = cand[2]; // columna iata (nullable)
                String iataExpr  = iataCol != null
                    ? "CASE WHEN " + iataCol + " IS NULL OR TRIM(" + iataCol + ") = '' THEN " + nameCol + " ELSE CONCAT(" + nameCol + ", ' (', " + iataCol + ", ')') END"
                    : nameCol;
                String sql = "SELECT id, " + iataExpr + " AS label FROM destinos_aereos " +
                    "WHERE activo = 1 AND LOWER(" + cityCol + ") LIKE LOWER(CONCAT('%',?,'%')) ORDER BY " + nameCol;
                List<LookupOptionDto> out = jdbc.query(sql,
                    (rs, rowNum) -> new LookupOptionDto(rs.getLong("id"), rs.getString("label")),
                    cityParam);
                if (out != null && !out.isEmpty()) return out;
            } catch (Exception ignored) { /* columna no existe, siguiente */ }
        }
        // Fallback: todos sin filtro
        return listAirDestinations();
    }

    @GetMapping({"/air-destinations", "/destinos-aereos"})
    public List<LookupOptionDto> listAirDestinations() {
        final List<Supplier<List<LookupOptionDto>>> attempts = List.of(
                // destinos_aereos (descripcion + iata_code)
                () -> jdbc.query(
                        "SELECT id, CASE WHEN iata_code IS NULL OR TRIM(iata_code) = '' THEN descripcion ELSE CONCAT(descripcion, ' (', iata_code, ')') END AS label " +
                                "FROM destinos_aereos WHERE activo = 1 ORDER BY descripcion",
                        (rs, rowNum) -> new LookupOptionDto(rs.getLong("id"), rs.getString("label"))
                ),
                // destinos_aereos (descripcion + iata_code) sin activo
                () -> jdbc.query(
                        "SELECT id, CASE WHEN iata_code IS NULL OR TRIM(iata_code) = '' THEN descripcion ELSE CONCAT(descripcion, ' (', iata_code, ')') END AS label " +
                                "FROM destinos_aereos ORDER BY descripcion",
                        (rs, rowNum) -> new LookupOptionDto(rs.getLong("id"), rs.getString("label"))
                ),
                // destinos_aereos (descripcion + iata)
                () -> jdbc.query(
                        "SELECT id, CASE WHEN iata IS NULL OR TRIM(iata) = '' THEN descripcion ELSE CONCAT(descripcion, ' (', iata, ')') END AS label " +
                                "FROM destinos_aereos WHERE activo = 1 ORDER BY descripcion",
                        (rs, rowNum) -> new LookupOptionDto(rs.getLong("id"), rs.getString("label"))
                ),
                // destinos_aereos (descripcion + codigo_iata)
                () -> jdbc.query(
                        "SELECT id, CASE WHEN codigo_iata IS NULL OR TRIM(codigo_iata) = '' THEN descripcion ELSE CONCAT(descripcion, ' (', codigo_iata, ')') END AS label " +
                                "FROM destinos_aereos WHERE activo = 1 ORDER BY descripcion",
                        (rs, rowNum) -> new LookupOptionDto(rs.getLong("id"), rs.getString("label"))
                ),
                // destinos_aereos (solo descripcion)
                () -> jdbc.query(
                        "SELECT id, descripcion AS label FROM destinos_aereos WHERE activo = 1 ORDER BY descripcion",
                        (rs, rowNum) -> new LookupOptionDto(rs.getLong("id"), rs.getString("label"))
                ),
                // air_destinations table (fallback)
                () -> jdbc.query(
                        "SELECT id, CASE WHEN iata_code IS NULL OR TRIM(iata_code) = '' THEN name ELSE CONCAT(name, ' (', iata_code, ')') END AS label " +
                                "FROM air_destinations WHERE activo = 1 ORDER BY name",
                        (rs, rowNum) -> new LookupOptionDto(rs.getLong("id"), rs.getString("label"))
                ),
                () -> jdbc.query(
                        "SELECT id, name AS label FROM air_destinations ORDER BY name",
                        (rs, rowNum) -> new LookupOptionDto(rs.getLong("id"), rs.getString("label"))
                )
        );

        for (Supplier<List<LookupOptionDto>> s : attempts) {
            try {
                final List<LookupOptionDto> out = s.get();
                if (out != null) return out;
            } catch (Exception ignored) {
                // siguiente query
            }
        }

        return List.of();
    }

    @GetMapping("/airports")
    public List<LookupOptionDto> listAirports() {
        final List<Supplier<List<LookupOptionDto>>> attempts = List.of(
                // airports (name + iata_code)
                () -> jdbc.query(
                        "SELECT id, CASE WHEN iata_code IS NULL OR TRIM(iata_code) = '' THEN name ELSE CONCAT(name, ' (', iata_code, ')') END AS label " +
                                "FROM airports WHERE activo = 1 ORDER BY name",
                        (rs, rowNum) -> new LookupOptionDto(rs.getLong("id"), rs.getString("label"))
                ),
                // airports sin activo
                () -> jdbc.query(
                        "SELECT id, CASE WHEN iata_code IS NULL OR TRIM(iata_code) = '' THEN name ELSE CONCAT(name, ' (', iata_code, ')') END AS label " +
                                "FROM airports ORDER BY name",
                        (rs, rowNum) -> new LookupOptionDto(rs.getLong("id"), rs.getString("label"))
                ),
                // aeropuertos (descripcion + iata_code)
                () -> jdbc.query(
                        "SELECT id, CASE WHEN iata_code IS NULL OR TRIM(iata_code) = '' THEN descripcion ELSE CONCAT(descripcion, ' (', iata_code, ')') END AS label " +
                                "FROM aeropuertos WHERE activo = 1 ORDER BY descripcion",
                        (rs, rowNum) -> new LookupOptionDto(rs.getLong("id"), rs.getString("label"))
                ),
                // aeropuertos (descripcion + iata)
                () -> jdbc.query(
                        "SELECT id, CASE WHEN iata IS NULL OR TRIM(iata) = '' THEN descripcion ELSE CONCAT(descripcion, ' (', iata, ')') END AS label " +
                                "FROM aeropuertos WHERE activo = 1 ORDER BY descripcion",
                        (rs, rowNum) -> new LookupOptionDto(rs.getLong("id"), rs.getString("label"))
                ),
                // aeropuertos (solo descripcion)
                () -> jdbc.query(
                        "SELECT id, descripcion AS label FROM aeropuertos WHERE activo = 1 ORDER BY descripcion",
                        (rs, rowNum) -> new LookupOptionDto(rs.getLong("id"), rs.getString("label"))
                ),
                // fallback: destinos_aereos
                () -> jdbc.query(
                        "SELECT id, CASE WHEN iata_code IS NULL OR TRIM(iata_code) = '' THEN descripcion ELSE CONCAT(descripcion, ' (', iata_code, ')') END AS label " +
                                "FROM destinos_aereos WHERE activo = 1 ORDER BY descripcion",
                        (rs, rowNum) -> new LookupOptionDto(rs.getLong("id"), rs.getString("label"))
                ),
                () -> jdbc.query(
                        "SELECT id, descripcion AS label FROM destinos_aereos WHERE activo = 1 ORDER BY descripcion",
                        (rs, rowNum) -> new LookupOptionDto(rs.getLong("id"), rs.getString("label"))
                )
        );

        for (Supplier<List<LookupOptionDto>> s : attempts) {
            try {
                final List<LookupOptionDto> out = s.get();
                if (out != null) return out;
            } catch (Exception ignored) {
                // siguiente query
            }
        }

        return List.of();
    }

    @GetMapping({"/accommodations/{accommodationId}/schedule", "/accommodations/{accommodationId}/check-times", "/accommodation-schedules/{accommodationId}"})
    public Map<String, String> getAccommodationSchedule(@PathVariable long accommodationId) {
        final List<Supplier<Map<String, String>>> attempts = List.of(
                () -> jdbc.query(
                        "SELECT axh.check_in_time, axh.check_out_time " +
                                "FROM alojamiento_x_horario axh " +
                                "WHERE axh.id_alojamiento = ?",
                        rs -> rs.next() ? buildAccommodationScheduleResponse(rs.getObject("check_in_time"), rs.getObject("check_out_time")) : Map.of(),
                        accommodationId
                ),
                () -> jdbc.query(
                        "SELECT a.check_in_time, a.check_out_time " +
                                "FROM alojamientos a " +
                                "WHERE a.id = ? AND a.activo = 1",
                        rs -> rs.next() ? buildAccommodationScheduleResponse(rs.getObject("check_in_time"), rs.getObject("check_out_time")) : Map.of(),
                        accommodationId
                ),
                () -> jdbc.query(
                        "SELECT a.hora_check_in, a.hora_check_out " +
                                "FROM alojamientos a " +
                                "WHERE a.id = ? AND a.activo = 1",
                        rs -> rs.next() ? buildAccommodationScheduleResponse(rs.getObject("hora_check_in"), rs.getObject("hora_check_out")) : Map.of(),
                        accommodationId
                ),
                () -> jdbc.query(
                        "SELECT a.checkin_time, a.checkout_time " +
                                "FROM alojamientos a " +
                                "WHERE a.id = ? AND a.activo = 1",
                        rs -> rs.next() ? buildAccommodationScheduleResponse(rs.getObject("checkin_time"), rs.getObject("checkout_time")) : Map.of(),
                        accommodationId
                )
        );

        for (Supplier<Map<String, String>> s : attempts) {
            try {
                final Map<String, String> out = s.get();
                if (out != null && (!Objects.toString(out.get("checkInTime"), "").isBlank() || !Objects.toString(out.get("checkOutTime"), "").isBlank())) {
                    return out;
                }
            } catch (Exception ignored) {
                // siguiente query
            }
        }

        return Map.of();
    }

    @GetMapping("/accommodations/{accommodationId}/regimens")
    public List<RegimenOptionDto> listAccommodationRegimens(@PathVariable long accommodationId) {
        return jdbc.query(
                "SELECT r.id, r.descripcion " +
                        "FROM regimen r " +
                        "JOIN alojamiento_x_regimen axr ON axr.id_regimen = r.id " +
                        "WHERE axr.id_alojamiento = ? AND r.activo = 1 " +
                        "ORDER BY r.descripcion",
                (rs, rowNum) -> {
                    Long id = rs.getLong("id");
                    String label = rs.getString("descripcion");
                    String value = mapRegimenToEnumValue(label);
                    return new RegimenOptionDto(id, value, label);
                },
                accommodationId
        ).stream().filter(o -> o.value() != null && !o.value().isBlank()).toList();
    }

    @GetMapping({"/accommodations/{accommodationId}/details", "/accommodations/{accommodationId}/contact"})
    public Map<String, String> getAccommodationDetails(@PathVariable long accommodationId) {
        final List<String[]> queries = List.of(
            new String[]{ "SELECT direccion, telefono, web FROM alojamientos WHERE id = ? AND activo = 1" },
            new String[]{ "SELECT address AS direccion, phone AS telefono, website AS web FROM alojamientos WHERE id = ? AND activo = 1" }
        );
        for (String[] qArr : queries) {
            try {
                Map<String, String> result = jdbc.query(
                    qArr[0],
                    rs -> {
                        if (!rs.next()) return null;
                        Map<String, String> m = new LinkedHashMap<>();
                        m.put("direccion", rs.getString("direccion"));
                        m.put("telefono",  rs.getString("telefono"));
                        m.put("web",       rs.getString("web"));
                        return m;
                    },
                    accommodationId
                );
                if (result != null) return result;
            } catch (Exception ignored) { /* columna no existe, probar siguiente */ }
        }
        return Map.of("direccion", "", "telefono", "", "web", "");
    }

    private Map<String, String> buildAccommodationScheduleResponse(Object checkInValue, Object checkOutValue) {
        final String checkInTime = normalizeAccommodationTime(checkInValue);
        final String checkOutTime = normalizeAccommodationTime(checkOutValue);

        final Map<String, String> out = new LinkedHashMap<>();
        out.put("checkInTime", checkInTime);
        out.put("checkOutTime", checkOutTime);
        out.put("checkIn", checkInTime);
        out.put("checkOut", checkOutTime);
        out.put("horaCheckIn", checkInTime);
        out.put("horaCheckOut", checkOutTime);
        return out;
    }

    private String normalizeAccommodationTime(Object value) {
        if (value == null) return "";
        if (value instanceof LocalTime localTime) {
            return String.format("%02d:%02d", localTime.getHour(), localTime.getMinute());
        }
        final String raw = value.toString().trim();
        if (raw.isBlank()) return "";
        final java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^(\\d{1,2}):(\\d{2})").matcher(raw);
        if (matcher.find()) {
            return String.format("%02d:%02d", Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
        }
        return raw;
    }

    private String mapRegimenToEnumValue(String label) {
        if (label == null) return null;
        String l = label.trim().toLowerCase(Locale.ROOT);

        if (l.contains("solo") && l.contains("aloj")) return AccommodationRegimen.ROOM_ONLY.name();
        if (l.contains("desay")) return AccommodationRegimen.BREAKFAST.name();
        if (l.contains("media") && l.contains("pensi")) return AccommodationRegimen.HALF_BOARD.name();
        if (l.contains("pensi") && l.contains("completa")) return AccommodationRegimen.FULL_BOARD.name();
        if (l.contains("all") && l.contains("inclusive")) return AccommodationRegimen.ALL_INCLUSIVE.name();

        // fallback: intenta matchear por nombre de enum
        String candidate = label.trim().toUpperCase(Locale.ROOT)
                .replace("Á", "A")
                .replace("É", "E")
                .replace("Í", "I")
                .replace("Ó", "O")
                .replace("Ú", "U")
                .replace("Ü", "U")
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");

        for (AccommodationRegimen r : AccommodationRegimen.values()) {
            if (r.name().equals(candidate)) return r.name();
        }

        return null;
    }

    /**
     * Construye el fragmento WHERE para filtrar por ciudad, soportando ciudades
     * compuestas separadas por "-" (ej: "ushuaia-calafate" → Ushuaia OR Calafate).
     */
    private String buildCityWhereClause(String col, String city) {
        String[] parts = city.split("-");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(" OR ");
            sb.append("LOWER(").append(col).append(") LIKE LOWER(CONCAT('%',?,'%'))");
        }
        return sb.toString();
    }

    /**
     * Arma el array de args para la query con ciudades compuestas.
     * Si provider != null y no vacío, va primero; luego una entrada por cada parte de ciudad.
     */
    private Object[] buildCityArgs(String provider, String city) {
        String[] parts = city.split("-");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (provider != null && !provider.isEmpty()) args.add(provider);
        for (String part : parts) args.add(part.trim());
        return args.toArray();
    }
}
