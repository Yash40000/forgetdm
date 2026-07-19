package io.forgetdm.discovery;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DiscoveryJobServiceTest {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void requestedCancellationSurvivesScannerExceptionTranslation() throws Exception {
        DiscoveryService discovery = mock(DiscoveryService.class);
        AuditService audit = mock(AuditService.class);
        CountDownLatch scanStarted = new CountDownLatch(1);
        CountDownLatch continueScan = new CountDownLatch(1);
        when(discovery.validateScanScope(2L, "public", Set.of())).thenReturn(List.of("accounts"));
        when(discovery.scan(eq(2L), eq("public"), isNull(), isNull(), any())).thenAnswer(invocation -> {
            DiscoveryService.ScanProgress progress = invocation.getArgument(4);
            progress.schemaResolved("public");
            progress.tablesDiscovered(List.of("accounts"));
            progress.tableStarted("accounts", 1, 1);
            scanStarted.countDown();
            assertTrue(continueScan.await(5, TimeUnit.SECONDS));
            try {
                progress.columnScanned("accounts", "account_id", 1, 1);
                return List.of();
            } catch (RuntimeException cancelled) {
                throw ApiException.bad("Discovery scan failed: " + cancelled.getMessage());
            }
        });
        DiscoveryJobService service = new DiscoveryJobService(discovery, executor, audit);

        DiscoveryJobService.JobSnapshot started = service.start(2L, "public", Set.of(), Set.of());
        assertTrue(scanStarted.await(5, TimeUnit.SECONDS));
        service.cancel(started.jobId());
        continueScan.countDown();

        DiscoveryJobService.JobSnapshot terminal = awaitTerminal(service, started.jobId());
        assertEquals("CANCELLED", terminal.status());
        verify(audit).record(eq("system"), eq("DISCOVERY_JOB_CANCELLED"), eq("CANCEL"),
                eq("DISCOVERY_JOB"), eq(started.jobId()), eq("public"), eq("SUCCESS"), anyString(), isNull());
        verify(audit, never()).record(any(), eq("DISCOVERY_JOB_FAILED"), any(), any(), any(), any(), any(), any(), any());
    }

    private static DiscoveryJobService.JobSnapshot awaitTerminal(DiscoveryJobService service, String jobId)
            throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            DiscoveryJobService.JobSnapshot snapshot = service.get(jobId);
            if (!Set.of("PENDING", "RUNNING").contains(snapshot.status())) return snapshot;
            Thread.sleep(20);
        }
        throw new AssertionError("Discovery job did not reach a terminal state");
    }
}
