package io.forgetdm.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.*;

class ForgeIntelligenceStoreServiceTest {
    private JdbcTemplate jdbc;
    private ForgeIntelligenceStoreService store;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:forge_ai_store;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP ALL OBJECTS");
        try (Connection connection = ds.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V58__forge_intelligence_store.sql"));
        }
        createMetadataTables();
        store = new ForgeIntelligenceStoreService(jdbc, new ObjectMapper());
    }

    @Test
    void synchronizesMetadataWithoutSecretsAndKeepsStableCitations() {
        jdbc.update("INSERT INTO data_sources VALUES (1,'CoreBank','POSTGRES','SOURCE','PROD','banking')");
        jdbc.update("INSERT INTO dataset_definitions VALUES (10,'Customer360','Customer entity slice','retail','customers',NULL,1)");
        jdbc.update("INSERT INTO table_profiles VALUES (10,'customers',TRUE,NULL)");
        jdbc.update("INSERT INTO masking_policies VALUES (20,'Retail PII','Banking privacy policy')");
        jdbc.update("INSERT INTO masking_rules VALUES (20,'customers','ssn','SSN',TRUE)");
        jdbc.update("INSERT INTO business_entities VALUES (30,'Retail Customer','Customer across banking apps','Retail',10,'customers','customer_id','ACTIVE','steward')");
        jdbc.update("INSERT INTO business_entity_members VALUES (30,301,'Core Banking',1,'retail',10,'CUSTOMER','customers','customer_id',TRUE,TRUE,1)");
        jdbc.update("INSERT INTO classifications VALUES (1,'CoreBank','customers','ssn','varchar','SSN',0.99,'SSN','APPROVED')");

        var first = store.sync();
        assertTrue(((Number) first.get("documents")).longValue() >= 5);
        @SuppressWarnings("unchecked")
        var latest = (java.util.Map<String, Object>) first.get("latestSync");
        assertEquals("COMPLETED", latest.get("status"));
        assertTrue(latest.containsKey("documentsWritten"));
        assertTrue(latest.containsKey("triggeredBy"));
        assertTrue(latest.containsKey("finishedAt"));
        var hit = store.search("Retail Customer CoreBank SSN", 10).stream()
                .filter(value -> value.type().equals("BUSINESS_ENTITY")).findFirst().orElseThrow();
        assertEquals("METADATA_ONLY", hit.sensitivity());
        assertFalse(hit.metadata().toString().toLowerCase().contains("password"));
        long documentId = hit.id();

        store.sync();
        var replay = store.search("Retail Customer", 10).stream()
                .filter(value -> value.type().equals("BUSINESS_ENTITY")).findFirst().orElseThrow();
        assertEquals(documentId, replay.id());
        assertEquals("FDS-" + documentId, replay.citation());
    }

    @Test
    void userKnowledgeIsVersionedSeparatelyFromSystemRefresh() {
        var added = store.addManualDocument("DOMAIN_RULE", "Dormant account",
                "No customer initiated transaction for 365 days", new ObjectMapper().createObjectNode());
        assertEquals("USER", added.origin());
        store.sync();
        assertTrue(store.search("Dormant account 365", 5).stream().anyMatch(value -> value.id() == added.id()));
        store.deleteManualDocument(added.id());
        assertTrue(store.search("Dormant account 365", 5).isEmpty());
    }

    private void createMetadataTables() {
        jdbc.execute("CREATE TABLE data_sources(id BIGINT,name VARCHAR(120),kind VARCHAR(40),role VARCHAR(40),environment VARCHAR(40),tags TEXT)");
        jdbc.execute("CREATE TABLE dataset_definitions(id BIGINT,name VARCHAR(200),description TEXT,schema_name VARCHAR(200),driver_table VARCHAR(200),driver_filter TEXT,data_source_id BIGINT)");
        jdbc.execute("CREATE TABLE table_profiles(dataset_id BIGINT,table_name VARCHAR(200),included BOOLEAN,filter_expr TEXT)");
        jdbc.execute("CREATE TABLE masking_policies(id BIGINT,name VARCHAR(200),description TEXT)");
        jdbc.execute("CREATE TABLE masking_rules(policy_id BIGINT,table_name VARCHAR(200),column_name VARCHAR(200),function VARCHAR(80),deterministic BOOLEAN)");
        jdbc.execute("CREATE TABLE business_entities(id BIGINT,name VARCHAR(200),description TEXT,domain VARCHAR(120),primary_dataset_id BIGINT,root_table VARCHAR(200),business_key_columns TEXT,status VARCHAR(40),owner_username VARCHAR(120))");
        jdbc.execute("CREATE TABLE business_entity_members(entity_id BIGINT,id BIGINT,system_name VARCHAR(200),data_source_id BIGINT,schema_name VARCHAR(200),dataset_id BIGINT,logical_role VARCHAR(120),table_name VARCHAR(200),key_columns TEXT,include_in_subset BOOLEAN,include_in_synthetic BOOLEAN,ordinal_no INT)");
        jdbc.execute("CREATE TABLE classifications(data_source_id BIGINT,source_name VARCHAR(120),table_name VARCHAR(200),column_name VARCHAR(200),data_type VARCHAR(80),pii_type VARCHAR(80),confidence DOUBLE,suggested_function VARCHAR(80),status VARCHAR(40))");
        jdbc.execute("CREATE TABLE mapping_definitions(id BIGINT,name VARCHAR(200),description TEXT)");
        jdbc.execute("CREATE TABLE synthetic_saved_jobs(id VARCHAR(80),name VARCHAR(200),description TEXT,approval_status VARCHAR(40),owner_username VARCHAR(120),updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE datascope_saved_jobs(id VARCHAR(80),name VARCHAR(200),description TEXT,owner_username VARCHAR(120),updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE self_service_products(id VARCHAR(80),product_type VARCHAR(40),artifact_id VARCHAR(120),artifact_version INT,label VARCHAR(200),description TEXT,category VARCHAR(100),tags TEXT,approval_mode VARCHAR(40),allowed_environments TEXT,enabled BOOLEAN,updated_at TIMESTAMP)");
    }
}
