package io.forgetdm.policy;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Lua masking scripts. Under /api/policies → GET = policy.read, mutations = policy.manage (filter). */
@RestController
@RequestMapping("/api/policies/scripts")
public class MaskingScriptController {

    private final MaskingScriptService svc;

    public MaskingScriptController(MaskingScriptService svc) { this.svc = svc; }

    @GetMapping
    public List<MaskingScriptEntity> list() { return svc.list(); }

    /** Create or update by name (the name is the stable handle rules reference in param1). */
    @PostMapping
    public MaskingScriptEntity save(@RequestBody MaskingScriptEntity body) { return svc.save(body); }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) { svc.delete(id); }
}
