package io.forgetdm.audit;

import io.forgetdm.common.ApiException;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.AccessControlService;
import io.forgetdm.security.AccessPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuditControllerTenancyTest {

    private final AuditEventRepository repo = mock(AuditEventRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final AuditController controller = new AuditController(repo, audit);

    @Test
    void searchUsesCallerTenantScope() {
        AuditEventEntity event = event("alpha", 7L, 11L, "GROUP");
        when(repo.searchScoped(eq(false), eq(7L), eq(Set.of(11L)),
                anyString(), anyString(), anyString(), anyString(), anyString(),
                any(Instant.class), any(Instant.class), anyString(), any()))
                .thenReturn(new PageImpl<>(List.of(event)));

        Map<String, Object> result = as(alpha(), () -> controller.search(
                null, null, null, null, null, null, null, null, 0, 50));

        assertEquals(1L, result.get("total"));
        verify(repo).searchScoped(eq(false), eq(7L), eq(Set.of(11L)),
                eq(""), eq(""), eq(""), eq(""), eq(""),
                eq(Instant.EPOCH), any(Instant.class), eq(""), any());
        verify(repo, never()).search(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), any(), anyString(), any());
    }

    @Test
    void facetsAndStatsCannotAggregateAnotherTenant() {
        when(repo.distinctActionsScoped(false, 7L, Set.of(11L))).thenReturn(List.of("A"));
        when(repo.distinctCategoriesScoped(false, 7L, Set.of(11L))).thenReturn(List.of("MASKING"));
        when(repo.distinctActorsScoped(false, 7L, Set.of(11L))).thenReturn(List.of("alpha"));
        when(repo.countScoped(false, 7L, Set.of(11L))).thenReturn(3L);
        when(repo.countByOutcomeScoped("FAILURE", false, 7L, Set.of(11L))).thenReturn(1L);

        Map<String, Object> facets = as(alpha(), controller::facets);
        Map<String, Object> stats = as(alpha(), controller::stats);

        assertEquals(List.of("alpha"), facets.get("actors"));
        assertEquals(3L, stats.get("total"));
        assertEquals(1L, stats.get("failures"));
        verify(repo, never()).distinctActors();
        verify(repo, never()).count();
    }

    @Test
    void administratorsReceiveGlobalScopeWithoutASeparateQueryPath() {
        when(repo.searchScoped(eq(true), eq(1L), eq(Set.of(Long.MIN_VALUE)),
                anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), any(), anyString(), any())).thenReturn(new PageImpl<>(List.of()));

        as(admin(), () -> controller.search(null, null, null, null, null,
                null, null, null, 0, 20));

        verify(repo).searchScoped(eq(true), eq(1L), eq(Set.of(Long.MIN_VALUE)),
                anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), any(), anyString(), any());
    }

    @Test
    void onlyAdministratorCanVerifyTheGlobalHashChain() {
        assertThrows(ApiException.class, () -> as(alpha(), controller::verify));
        verify(audit, never()).verifyChain();

        when(audit.verifyChain()).thenReturn(Map.of("valid", true));
        assertEquals(true, as(admin(), controller::verify).get("valid"));
        verify(audit).verifyChain();
    }

    @Test
    void csvExportUsesSameTenantScopeAndAuditsDelivery() {
        AuditEventEntity visible = event("alpha", 7L, 11L, "GROUP");
        when(repo.searchScoped(eq(false), eq(7L), eq(Set.of(11L)),
                anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), any(), anyString(), any())).thenReturn(new PageImpl<>(List.of(visible)));

        ResponseEntity<String> response = as(alpha(), () -> controller.export(
                null, null, null, null, null, null, null, null));

        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("alpha"));
        assertEquals("1", response.getHeaders().getFirst("X-Total-Count"));
        verify(audit).record(isNull(), eq("AUDIT_EXPORTED"), eq("SECURITY"),
                eq("audit"), isNull(), eq("audit-trail.csv"), eq("SUCCESS"), anyString(), anyString());
    }

    private static AuditEventEntity event(String actor, Long owner, Long group, String visibility) {
        AuditEventEntity event = new AuditEventEntity();
        event.setSeq(1L);
        event.setActor(actor);
        event.setAction("TEST");
        event.setCategory("SECURITY");
        event.setOutcome("SUCCESS");
        event.setSeverity("INFO");
        event.setCreatedAt(Instant.parse("2026-07-20T10:00:00Z"));
        event.setOwnerUserId(owner);
        event.setOwnerGroupId(group);
        event.setVisibility(visibility);
        return event;
    }

    private static AccessPrincipal alpha() {
        return new AccessPrincipal(7L, "alpha", "Alpha User", Set.of("AUDITOR"), Set.of("audit.read"),
                List.of(new AccessControlService.GroupLite(11L, "Alpha Group")));
    }

    private static AccessPrincipal admin() {
        return new AccessPrincipal(1L, "admin", "Admin", Set.of("ADMIN"), Set.of("admin.all"), List.of());
    }

    private static <T> T as(AccessPrincipal principal, java.util.function.Supplier<T> action) {
        return AccessContext.callAs(principal, null, action);
    }
}
