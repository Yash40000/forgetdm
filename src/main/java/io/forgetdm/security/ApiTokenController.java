package io.forgetdm.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/auth/tokens")
public class ApiTokenController {
    private final AccessControlService access;

    public ApiTokenController(AccessControlService access) { this.access = access; }

    @GetMapping
    public List<AccessControlService.ApiTokenView> list() { return access.apiTokens(); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccessControlService.ApiTokenCreated create(@RequestBody AccessControlService.ApiTokenRequest request) {
        return access.createApiToken(request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable String id) { access.revokeApiToken(id); }
}
