package app.coincidir.api.marketing.controller;

import app.coincidir.api.marketing.domain.Coupon;
import app.coincidir.api.marketing.dto.MarketingDtos.CouponDto;
import app.coincidir.api.marketing.service.CouponService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * MarketingCouponController — CRUD de cupones para el panel admin.
 *
 * La aplicación del cupón (consumirlo en una compra) la hace el staff
 * desde StaffLoyaltyController.
 */
@RestController
@RequestMapping("/api/admin/marketing/coupons")
@RequiredArgsConstructor
public class MarketingCouponController {

    private final CouponService couponService;

    @GetMapping
    public Map<String, Object> list(@RequestParam(value = "page", defaultValue = "0") int page,
                                    @RequestParam(value = "size", defaultValue = "20") int size) {
        Page<Coupon> p = couponService.list(PageRequest.of(page, size));
        return Map.of(
            "items", p.getContent().stream().map(CouponDto::fromEntity).toList(),
            "total", p.getTotalElements(),
            "page", p.getNumber(),
            "size", p.getSize()
        );
    }

    @GetMapping("/{id}")
    public ResponseEntity<CouponDto> getOne(@PathVariable Long id) {
        return couponService.findById(id)
            .map(c -> ResponseEntity.ok(CouponDto.fromEntity(c)))
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/by-code/{code}")
    public ResponseEntity<CouponDto> getByCode(@PathVariable String code) {
        return couponService.findByCode(code)
            .map(c -> ResponseEntity.ok(CouponDto.fromEntity(c)))
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Coupon body) {
        try {
            Coupon saved = couponService.create(body);
            return ResponseEntity.status(HttpStatus.CREATED).body(CouponDto.fromEntity(saved));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Coupon body) {
        try {
            Coupon saved = couponService.update(id, body);
            return ResponseEntity.ok(CouponDto.fromEntity(saved));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
