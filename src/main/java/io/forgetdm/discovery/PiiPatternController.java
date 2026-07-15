package io.forgetdm.discovery;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Custom PII detection patterns (user/group scoped). Secured by discovery.read / discovery.manage. */
@RestController
@RequestMapping("/api/discovery/patterns")
public class PiiPatternController {

    private final PiiPatternService patterns;

    public PiiPatternController(PiiPatternService patterns) {
        this.patterns = patterns;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return patterns.list();
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody PiiPatternService.PatternRequest req) {
        return patterns.create(req);
    }

    @PostMapping("/test")
    public Map<String, Object> test(@RequestBody PiiPatternService.PatternTestRequest req) {
        return patterns.test(req);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        patterns.delete(id);
    }

    @GetMapping("/my-groups")
    public List<Map<String, Object>> myGroups() {
        return patterns.myGroups();
    }
}
