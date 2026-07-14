package io.forgetdm.provision;

import io.forgetdm.audit.AuditService;
import io.forgetdm.core.mask.MaskContext;
import io.forgetdm.core.mask.MaskFunction;
import io.forgetdm.core.mask.MaskingEngine;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class MaskingLookupCatalogTest {
    private MaskingEngine engine;
    private ValueListService service;

    @BeforeEach void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:mask_lookup_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE masking_lookup_values (lookup_name VARCHAR(120), lookup_mode VARCHAR(16), " +
                "lookup_key INTEGER, source_value VARCHAR(512), replacement_value VARCHAR(512))");
        jdbc.update("INSERT INTO masking_lookup_values VALUES ('demo.names','HASH',-1,NULL,'Unknown')");
        jdbc.update("INSERT INTO masking_lookup_values VALUES ('demo.names','HASH',1,NULL,'Olivia')");
        jdbc.update("INSERT INTO masking_lookup_values VALUES ('demo.names','HASH',2,NULL,'Liam')");
        jdbc.update("INSERT INTO masking_lookup_values VALUES ('demo.tier','DIRECT',NULL,'CHK','Everyday')");
        jdbc.update("INSERT INTO masking_lookup_values VALUES ('demo.tier','DIRECT',NULL,'SAV','Reserve')");

        engine = new MaskingEngine("lookup-catalog-secret");
        service = new ValueListService(mock(ValueListRepository.class), mock(DataSourceService.class),
                mock(ConnectionFactory.class), mock(AuditService.class), engine, jdbc);
    }

    @Test void relationalHashLookupLoadsSequentialAndReservedRows() {
        service.validateMaskingReference("@lookup:hash:demo.names");
        String output = engine.mask(MaskFunction.HASH_LOOKUP, "ignored", "customer-10025",
                "@lookup:hash:demo.names", "SEED=4", new MaskContext(1));
        assertTrue(Set.of("Olivia", "Liam").contains(output));
        assertEquals("Unknown", engine.mask(MaskFunction.HASH_LOOKUP, "ignored", null,
                "@lookup:hash:demo.names", null, new MaskContext(1)));
    }

    @Test void relationalDirectLookupAndReferenceCatalogAreUsable() {
        assertEquals("Everyday", engine.mask(MaskFunction.DIRECT_LOOKUP, "ignored", "CHK",
                "@lookup:direct:demo.tier", "NOT_FOUND=ERROR", new MaskContext(1)));
        assertTrue(service.maskingLookupReferences().contains("@lookup:hash:demo.names"));
        assertTrue(service.maskingLookupReferences().contains("@lookup:direct:demo.tier"));
    }
}
