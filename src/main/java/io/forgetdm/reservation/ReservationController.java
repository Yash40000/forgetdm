package io.forgetdm.reservation;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reservations")
public class ReservationController {
    private final ReservationService svc;
    public ReservationController(ReservationService svc) { this.svc = svc; }

    @GetMapping public List<ReservationEntity> list() { return svc.list(); }

    @PostMapping("/find-and-reserve")
    public ReservationEntity findAndReserve(@RequestBody Map<String, Object> body) {
        return svc.findAndReserve(
                Long.valueOf(String.valueOf(body.get("dataSourceId"))),
                String.valueOf(body.get("table")),
                body.get("criteria") == null ? null : String.valueOf(body.get("criteria")),
                body.get("count") == null ? 1 : Integer.parseInt(String.valueOf(body.get("count"))),
                body.get("reservedBy") == null ? "anonymous" : String.valueOf(body.get("reservedBy")),
                body.get("purpose") == null ? null : String.valueOf(body.get("purpose")),
                body.get("ttlHours") == null ? 24 : Integer.parseInt(String.valueOf(body.get("ttlHours"))));
    }

    @PostMapping("/{id}/release")
    public ReservationEntity release(@PathVariable Long id) { return svc.release(id); }
}
