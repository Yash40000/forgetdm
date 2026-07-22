package io.forgetdm.businessentity;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.core.mask.MaskingEngine;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.policy.MaskingRuleRepository;
import io.forgetdm.security.GovernedReferenceGuard;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessEntityCapsuleServiceTest {

    @Test
    void normalizesVendorTimestampThroughJdbcTextBoundary() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData md = mock(ResultSetMetaData.class);
        when(md.getColumnType(1)).thenReturn(Types.TIMESTAMP);
        when(rs.getString(1)).thenReturn("2026-07-13 16:42:11.123456");

        Object value = BusinessEntityCapsuleService.readPortableValue(rs, md, 1);

        assertEquals("2026-07-13 16:42:11.123456", value);
        verify(rs, never()).getObject(1);
    }

    @Test
    void encodesBinaryPayloadForPortableEncryptedJson() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData md = mock(ResultSetMetaData.class);
        when(md.getColumnType(1)).thenReturn(Types.BLOB);
        when(rs.getBytes(1)).thenReturn(new byte[]{1, 2, 3, 4});

        assertEquals("AQIDBA==", BusinessEntityCapsuleService.readPortableValue(rs, md, 1));
    }

    @Test
    void deniesBetaCapsuleAndGrantRawIdsBeforeReadingOrMutation() {
        BusinessEntityService entities = mock(BusinessEntityService.class);
        BeEntityInstanceRepository instances = mock(BeEntityInstanceRepository.class);
        BeEntityFragmentRepository fragments = mock(BeEntityFragmentRepository.class);
        BeEntityAccessGrantRepository grants = mock(BeEntityAccessGrantRepository.class);
        BeEntityInstanceEntity beta = new BeEntityInstanceEntity();
        beta.setId(77L);
        beta.setEntityId(2L);
        beta.setStatus("ACTIVE");
        when(instances.findById(77L)).thenReturn(Optional.of(beta));
        BeEntityAccessGrantEntity grant = new BeEntityAccessGrantEntity();
        grant.setId(88L);
        grant.setEntityId(2L);
        when(grants.findById(88L)).thenReturn(Optional.of(grant));
        when(entities.getDetail(2L)).thenThrow(new ApiException(HttpStatus.FORBIDDEN, "BETA entity"));
        BusinessEntityCapsuleService service = service(entities, instances, fragments, grants);

        assertThrows(ApiException.class, () -> service.detail(77L));
        assertThrows(ApiException.class, () -> service.revokeAccess(88L));
        assertThrows(ApiException.class, () -> service.recordLineageForKeys(
                2L, List.of(Map.of("customer_id", 100L)), "TEST", "blocked"));

        verifyNoInteractions(fragments);
        verify(grants, never()).save(any());
        verify(instances, never()).findByEntityIdAndCanonicalKey(anyLong(), anyString());
    }

    private BusinessEntityCapsuleService service(BusinessEntityService entities,
                                                  BeEntityInstanceRepository instances,
                                                  BeEntityFragmentRepository fragments,
                                                  BeEntityAccessGrantRepository grants) {
        return new BusinessEntityCapsuleService(
                entities,
                instances,
                fragments,
                mock(BeEntityVersionRepository.class),
                mock(BeEntityWatermarkRepository.class),
                grants,
                mock(BeEntityLineageEventRepository.class),
                mock(DataSourceService.class),
                new ConnectionFactory(),
                mock(MaskingEngine.class),
                mock(MaskingRuleRepository.class),
                mock(AuditService.class),
                mock(JdbcTemplate.class),
                mock(CapsuleCrypto.class),
                mock(GovernedReferenceGuard.class));
    }
}
