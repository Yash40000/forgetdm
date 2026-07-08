package io.forgetdm.policy;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.core.mask.MaskingEngine;
import io.forgetdm.security.AccessContext;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Registry for user-defined Lua masking scripts (Optim-style exits). Wires itself into the
 * MaskingEngine as the script provider. Job executor threads carry no AccessContext, so only
 * GLOBAL scripts resolve during provisioning; PRIVATE scripts are drafts (owner-only, testable
 * from the Studio on the owner's own request thread).
 */
@Service
public class MaskingScriptService {

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,118}");
    private static final int MAX_SOURCE_CHARS = 20_000;

    private final MaskingScriptRepository repo;
    private final MaskingEngine engine;
    private final AuditService audit;

    public MaskingScriptService(MaskingScriptRepository repo, MaskingEngine engine, AuditService audit) {
        this.repo = repo;
        this.engine = engine;
        this.audit = audit;
    }

    @PostConstruct
    void wireIntoEngine() {
        engine.setScriptProvider(this::sourceForEngine);
    }

    /** Engine resolution: GLOBAL always; PRIVATE only for the owner's own (request) thread. */
    private String sourceForEngine(String name) {
        return repo.findByNameIgnoreCase(name)
                .filter(s -> !"PRIVATE".equalsIgnoreCase(s.getVisibility())
                        || AccessContext.current()
                            .map(p -> p.username().equalsIgnoreCase(String.valueOf(s.getOwnerUsername())))
                            .orElse(false))
                .map(MaskingScriptEntity::getLuaSource)
                .orElse(null);
    }

    public List<MaskingScriptEntity> list() {
        String me = currentUser();
        return repo.findAll().stream()
                .filter(s -> !"PRIVATE".equalsIgnoreCase(s.getVisibility())
                        || (s.getOwnerUsername() != null && s.getOwnerUsername().equalsIgnoreCase(me)))
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .toList();
    }

    public MaskingScriptEntity save(MaskingScriptEntity in) {
        if (in == null || in.getName() == null || in.getName().isBlank())
            throw ApiException.bad("Script name is required (e.g. bank-a.custom-ref)");
        String name = in.getName().trim().toLowerCase(Locale.ROOT);
        if (!NAME.matcher(name).matches())
            throw ApiException.bad("Script name must be lower-case letters/digits/._-");
        if (in.getLuaSource() == null || in.getLuaSource().isBlank())
            throw ApiException.bad("Lua source is required — the script must 'return' the masked value");
        if (in.getLuaSource().length() > MAX_SOURCE_CHARS)
            throw ApiException.bad("Script too large (max " + MAX_SOURCE_CHARS + " characters)");
        String syntaxError = engine.checkScriptSyntax(in.getLuaSource());
        if (syntaxError != null)
            throw ApiException.bad("Lua syntax error — " + syntaxError);
        MaskingScriptEntity e = repo.findByNameIgnoreCase(name).orElseGet(MaskingScriptEntity::new);
        if (e.getId() != null) requireEditable(e);
        if (e.getId() == null) {
            e.setName(name);
            e.setOwnerUsername(currentUser());
        }
        e.setDescription(in.getDescription() == null || in.getDescription().isBlank() ? null : in.getDescription().trim());
        e.setLuaSource(in.getLuaSource());
        e.setVisibility("PRIVATE".equalsIgnoreCase(in.getVisibility()) ? "PRIVATE" : "GLOBAL");
        e.setUpdatedAt(Instant.now());
        MaskingScriptEntity saved = repo.save(e);
        audit.log(currentUser(), "MASKING_SCRIPT_SAVED", saved.getName() + " (" + saved.getVisibility() + ")");
        return saved;
    }

    public void delete(Long id) {
        repo.findById(id).ifPresent(e -> {
            requireEditable(e);
            repo.deleteById(id);
            audit.log(currentUser(), "MASKING_SCRIPT_DELETED", e.getName());
        });
    }

    private void requireEditable(MaskingScriptEntity e) {
        boolean admin = AccessContext.current().map(p -> p.hasPermission("admin.all")).orElse(false);
        if ("PRIVATE".equalsIgnoreCase(e.getVisibility()) && !admin
                && (e.getOwnerUsername() == null || !e.getOwnerUsername().equalsIgnoreCase(currentUser())))
            throw ApiException.bad("Script '" + e.getName() + "' is private to " + e.getOwnerUsername());
    }

    private static String currentUser() {
        return AccessContext.current().map(p -> p.username()).orElse("system");
    }
}
