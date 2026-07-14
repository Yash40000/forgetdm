package io.forgetdm.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.query.QueryService;
import io.forgetdm.datasource.ConnectionFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MappingValidationTest {
    @Test void acceptsManagedFileToDatabaseMappingAndRejectsIncompleteMaskAction() throws Exception {
        DataSourceService sources = mock(DataSourceService.class);
        when(sources.get(9L)).thenReturn(new DataSourceEntity());
        MappingFileAssetRepository assets = mock(MappingFileAssetRepository.class);
        when(assets.existsById(4L)).thenReturn(true);
        MappingService service = new MappingService(mock(MappingRepository.class), mock(QueryService.class), sources,
                mock(ConnectionFactory.class), mock(AuditService.class), new ObjectMapper(),
                mock(MappingVersionRepository.class), assets);
        ObjectMapper json = new ObjectMapper();

        var valid = service.validateSpec(json.readTree("""
                {"specVersion":2,"sources":[{"type":"FILE","assetId":4,"alias":"input"}],
                 "columns":[{"target":"email","source":"input.email","action":"MASK","maskFunction":"EMAIL"}],
                 "target":{"type":"DATABASE","dataSourceId":9,"table":"customers"}}
                """));
        assertEquals(true, valid.get("valid"));

        var invalid = service.validateSpec(json.readTree("""
                {"specVersion":2,"sources":[{"type":"FILE","assetId":4}],
                 "columns":[{"target":"email","source":"input.email","action":"MASK"}],
                 "target":{"type":"FILE","format":"CSV"}}
                """));
        assertEquals(false, invalid.get("valid"));
        assertTrue(String.valueOf(invalid.get("errors")).contains("masking function"));
    }

    @Test void requiresCatalogFunctionsToBeReviewedAndAcceptsConfiguredAggregate() throws Exception {
        MappingFileAssetRepository assets = mock(MappingFileAssetRepository.class);
        when(assets.existsById(4L)).thenReturn(true);
        MappingService service = service(mock(DataSourceService.class), assets);
        ObjectMapper json = new ObjectMapper();

        var unresolved = service.validateSpec(json.readTree("""
                {"specVersion":2,"sources":[{"type":"FILE","assetId":4,"alias":"input"}],
                 "transforms":[{"id":"agg-1","type":"AGGREGATOR","functionCategory":"Aggregate",
                   "requiresConfiguration":true,"aggregates":[{"name":"sum_1","expr":"SUM(col)"}]}],
                 "target":{"type":"PREVIEW"}}
                """));
        assertEquals(false, unresolved.get("valid"));
        assertTrue(String.valueOf(unresolved.get("errors")).contains("must be reviewed"));

        var configured = service.validateSpec(json.readTree("""
                {"specVersion":2,"sources":[{"type":"FILE","assetId":4,"alias":"input"}],
                 "transforms":[{"id":"agg-1","type":"AGGREGATOR","functionCategory":"Aggregate",
                   "requiresConfiguration":false,"aggregates":[{"name":"total_balance","expr":"SUM(input.balance)"}]}],
                 "target":{"type":"PREVIEW"}}
                """));
        assertEquals(true, configured.get("valid"));
    }

    @Test void blocksUnsafeTransformFragmentsAndInvalidJoinTopology() throws Exception {
        MappingFileAssetRepository assets = mock(MappingFileAssetRepository.class);
        when(assets.existsById(anyLong())).thenReturn(true);
        MappingService service = service(mock(DataSourceService.class), assets);
        ObjectMapper json = new ObjectMapper();

        var unsafe = service.validateSpec(json.readTree("""
                {"specVersion":2,"sources":[{"type":"FILE","assetId":4,"alias":"input"}],
                 "transforms":[{"id":"filter-1","type":"FILTER","condition":"1=1; DELETE FROM customers"}],
                 "target":{"type":"PREVIEW"}}
                """));
        assertEquals(false, unsafe.get("valid"));
        assertTrue(String.valueOf(unsafe.get("errors")).contains("unsafe SQL"));

        var badJoin = service.validateSpec(json.readTree("""
                {"specVersion":2,
                 "sources":[{"type":"FILE","assetId":4,"alias":"left_src"},{"type":"FILE","assetId":5,"alias":"right_src"}],
                 "joins":[{"id":"join-1","type":"INNER","left":"left_src.id","right":"left_src.parent_id"}],
                 "target":{"type":"PREVIEW"}}
                """));
        assertEquals(false, badJoin.get("valid"));
        assertTrue(String.valueOf(badJoin.get("errors")).contains("different source nodes"));
    }

    private static MappingService service(DataSourceService sources, MappingFileAssetRepository assets) {
        return new MappingService(mock(MappingRepository.class), mock(QueryService.class), sources,
                mock(ConnectionFactory.class), mock(AuditService.class), new ObjectMapper(),
                mock(MappingVersionRepository.class), assets);
    }
}
