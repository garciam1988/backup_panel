package app.coincidir.api.tenancy.controller;

import app.coincidir.api.tenancy.domain.Branch;
import app.coincidir.api.tenancy.domain.Brand;
import app.coincidir.api.tenancy.repository.BranchRepository;
import app.coincidir.api.tenancy.repository.BrandRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.text.Normalizer;
import java.util.*;

/**
 * PublicTenancyController — endpoints públicos (sin JWT) para que el bot
 * del cliente final pueda:
 *
 *   GET /api/public/tenancy/branches
 *       Lista las sucursales activas de la marca. El frontend del bot la
 *       carga al inicio de cada conversación y arma su menú de
 *       "¿En qué sucursal querés reservar?".
 *
 *   GET /api/public/tenancy/branches/resolve?query=...
 *       Resuelve un texto libre del usuario ("Colegiales", "la de palermo",
 *       "villa crespo") al branch_id correspondiente. Usa matching por slug
 *       primero, luego por nombre normalizado. Tool útil para que el bot
 *       Claude la invoque cuando el cliente le da una pista del local.
 *
 * Por qué público (sin JWT):
 *   El bot conversacional corre en el navegador del cliente final que NO
 *   tiene credenciales. La info expuesta acá (nombre/dirección/teléfono de
 *   sucursales) es pública y necesaria para que el bot funcione. No se
 *   expone nada sensible (no devolvemos brand_id, ni IDs internos cruzables
 *   con otras marcas — solo lo de la marca propia).
 *
 * Resolución de marca:
 *   Hoy hay 1 marca por deploy. El controller toma la primera marca activa.
 *   Si en el futuro hay multi-marca, agregar header X-Brand-Slug para
 *   distinguir (mismo patrón que ya usa BranchResolverService).
 */
@Slf4j
@RestController
@RequestMapping("/api/public/tenancy")
@RequiredArgsConstructor
public class PublicTenancyController {

    private final BrandRepository brandRepo;
    private final BranchRepository branchRepo;

    /**
     * Lista sucursales activas de la marca. Respuesta:
     *   {
     *     "brand": { "slug": "mikhuna-nikkei", "name": "Mikhuna Nikkei" },
     *     "branches": [
     *       { "id": 2, "slug": "default", "name": "Casa Central", "address": ..., ... },
     *       { "id": 3, "slug": "villa-crespo", "name": "Villa Crespo", ... }
     *     ]
     *   }
     */
    @GetMapping("/branches")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> branches() {
        Brand brand = resolveBrand();
        if (brand == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Sin marca configurada"));
        }

        List<Branch> branches = branchRepo.findByBrandIdAndActiveTrueOrderByNameAsc(brand.getId());

        Map<String, Object> brandDto = new LinkedHashMap<>();
        brandDto.put("slug", brand.getSlug());
        brandDto.put("name", brand.getName());

        List<Map<String, Object>> branchDtos = new ArrayList<>();
        for (Branch b : branches) {
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("id", b.getId());
            dto.put("slug", b.getSlug());
            dto.put("name", b.getName());
            dto.put("address", b.getAddress());
            dto.put("phone", b.getPhone());
            dto.put("timezone", b.getTimezone());
            dto.put("isDefault", Boolean.TRUE.equals(b.getDefaultForBrand()));
            branchDtos.add(dto);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("brand", brandDto);
        resp.put("branches", branchDtos);
        return ResponseEntity.ok(resp);
    }

    /**
     * Resuelve un texto libre a una sucursal. Estrategia (en orden):
     *
     *   1. Match exacto por slug                  ("colegiales" -> "colegiales")
     *   2. Match exacto por nombre (sin acentos)  ("Villa Crespo" -> "villa-crespo")
     *   3. Match por substring del nombre         ("villa" -> "villa-crespo" si es única)
     *   4. Match por substring de la dirección    ("santa fe 2300" -> match si única)
     *
     * Si hay ambigüedad (>1 match), devuelve la lista de candidatos para que
     * el bot le pregunte al usuario.
     *
     * Respuesta:
     *   - 1 match: { "matched": true, "branch": { ... } }
     *   - >1 match: { "matched": false, "candidates": [...] }
     *   - 0 match: { "matched": false, "candidates": [] }
     */
    @GetMapping("/branches/resolve")
    @Transactional(readOnly = true)
    public Map<String, Object> resolveBranch(@RequestParam("query") String query) {
        Brand brand = resolveBrand();
        if (brand == null) {
            return Map.of("matched", false, "error", "Sin marca configurada");
        }
        if (query == null || query.isBlank()) {
            return Map.of("matched", false, "candidates", List.of());
        }

        List<Branch> all = branchRepo.findByBrandIdAndActiveTrueOrderByNameAsc(brand.getId());
        String q = normalize(query);

        // 1) Match exacto por slug
        for (Branch b : all) {
            if (normalize(b.getSlug()).equals(q)) {
                return singleMatch(b);
            }
        }
        // 2) Match exacto por nombre
        for (Branch b : all) {
            if (normalize(b.getName()).equals(q)) {
                return singleMatch(b);
            }
        }
        // 3) Substring por nombre
        List<Branch> byNameSubstring = new ArrayList<>();
        for (Branch b : all) {
            if (normalize(b.getName()).contains(q)) byNameSubstring.add(b);
        }
        if (byNameSubstring.size() == 1) return singleMatch(byNameSubstring.get(0));
        if (byNameSubstring.size() > 1) return candidates(byNameSubstring);

        // 4) Substring por dirección
        List<Branch> byAddress = new ArrayList<>();
        for (Branch b : all) {
            if (b.getAddress() != null && normalize(b.getAddress()).contains(q)) {
                byAddress.add(b);
            }
        }
        if (byAddress.size() == 1) return singleMatch(byAddress.get(0));
        if (byAddress.size() > 1) return candidates(byAddress);

        // Sin matches → devolvemos todas para que el bot le pregunte al user.
        return candidates(all);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private Brand resolveBrand() {
        return brandRepo.findAll().stream().findFirst().orElse(null);
    }

    private static String normalize(String s) {
        if (s == null) return "";
        String nfd = Normalizer.normalize(s, Normalizer.Form.NFD);
        return nfd.replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase().trim();
    }

    private Map<String, Object> singleMatch(Branch b) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", b.getId());
        dto.put("slug", b.getSlug());
        dto.put("name", b.getName());
        dto.put("address", b.getAddress());
        dto.put("phone", b.getPhone());
        return Map.of("matched", true, "branch", dto);
    }

    private Map<String, Object> candidates(List<Branch> branches) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Branch b : branches) {
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("id", b.getId());
            dto.put("slug", b.getSlug());
            dto.put("name", b.getName());
            dto.put("address", b.getAddress());
            list.add(dto);
        }
        return Map.of("matched", false, "candidates", list);
    }
}
