package io.forgetdm.mainframe;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.core.mask.MaskingEngine;
import io.forgetdm.mainframe.transport.MainframeTransport;
import io.forgetdm.mainframe.transport.TransportFactory;
import io.forgetdm.provision.SyntheticGenService;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.AccessControlService;
import io.forgetdm.security.AccessPrincipal;
import io.forgetdm.security.OwnershipGuard;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MainframeServiceTenancyTest {

    private static final AccessPrincipal ALPHA = principal(1L, "alpha", 10L);
    private static final AccessPrincipal ALPHA_PEER = principal(3L, "alpha-peer", 10L);

    @Test
    void jobSubmitCancelAndRetryDenyCrossGroupBeforeSideEffects() {
        MainframeJobRepository jobs = mock(MainframeJobRepository.class);
        MainframeJobFileRepository files = mock(MainframeJobFileRepository.class);
        ExecutorService executor = mock(ExecutorService.class);
        MainframeJobEntity betaJob = job(22L, 2L, 20L, OwnershipGuard.GROUP, "FAILED");
        when(jobs.findById(22L)).thenReturn(Optional.of(betaJob));
        MainframeMaskingService service = maskingService(jobs, files,
                mock(MainframeConnectionRepository.class), mock(CopybookDefRepository.class), executor,
                new OwnershipGuard(mock(AuditService.class)));

        assertForbidden(() -> as(ALPHA, () -> {
            service.submitAsync(22L);
            return null;
        }));
        assertForbidden(() -> as(ALPHA, () -> service.cancel(22L)));
        assertForbidden(() -> as(ALPHA, () -> service.retry(22L)));

        verify(jobs, never()).save(any());
        verifyNoInteractions(files);
        verifyNoInteractions(executor);
    }

    @Test
    void retryAuthorizesReferencedObjectsAndStampsTheNewCaller() {
        MainframeJobRepository jobs = mock(MainframeJobRepository.class);
        MainframeJobFileRepository files = mock(MainframeJobFileRepository.class);
        MainframeConnectionRepository connections = mock(MainframeConnectionRepository.class);
        CopybookDefRepository copybooks = mock(CopybookDefRepository.class);
        ExecutorService executor = mock(ExecutorService.class);
        MainframeJobEntity previous = job(50L, 1L, 10L, OwnershipGuard.GROUP, "FAILED");
        previous.setSourceConnectionId(1L);
        previous.setTargetConnectionId(2L);
        previous.setFilesTotal(1);
        MainframeJobFileEntity priorFile = file(60L, 50L, 3L, 4L, "FAILED");
        when(jobs.findById(50L)).thenReturn(Optional.of(previous));
        AtomicReference<MainframeJobEntity> created = new AtomicReference<>();
        when(jobs.findById(51L)).thenAnswer(call -> Optional.ofNullable(created.get()));
        when(jobs.save(any())).thenAnswer(call -> {
            MainframeJobEntity value = call.getArgument(0);
            if (value.getId() == null) value.setId(51L);
            if (Long.valueOf(51L).equals(value.getId())) created.set(value);
            return value;
        });
        when(files.findByJobIdOrderByOrdinalAsc(50L)).thenReturn(List.of(priorFile));
        when(files.save(any())).thenAnswer(call -> call.getArgument(0));
        when(connections.findById(1L)).thenReturn(Optional.of(connection(1L, 10L)));
        when(connections.findById(2L)).thenReturn(Optional.of(connection(2L, 10L)));
        when(connections.findById(4L)).thenReturn(Optional.of(connection(4L, 10L)));
        when(copybooks.findById(3L)).thenReturn(Optional.of(copybook(3L, 10L)));
        MainframeMaskingService service = maskingService(jobs, files, connections, copybooks, executor,
                new OwnershipGuard(mock(AuditService.class)));

        MainframeJobEntity retry = as(ALPHA_PEER, () -> service.retry(50L));

        assertEquals(51L, retry.getId());
        assertEquals(3L, retry.getOwnerUserId());
        assertEquals("alpha-peer", retry.getOwnerUsername());
        assertEquals(10L, retry.getOwnerGroupId());
        assertEquals(OwnershipGuard.GROUP, retry.getVisibility());
        assertEquals("alpha-peer", retry.getCreatedBy());
        verify(connections).findById(1L);
        verify(connections).findById(2L);
        verify(connections).findById(4L);
        verify(copybooks).findById(3L);
        verify(executor).submit(any(Runnable.class));
    }

    @Test
    void retryRejectsAHiddenReferencedConnectionBeforeCreatingAttempt() {
        MainframeJobRepository jobs = mock(MainframeJobRepository.class);
        MainframeJobFileRepository files = mock(MainframeJobFileRepository.class);
        MainframeConnectionRepository connections = mock(MainframeConnectionRepository.class);
        ExecutorService executor = mock(ExecutorService.class);
        MainframeJobEntity visibleJob = job(70L, 1L, 10L, OwnershipGuard.GROUP, "FAILED");
        visibleJob.setSourceConnectionId(8L);
        visibleJob.setTargetConnectionId(9L);
        when(jobs.findById(70L)).thenReturn(Optional.of(visibleJob));
        when(files.findByJobIdOrderByOrdinalAsc(70L)).thenReturn(List.of());
        when(connections.findById(8L)).thenReturn(Optional.of(connection(8L, 20L)));
        MainframeMaskingService service = maskingService(jobs, files, connections,
                mock(CopybookDefRepository.class), executor, new OwnershipGuard(mock(AuditService.class)));

        assertForbidden(() -> as(ALPHA, () -> service.retry(70L)));

        verify(jobs, never()).save(any());
        verifyNoInteractions(executor);
    }

    @Test
    void generateFileRejectsHiddenCopybookAndTargetBeforeGeneratingOrDelivering() {
        CopybookDefRepository copybooks = mock(CopybookDefRepository.class);
        MainframeConnectionRepository connections = mock(MainframeConnectionRepository.class);
        TransportFactory transports = mock(TransportFactory.class);
        SyntheticGenService synth = mock(SyntheticGenService.class);
        when(copybooks.findById(12L)).thenReturn(Optional.of(copybook(12L, 20L)));
        MainframeGenService service = new MainframeGenService(copybooks, connections, transports, synth,
                mock(AuditService.class), new OwnershipGuard(mock(AuditService.class)));
        MainframeGenService.GenFileReq hiddenCopybook = request(12L, "DOWNLOAD", null);

        assertForbidden(() -> as(ALPHA, () -> service.generateFile(hiddenCopybook)));

        CopybookDefEntity alphaCopybook = copybook(11L, 10L);
        when(copybooks.findById(11L)).thenReturn(Optional.of(alphaCopybook));
        when(connections.findById(21L)).thenReturn(Optional.of(connection(21L, 20L)));
        assertForbidden(() -> as(ALPHA, () -> service.generateFile(request(11L, "DOWNLOAD", 21L))));
        MainframeGenService.GenFileReq hiddenTarget = request(11L, "TARGET", 21L);
        assertForbidden(() -> as(ALPHA, () -> service.generateFile(hiddenTarget)));

        verifyNoInteractions(synth);
        verifyNoInteractions(transports);
    }

    @Test
    void generateFileAllowsSameGroupDelivery() {
        CopybookDefRepository copybooks = mock(CopybookDefRepository.class);
        MainframeConnectionRepository connections = mock(MainframeConnectionRepository.class);
        TransportFactory transports = mock(TransportFactory.class);
        SyntheticGenService synth = mock(SyntheticGenService.class);
        CopybookDefEntity copybook = copybook(11L, 10L);
        MainframeConnectionEntity target = connection(21L, 10L);
        when(copybooks.findById(11L)).thenReturn(Optional.of(copybook));
        when(connections.findById(21L)).thenReturn(Optional.of(target));
        when(synth.generateRows(any(), eq(0L), eq(42L))).thenReturn(List.<LinkedHashMap<String, String>>of());
        MainframeTransport transport = mock(MainframeTransport.class);
        when(transports.forConnection(target)).thenReturn(transport);
        MainframeGenService service = new MainframeGenService(copybooks, connections, transports, synth,
                mock(AuditService.class), new OwnershipGuard(mock(AuditService.class)));

        Map<String, Object> result = as(ALPHA, () -> service.generateFile(request(11L, "TARGET", 21L)));

        assertTrue(result.containsKey("delivered"));
        verify(transport).put(eq(target), eq("customer.dat"), any(byte[].class), eq("FB"), anyInt());
    }

    @Test
    void asynchronousWorkerUsesDirectRepositoriesWithoutRequestAuthorization() {
        MainframeJobRepository jobs = mock(MainframeJobRepository.class);
        MainframeJobEntity canceled = job(90L, 2L, 20L, OwnershipGuard.GROUP, "CANCELED");
        canceled.setCancelRequested(true);
        when(jobs.findById(90L)).thenReturn(Optional.of(canceled));
        OwnershipGuard ownership = mock(OwnershipGuard.class);
        MainframeMaskingService service = maskingService(jobs, mock(MainframeJobFileRepository.class),
                mock(MainframeConnectionRepository.class), mock(CopybookDefRepository.class),
                mock(ExecutorService.class), ownership);

        service.run(90L);

        verifyNoInteractions(ownership);
    }

    @Test
    void asynchronousWorkerFailsOrphanedNonSharedJobBeforeLoadingChildren() {
        MainframeJobRepository jobs = mock(MainframeJobRepository.class);
        MainframeJobFileRepository files = mock(MainframeJobFileRepository.class);
        MainframeJobEntity orphaned = job(91L, null, null, OwnershipGuard.GROUP, "PENDING");
        orphaned.setOwnerUsername("deleted-owner");
        when(jobs.findById(91L)).thenReturn(Optional.of(orphaned));
        when(jobs.save(any())).thenAnswer(call -> call.getArgument(0));
        MainframeMaskingService service = maskingService(jobs, files,
                mock(MainframeConnectionRepository.class), mock(CopybookDefRepository.class),
                mock(ExecutorService.class), mock(OwnershipGuard.class));

        service.run(91L);

        assertEquals("FAILED", orphaned.getStatus());
        assertTrue(orphaned.getMessage().contains("ownership"));
        assertNotNull(orphaned.getFinishedAt());
        verify(jobs).save(orphaned);
        verifyNoInteractions(files);
    }

    @Test
    void asynchronousWorkerRejectsOrphanedReferenceBeforeTransportAccess() {
        MainframeJobRepository jobs = mock(MainframeJobRepository.class);
        MainframeJobFileRepository files = mock(MainframeJobFileRepository.class);
        MainframeConnectionRepository connections = mock(MainframeConnectionRepository.class);
        CopybookDefRepository copybooks = mock(CopybookDefRepository.class);
        CopybookMaskRepository masks = mock(CopybookMaskRepository.class);
        TransportFactory transports = mock(TransportFactory.class);
        MainframeJobEntity queued = job(92L, 1L, 10L, OwnershipGuard.GROUP, "PENDING");
        queued.setSourceConnectionId(1L);
        queued.setTargetConnectionId(2L);
        queued.setFilesTotal(1);
        MainframeJobFileEntity queuedFile = file(93L, 92L, 3L, null, "PENDING");
        MainframeConnectionEntity orphanedSource = connection(1L, 10L);
        orphanedSource.setOwnerUserId(null);
        orphanedSource.setOwnerGroupId(null);
        orphanedSource.setOwnerUsername("deleted-owner");

        when(jobs.findById(92L)).thenReturn(Optional.of(queued));
        when(jobs.save(any())).thenAnswer(call -> call.getArgument(0));
        when(files.findByJobIdOrderByOrdinalAsc(92L)).thenReturn(List.of(queuedFile));
        when(files.save(any())).thenAnswer(call -> call.getArgument(0));
        when(connections.findById(1L)).thenReturn(Optional.of(orphanedSource));
        MainframeMaskingService service = new MainframeMaskingService(jobs, files, connections, copybooks,
                masks, transports, new MaskingEngine("mainframe-tenancy-test"), mock(ExecutorService.class),
                mock(AuditService.class), mock(OwnershipGuard.class));

        service.run(92L);

        assertEquals("FAILED", queued.getStatus());
        assertEquals("FAILED", queuedFile.getStatus());
        assertTrue(queuedFile.getMessage().contains("valid owner"));
        verifyNoInteractions(transports);
        verifyNoInteractions(copybooks);
        verifyNoInteractions(masks);
    }

    private static MainframeMaskingService maskingService(MainframeJobRepository jobs,
                                                           MainframeJobFileRepository files,
                                                           MainframeConnectionRepository connections,
                                                           CopybookDefRepository copybooks,
                                                           ExecutorService executor,
                                                           OwnershipGuard ownership) {
        return new MainframeMaskingService(jobs, files, connections, copybooks,
                mock(CopybookMaskRepository.class), mock(TransportFactory.class),
                new MaskingEngine("mainframe-tenancy-test"), executor, mock(AuditService.class), ownership);
    }

    private static MainframeGenService.GenFileReq request(Long copybookId, String output, Long targetId) {
        return new MainframeGenService.GenFileReq(copybookId, "Cp037", "FB", 42L, 0L,
                List.of(), output, targetId, "customer.dat");
    }

    private static MainframeConnectionEntity connection(Long id, Long groupId) {
        MainframeConnectionEntity value = new MainframeConnectionEntity();
        value.setId(id);
        value.setName("connection-" + id);
        value.setType("LOCAL");
        value.setBaseDir("D:/mainframe");
        value.setOwnerUserId(groupId.equals(10L) ? 1L : 2L);
        value.setOwnerGroupId(groupId);
        value.setVisibility(OwnershipGuard.GROUP);
        return value;
    }

    private static CopybookDefEntity copybook(Long id, Long groupId) {
        CopybookDefEntity value = new CopybookDefEntity();
        value.setId(id);
        value.setName("customer");
        value.setSource(String.join("\n", "01 CUSTOMER-RECORD.", "   05 CUSTOMER-ID PIC X(20)."));
        value.setCodePage("Cp037");
        value.setOwnerUserId(groupId.equals(10L) ? 1L : 2L);
        value.setOwnerGroupId(groupId);
        value.setVisibility(OwnershipGuard.GROUP);
        return value;
    }

    private static MainframeJobEntity job(Long id, Long ownerUserId, Long ownerGroupId,
                                          String visibility, String status) {
        MainframeJobEntity value = new MainframeJobEntity();
        value.setId(id);
        value.setName("job-" + id);
        value.setStatus(status);
        value.setOwnerUserId(ownerUserId);
        value.setOwnerGroupId(ownerGroupId);
        value.setVisibility(visibility);
        return value;
    }

    private static MainframeJobFileEntity file(Long id, Long jobId, Long copybookId,
                                               Long targetConnectionId, String status) {
        MainframeJobFileEntity value = new MainframeJobFileEntity();
        value.setId(id);
        value.setJobId(jobId);
        value.setSourceName("HLQ.INPUT");
        value.setCopybookId(copybookId);
        value.setTargetConnectionId(targetConnectionId);
        value.setStatus(status);
        return value;
    }

    private static AccessPrincipal principal(Long userId, String username, Long groupId) {
        return new AccessPrincipal(userId, username, username, Set.of(), Set.of("mainframe.manage"),
                List.of(new AccessControlService.GroupLite(groupId, "group-" + groupId)));
    }

    private static <T> T as(AccessPrincipal principal, Supplier<T> work) {
        return AccessContext.callAs(principal, null, work);
    }

    private static void assertForbidden(org.junit.jupiter.api.function.Executable action) {
        ApiException error = assertThrows(ApiException.class, action);
        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
    }
}
