package app.coincidir.api.botplatform.controller;

import app.coincidir.api.botplatform.domain.TravelPackage;
import app.coincidir.api.botplatform.repository.TravelPackageRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * TravelPackageController — CRUD de paquetes (Fase 5B).
 * Endpoints bajo /api/admin/travel-packages, requieren JWT.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/travel-packages")
@RequiredArgsConstructor
public class TravelPackageController {

    private final TravelPackageRepository repo;

    @GetMapping
    @Transactional(readOnly = true)
    public List<TravelPackageDto> list(@RequestParam(value = "all", defaultValue = "true") boolean all) {
        List<TravelPackage> list = all
                ? repo.findAllByOrderByFechaSalidaAsc()
                : repo.findByActiveTrueOrderByFechaSalidaAsc();
        return list.stream().map(TravelPackageDto::fromEntity).toList();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<TravelPackageDto> getOne(@PathVariable Long id) {
        return repo.findById(id)
                .map(TravelPackageDto::fromEntity)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody TravelPackageDto dto) {
        String err = dto.validate();
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));
        if (repo.findByCodigo(dto.codigo.trim()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Ya existe un paquete con ese código"));
        }
        TravelPackage e = new TravelPackage();
        dto.applyTo(e);
        TravelPackage saved = repo.save(e);
        log.info("travel_package creado: id={}, codigo='{}'", saved.getId(), saved.getCodigo());
        return ResponseEntity.status(HttpStatus.CREATED).body(TravelPackageDto.fromEntity(saved));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody TravelPackageDto dto) {
        Optional<TravelPackage> opt = repo.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        String err = dto.validate();
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));
        TravelPackage e = opt.get();
        dto.applyTo(e);
        TravelPackage saved = repo.save(e);
        return ResponseEntity.ok(TravelPackageDto.fromEntity(saved));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!repo.existsById(id)) return ResponseEntity.notFound().build();
        repo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ─────────────────────────────────────────────────────────────────
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TravelPackageDto {
        public Long       id;
        public String     codigo;
        public String     destino;
        public String     nombre;
        public String     descripcion;
        public LocalDate  fechaSalida;
        public LocalDate  fechaRegreso;
        public Integer    noches;
        public BigDecimal precioAdulto;
        public BigDecimal precioMenor;
        public String     moneda;
        public Integer    cuposDisponibles;
        public Boolean    incluyeVuelo;
        public Boolean    incluyeHotel;
        public Boolean    incluyeTraslados;
        public Boolean    incluyeAsistencia;
        public Boolean    incluyeFerry;
        public String     noIncluyeJson;
        public Boolean    active;
        public Instant    createdAt;
        public Instant    updatedAt;

        public static TravelPackageDto fromEntity(TravelPackage e) {
            TravelPackageDto d = new TravelPackageDto();
            d.id                = e.getId();
            d.codigo            = e.getCodigo();
            d.destino           = e.getDestino();
            d.nombre            = e.getNombre();
            d.descripcion       = e.getDescripcion();
            d.fechaSalida       = e.getFechaSalida();
            d.fechaRegreso      = e.getFechaRegreso();
            d.noches            = e.getNoches();
            d.precioAdulto      = e.getPrecioAdulto();
            d.precioMenor       = e.getPrecioMenor();
            d.moneda            = e.getMoneda();
            d.cuposDisponibles  = e.getCuposDisponibles();
            d.incluyeVuelo      = e.getIncluyeVuelo();
            d.incluyeHotel      = e.getIncluyeHotel();
            d.incluyeTraslados  = e.getIncluyeTraslados();
            d.incluyeAsistencia = e.getIncluyeAsistencia();
            d.incluyeFerry      = e.getIncluyeFerry();
            d.noIncluyeJson     = e.getNoIncluyeJson();
            d.active            = e.getActive();
            d.createdAt         = e.getCreatedAt();
            d.updatedAt         = e.getUpdatedAt();
            return d;
        }

        public void applyTo(TravelPackage e) {
            if (codigo != null)           e.setCodigo(codigo.trim());
            if (destino != null)          e.setDestino(destino.trim().toLowerCase());
            if (nombre != null)           e.setNombre(nombre.trim());
            if (descripcion != null)      e.setDescripcion(descripcion);
            if (fechaSalida != null)      e.setFechaSalida(fechaSalida);
            if (fechaRegreso != null)     e.setFechaRegreso(fechaRegreso);
            if (noches != null)           e.setNoches(noches);
            if (precioAdulto != null)     e.setPrecioAdulto(precioAdulto);
            if (precioMenor != null)      e.setPrecioMenor(precioMenor);
            if (moneda != null)           e.setMoneda(moneda.trim().toUpperCase());
            if (cuposDisponibles != null) e.setCuposDisponibles(cuposDisponibles);
            if (incluyeVuelo != null)     e.setIncluyeVuelo(incluyeVuelo);
            if (incluyeHotel != null)     e.setIncluyeHotel(incluyeHotel);
            if (incluyeTraslados != null) e.setIncluyeTraslados(incluyeTraslados);
            if (incluyeAsistencia != null)e.setIncluyeAsistencia(incluyeAsistencia);
            if (incluyeFerry != null)     e.setIncluyeFerry(incluyeFerry);
            if (noIncluyeJson != null)    e.setNoIncluyeJson(noIncluyeJson);
            if (active != null)           e.setActive(active);
        }

        public String validate() {
            if (codigo == null || codigo.isBlank()) return "codigo es obligatorio";
            if (destino == null || destino.isBlank()) return "destino es obligatorio";
            if (nombre == null || nombre.isBlank()) return "nombre es obligatorio";
            if (precioAdulto == null || precioAdulto.signum() < 0) return "precioAdulto debe ser >= 0";
            return null;
        }
    }
}
