package io.forgetdm.businessentity;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessEntityIdentityServiceTest {
    private JdbcTemplate jdbc;
    private BusinessEntityService entities;
    private AuditService audit;
    private BusinessEntityIdentityService service;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:be_identity;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"));
        createTables();
        entities = mock(BusinessEntityService.class);
        audit = mock(AuditService.class);
        service = new BusinessEntityIdentityService(jdbc, entities, audit);

        BusinessEntityDefinitionEntity entity = new BusinessEntityDefinitionEntity();
        entity.setId(1L);
        entity.setName("Customer 360");
        BusinessEntityMemberEntity db2 = member(11L, 101L, "Core DB2", "customers", "customer", "customer_id");
        BusinessEntityMemberEntity oracle = member(12L, 102L, "Cards Oracle", "card_accounts", "card", "card_ref");
        BusinessEntityMemberEntity crm = member(13L, 103L, "CRM", "party", "party", "party_id");
        when(entities.getDetail(1L)).thenReturn(new BusinessEntityService.BusinessEntityDetail(entity,
                List.of(db2, oracle, crm), null, Map.of(101L, "db2-core", 102L, "oracle-cards", 103L, "crm")));
    }

    @Test
    void linksAndResolvesCrossApplicationIdentity() {
        Map<String, Object> saved = service.upsert(1L, new BusinessEntityIdentityService.CrosswalkRequest(
                "CUST-10025", "CUSTOMER", "ACTIVE", 0.99, Map.of("segment", "retail"),
                List.of(
                        link(11L, "10025", "SOURCE_DB2_PK"),
                        link(12L, "CARD-778899", "ORACLE_CARD_REF"),
                        link(13L, "P-50025", "CRM_PARTY_ID")
                )));

        assertEquals("CUST-10025", saved.get("canonicalKey"));
        assertEquals(3, ((List<?>) saved.get("links")).size());

        Map<String, Object> resolved = service.resolve(1L, new BusinessEntityIdentityService.ResolveRequest(
                12L, null, null, null, null, null, null, "CARD-778899"));
        assertEquals(true, resolved.get("matched"));
        Map<String, Object> crosswalk = castMap(resolved.get("crosswalk"));
        assertEquals("CUST-10025", crosswalk.get("canonicalKey"));
        assertEquals(1, service.list(1L, "P-50025").size());
        verify(audit, atLeastOnce()).log(any(), any(), any());
    }

    @Test
    void rejectsSystemIdentityLinkedToDifferentCanonicalKey() {
        service.upsert(1L, new BusinessEntityIdentityService.CrosswalkRequest(
                "CUST-10025", "CUSTOMER", "ACTIVE", 1.0, Map.of(), List.of(link(11L, "10025", "SOURCE_DB2_PK"))));

        ApiException ex = assertThrows(ApiException.class, () -> service.upsert(1L,
                new BusinessEntityIdentityService.CrosswalkRequest("CUST-20000", "CUSTOMER", "ACTIVE", 1.0,
                        Map.of(), List.of(link(11L, "10025", "SOURCE_DB2_PK")))));
        assertTrue(ex.getMessage().contains("already linked"));
    }

    @Test
    void deniesBetaSubjectAndRawLinkIdsBeforeAnyDeletion() {
        jdbc.update("""
                INSERT INTO be_identity_subjects(id, entity_id, canonical_key, identity_type, status, confidence, attributes_json)
                VALUES (200, 2, 'BETA-1', 'CUSTOMER', 'ACTIVE', 1.0, '{}')
                """);
        jdbc.update("""
                INSERT INTO be_identity_links(id, entity_id, subject_id, table_name, key_values_json, external_id,
                    identity_key_hash, confidence, status)
                VALUES (201, 2, 200, 'customers', '{}', 'BETA-1', 'beta-hash', 1.0, 'ACTIVE')
                """);
        when(entities.getDetail(2L)).thenThrow(new ApiException(HttpStatus.FORBIDDEN, "BETA entity"));

        assertThrows(ApiException.class, () -> service.get(200L));
        assertThrows(ApiException.class, () -> service.deleteLink(201L));
        assertThrows(ApiException.class, () -> service.deleteSubject(200L));

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM be_identity_subjects WHERE id = 200", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM be_identity_links WHERE id = 201", Integer.class));
        verify(audit, never()).log(any(), contains("DELETE"), any());
    }

    @Test
    void rejectsPhysicalCoordinateOverrideForSelectedMember() {
        ApiException ex = assertThrows(ApiException.class, () -> service.resolve(1L,
                new BusinessEntityIdentityService.ResolveRequest(11L, "Core DB2", 102L,
                        null, "customers", "customer_id", null, "10025")));

        assertTrue(ex.getMessage().contains("data source"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM be_identity_links", Integer.class));
    }

    private BusinessEntityIdentityService.LinkRequest link(Long memberId, String externalId, String rule) {
        return new BusinessEntityIdentityService.LinkRequest(memberId, null, null, null, null, null, null,
                null, externalId, 1.0, rule, "ACTIVE", "TEST");
    }

    private BusinessEntityMemberEntity member(Long id, Long ds, String system, String table, String role, String keys) {
        BusinessEntityMemberEntity m = new BusinessEntityMemberEntity();
        m.setId(id);
        m.setEntityId(1L);
        m.setDataSourceId(ds);
        m.setSystemName(system);
        m.setTableName(table);
        m.setLogicalRole(role);
        m.setKeyColumns(keys);
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private void createTables() {
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE business_entities (id BIGINT PRIMARY KEY)");
        jdbc.execute("""
                CREATE TABLE business_entity_members (
                  id BIGINT PRIMARY KEY, entity_id BIGINT, system_name VARCHAR(160), data_source_id BIGINT,
                  schema_name VARCHAR(160), dataset_id BIGINT, logical_role VARCHAR(120), table_name VARCHAR(240),
                  table_alias VARCHAR(240), key_columns CLOB, join_to_role VARCHAR(120), relationship_json CLOB,
                  include_in_subset BOOLEAN DEFAULT TRUE, include_in_synthetic BOOLEAN DEFAULT TRUE, ordinal_no INTEGER DEFAULT 0)
                """);
        jdbc.execute("""
                CREATE TABLE be_identity_subjects (
                  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                  entity_id BIGINT NOT NULL,
                  canonical_key VARCHAR(240) NOT NULL,
                  identity_type VARCHAR(80) NOT NULL DEFAULT 'BUSINESS_ENTITY',
                  status VARCHAR(40) NOT NULL DEFAULT 'ACTIVE',
                  confidence DOUBLE PRECISION NOT NULL DEFAULT 1.0,
                  attributes_json CLOB NOT NULL,
                  created_by VARCHAR(200),
                  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE(entity_id, canonical_key))
                """);
        jdbc.execute("""
                CREATE TABLE be_identity_links (
                  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                  entity_id BIGINT NOT NULL,
                  subject_id BIGINT NOT NULL,
                  member_id BIGINT,
                  system_name VARCHAR(160),
                  data_source_id BIGINT,
                  schema_name VARCHAR(160),
                  table_name VARCHAR(240) NOT NULL,
                  logical_role VARCHAR(120),
                  key_columns CLOB,
                  key_values_json CLOB NOT NULL,
                  external_id VARCHAR(500) NOT NULL,
                  identity_key_hash VARCHAR(128) NOT NULL,
                  confidence DOUBLE PRECISION NOT NULL DEFAULT 1.0,
                  match_rule VARCHAR(160),
                  status VARCHAR(40) NOT NULL DEFAULT 'ACTIVE',
                  source VARCHAR(120),
                  created_by VARCHAR(200),
                  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                  UNIQUE(entity_id, identity_key_hash))
                """);
    }
}
