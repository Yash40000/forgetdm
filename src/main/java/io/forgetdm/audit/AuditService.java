package io.forgetdm.audit;

import io.forgetdm.security.AccessContext;
import org.springframework.stereotype.Service;

/** Every consequential action lands in the immutable audit ledger (compliance evidence). */
@Service
public class AuditService {
    private final AuditEventRepository repo;
    public AuditService(AuditEventRepository repo) { this.repo = repo; }

    public void log(String actor, String action, String detail) {
        AuditEventEntity e = new AuditEventEntity();
        e.setActor(resolveActor(actor));
        e.setAction(action);
        e.setDetail(detail);
        repo.save(e);
    }

    private String resolveActor(String actor) {
        if (actor != null && !actor.isBlank() && !"system".equalsIgnoreCase(actor)) return actor;
        return AccessContext.current().map(p -> p.username()).orElse(actor == null || actor.isBlank() ? "system" : actor);
    }
}
