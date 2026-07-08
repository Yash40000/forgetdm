package io.forgetdm.audit;

import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/audit")
public class AuditController {
    private final AuditEventRepository repo;
    public AuditController(AuditEventRepository repo) { this.repo = repo; }

    @GetMapping
    public List<AuditEventEntity> latest(@RequestParam(defaultValue = "100") int limit) {
        return repo.findAllByOrderByIdDesc(PageRequest.of(0, Math.min(limit, 500)));
    }
}
