package io.forgetdm.provision;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Reference-data value lists. GET → synthetic.read; mutations → synthetic.manage (AccessControlFilter). */
@RestController
@RequestMapping("/api/synthetic/value-lists")
public class ValueListController {

    private final ValueListService svc;

    public ValueListController(ValueListService svc) { this.svc = svc; }

    @GetMapping
    public List<ValueListEntity> list() { return svc.list(); }

    /** Create or update by name (upsert — the name is the stable handle plans reference as @name). */
    @PostMapping
    public ValueListEntity save(@RequestBody ValueListEntity body) { return svc.save(body); }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) { svc.delete(id); }

    /** Seed a list from a live column: SELECT DISTINCT + frequencies (optionally stored as weights). */
    @PostMapping("/import")
    public ValueListEntity importFromColumn(@RequestBody ValueListService.ImportRequest body) {
        return svc.importFromColumn(body);
    }
}
