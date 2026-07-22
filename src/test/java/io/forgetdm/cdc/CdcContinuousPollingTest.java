package io.forgetdm.cdc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.config.ForgeProps;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.security.OwnershipGuard;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CdcContinuousPollingTest {

    @Test
    void schedulerPollsOnlyWhenContinuousCaptureIsEnabled() {
        ForgeProps props = new ForgeProps();
        CdcService service = mock(CdcService.class);
        CdcPollScheduler scheduler = new CdcPollScheduler(props, service);

        props.getCdc().setContinuousEnabled(false);
        scheduler.sweep();
        verifyNoInteractions(service);

        props.getCdc().setContinuousEnabled(true);
        when(service.pollAllActive()).thenReturn(2);
        scheduler.sweep();
        verify(service).pollAllActive();
    }

    @Test
    void pollAllActiveContinuesAfterOneCaptureFails() {
        CdcCaptureRepository repository = mock(CdcCaptureRepository.class);
        CdcService service = spy(service(repository, mock(CdcChangeRepository.class),
                mock(DataSourceService.class), List.of(), mock(OwnershipGuard.class)));
        CdcCaptureEntity first = capture(1L, 10L);
        CdcCaptureEntity second = capture(2L, 20L);
        when(repository.findByStatus("ACTIVE")).thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("temporary source failure")).when(service).poll(10L);
        doReturn(new CdcService.PollSummary(20L, 1, 1, "0/20", true)).when(service).poll(20L);

        assertEquals(1, service.pollAllActive());
        verify(service).poll(10L);
        verify(service).poll(20L);
    }

    @Test
    void temporaryPollFailureIsRecordedWithoutDisablingContinuousRetries() {
        CdcCaptureRepository repository = mock(CdcCaptureRepository.class);
        CdcChangeRepository changes = mock(CdcChangeRepository.class);
        DataSourceService dataSources = mock(DataSourceService.class);
        OwnershipGuard ownership = mock(OwnershipGuard.class);
        CdcProvider provider = mock(CdcProvider.class);
        DataSourceEntity source = new DataSourceEntity();
        source.setId(10L);
        source.setName("core-postgres");
        CdcCaptureEntity capture = capture(1L, 10L);

        when(repository.findByDataSourceId(10L)).thenReturn(java.util.Optional.of(capture));
        when(dataSources.get(10L)).thenReturn(source);
        when(provider.supports(source)).thenReturn(true);
        when(provider.poll(any(), any(), anyInt(), anyLong()))
                .thenThrow(new IllegalStateException("temporary source failure"));

        CdcService service = service(repository, changes, dataSources, List.of(provider), ownership);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> service.poll(10L));

        assertEquals("temporary source failure", failure.getMessage());
        assertEquals("ACTIVE", capture.getStatus());
        assertEquals("temporary source failure", capture.getLastError());
        assertNotNull(capture.getLastPolledAt());
        verify(repository).save(capture);
    }

    @Test
    void statusSurfacesCurrentPositionAndProviderLag() {
        CdcCaptureRepository repository = mock(CdcCaptureRepository.class);
        CdcChangeRepository changes = mock(CdcChangeRepository.class);
        DataSourceService dataSources = mock(DataSourceService.class);
        OwnershipGuard ownership = mock(OwnershipGuard.class);
        CdcProvider provider = mock(CdcProvider.class);
        DataSourceEntity source = new DataSourceEntity();
        source.setId(7L);
        source.setName("core-postgres");
        CdcCaptureEntity capture = capture(3L, 7L);
        capture.setConfirmedLsn("0/100");
        capture.setRowsCaptured(42);
        capture.setLastPolledAt(Instant.parse("2026-07-21T12:00:00Z"));

        when(dataSources.get(7L)).thenReturn(source);
        when(repository.findByDataSourceId(7L)).thenReturn(java.util.Optional.of(capture));
        when(changes.countByDataSourceId(7L)).thenReturn(9L);
        when(provider.supports(source)).thenReturn(true);
        when(provider.mechanism()).thenReturn("PostgreSQL logical replication");
        when(provider.lagUnit()).thenReturn("bytes");
        when(provider.currentLogPosition(source)).thenReturn("0/180");
        when(provider.lag(source, "0/100")).thenReturn(128L);

        CdcService.Status status = service(repository, changes, dataSources, List.of(provider), ownership)
                .status(7L);

        assertEquals("0/180", status.currentPosition());
        assertEquals(128L, status.lag());
        assertEquals("bytes", status.lagUnit());
        assertEquals(42, status.rowsCaptured());
        assertEquals(9, status.bufferedChanges());
    }

    private static CdcService service(CdcCaptureRepository captures, CdcChangeRepository changes,
                                      DataSourceService dataSources, List<CdcProvider> providers,
                                      OwnershipGuard ownership) {
        return new CdcService(captures, changes, dataSources, providers,
                mock(CdcIncrementalApplier.class), ownership, mock(AuditService.class), new ObjectMapper());
    }

    private static CdcCaptureEntity capture(Long id, Long dataSourceId) {
        CdcCaptureEntity capture = new CdcCaptureEntity();
        capture.setId(id);
        capture.setDataSourceId(dataSourceId);
        capture.setSlotName("slot-" + id);
        capture.setStatus("ACTIVE");
        return capture;
    }
}
