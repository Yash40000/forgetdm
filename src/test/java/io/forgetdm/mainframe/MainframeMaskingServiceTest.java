package io.forgetdm.mainframe;

import io.forgetdm.audit.AuditService;
import io.forgetdm.core.mask.MaskingEngine;
import io.forgetdm.mainframe.transport.TransportFactory;
import io.forgetdm.security.OwnershipGuard;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MainframeMaskingServiceTest {

    @Test
    void pendingJobCanBeCanceledBeforeWorkerStarts() {
        MainframeJobEntity job = job(7L, "PENDING");
        MainframeJobRepository jobs = mock(MainframeJobRepository.class);
        when(jobs.findById(7L)).thenReturn(Optional.of(job));
        when(jobs.save(any())).thenAnswer(call -> call.getArgument(0));
        AuditService audit = mock(AuditService.class);
        MainframeMaskingService service = service(jobs, mock(MainframeJobFileRepository.class),
                mock(ExecutorService.class), audit);

        MainframeJobEntity canceled = service.cancel(7L);

        assertEquals("CANCELED", canceled.getStatus());
        assertTrue(canceled.isCancelRequested());
        assertNotNull(canceled.getFinishedAt());
        verify(audit).record(eq("system"), eq("MAINFRAME_JOB_CANCEL_REQUESTED"), eq("CANCEL"),
                eq("MAINFRAME_JOB"), eq("7"), eq("AUD mainframe"), eq("SUCCESS"), anyString(), isNull());
        verify(audit).record(eq("admin"), eq("MAINFRAME_JOB_CANCELED"), eq("MASKING"),
                eq("MAINFRAME_JOB"), eq("7"), eq("AUD mainframe"), eq("SUCCESS"), anyString(), anyString());
    }

    @Test
    void retryCreatesNewAttemptAndRetainsOnlyCompletedFiles() {
        MainframeJobEntity previous = job(11L, "COMPLETED_WITH_ERRORS");
        previous.setFilesTotal(2);
        MainframeJobFileEntity completed = file(21L, 11L, "A.DATA", "COMPLETED", 25);
        MainframeJobFileEntity failed = file(22L, 11L, "B.DATA", "FAILED", 0);

        MainframeJobRepository jobs = mock(MainframeJobRepository.class);
        AtomicReference<MainframeJobEntity> created = new AtomicReference<>();
        when(jobs.findById(11L)).thenReturn(Optional.of(previous));
        when(jobs.findById(12L)).thenAnswer(call -> Optional.ofNullable(created.get()));
        when(jobs.save(any())).thenAnswer(call -> {
            MainframeJobEntity value = call.getArgument(0);
            if (value.getId() == null) value.setId(12L);
            if (Long.valueOf(12L).equals(value.getId())) created.set(value);
            return value;
        });

        MainframeJobFileRepository files = mock(MainframeJobFileRepository.class);
        when(files.findByJobIdOrderByOrdinalAsc(11L)).thenReturn(List.of(completed, failed));
        List<MainframeJobFileEntity> copied = new ArrayList<>();
        when(files.save(any())).thenAnswer(call -> {
            MainframeJobFileEntity value = call.getArgument(0);
            copied.add(value);
            return value;
        });
        ExecutorService executor = mock(ExecutorService.class);
        AuditService audit = mock(AuditService.class);
        MainframeMaskingService service = service(jobs, files, executor, audit);

        MainframeJobEntity retry = service.retry(11L);

        assertEquals(12L, retry.getId());
        assertEquals("PENDING", retry.getStatus());
        assertEquals(1, retry.getFilesDone());
        assertEquals(25, retry.getRecordsProcessed());
        assertEquals(2, copied.size());
        assertEquals("COMPLETED", copied.get(0).getStatus());
        assertEquals(25, copied.get(0).getRecordCount());
        assertEquals("PENDING", copied.get(1).getStatus());
        assertEquals(0, copied.get(1).getRecordCount());
        verify(audit).record(eq("system"), eq("MAINFRAME_JOB_RETRIED"), eq("MASKING"),
                eq("MAINFRAME_JOB"), eq("12"), eq("AUD mainframe"), eq("SUCCESS"), anyString(), contains("11"));
        verify(audit).record(eq("system"), eq("MAINFRAME_JOB_QUEUED"), eq("MASKING"),
                eq("MAINFRAME_JOB"), eq("12"), eq("AUD mainframe"), eq("SUCCESS"), anyString(), anyString());
        verify(executor).submit(any(Runnable.class));
    }

    private static MainframeMaskingService service(MainframeJobRepository jobs,
                                                   MainframeJobFileRepository files,
                                                   ExecutorService executor,
                                                   AuditService audit) {
        MainframeConnectionRepository connections = mock(MainframeConnectionRepository.class);
        MainframeConnectionEntity connection = new MainframeConnectionEntity();
        connection.setId(1L);
        when(connections.findById(anyLong())).thenReturn(Optional.of(connection));
        CopybookDefRepository copybooks = mock(CopybookDefRepository.class);
        CopybookDefEntity copybook = new CopybookDefEntity();
        copybook.setId(1L);
        when(copybooks.findById(anyLong())).thenReturn(Optional.of(copybook));
        return new MainframeMaskingService(jobs, files, connections,
                copybooks, mock(CopybookMaskRepository.class),
                mock(TransportFactory.class), new MaskingEngine("test-secret"), executor, audit,
                mock(OwnershipGuard.class));
    }

    private static MainframeJobEntity job(Long id, String status) {
        MainframeJobEntity job = new MainframeJobEntity();
        job.setId(id);
        job.setName("AUD mainframe");
        job.setStatus(status);
        job.setCreatedBy("admin");
        job.setSourceConnectionId(1L);
        job.setTargetConnectionId(1L);
        return job;
    }

    private static MainframeJobFileEntity file(Long id, Long jobId, String name, String status, long records) {
        MainframeJobFileEntity file = new MainframeJobFileEntity();
        file.setId(id);
        file.setJobId(jobId);
        file.setSourceName(name);
        file.setCopybookId(1L);
        file.setStatus(status);
        file.setRecordCount(records);
        file.setOrdinal(name.startsWith("A") ? 0 : 1);
        return file;
    }
}
