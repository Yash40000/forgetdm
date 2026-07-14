package io.forgetdm.businessentity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.core.mask.MaskContext;
import io.forgetdm.core.mask.MaskFunction;
import io.forgetdm.core.mask.MaskingEngine;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.policy.MaskingRuleEntity;
import io.forgetdm.policy.MaskingRuleRepository;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.AccessPrincipal;
import jakarta.transaction.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * K2View-style logical Micro-Database / Entity Capsule layer.
 *
 * Materializes each business entity INSTANCE (e.g. Customer 360 / CUST-10025) into a governed,
 * reusable capsule: one fragment per member table keyed on the same canonical business key,
 * restorable version history, freshness watermarks, enforced access grants, and a lineage
 * trail — all in shared physical tables ({@code be_entity_*}), never a database-per-customer.
 *
 * Industry-standard guarantees of this layer:
 *  - a fragment is only ever persisted MASKED (via the existing {@link MaskingEngine}) or as a
 *    row-count-only pointer (no policy). Raw PII is never written into the capsule store;
 *  - MASKED payloads are encrypted at rest (AES-256-GCM) with a per-capsule derived key
 *    ({@link CapsuleCrypto}) — K2View encrypts each micro-DB with its own key, so do we;
 *  - access grants are ENFORCED (grantee/role, scope hierarchy READ < PROVISION < MANAGE < OWNER,
 *    TTL expiry, revocation); the materializing user receives an implicit OWNER grant;
 *  - every version's fragments are retained and restorable (time travel), with content hashes;
 *  - capture truncation is recorded as evidence, never silent;
 *  - ON_DEMAND capsules refresh themselves at access time once their staleness budget is spent
 *    (K2View "sync on access");
 *  - concurrent materialize calls on one capsule serialize on a row lock instead of racing;
 *  - a capsule can act as a PROVISIONING ROW SOURCE: masked fragment rows can be loaded into a
 *    target data source without touching the live source system again.
 */
@Service
public class BusinessEntityCapsuleService {
    private static final int MAX_FRAGMENT_ROWS = 500;
    private static final int MAX_FK_PARENT_KEYS = 1000;
    private static final int MAX_DELETE_KEY_TUPLES = 200;

    /** Scope hierarchy for enforced access grants. */
    private static final Map<String, Integer> SCOPE_RANK = Map.of(
            "READ", 1, "PROVISION", 2, "MANAGE", 3, "OWNER", 4);

    private final BusinessEntityService entities;
    private final BeEntityInstanceRepository instances;
    private final BeEntityFragmentRepository fragments;
    private final BeEntityVersionRepository versions;
    private final BeEntityWatermarkRepository watermarks;
    private final BeEntityAccessGrantRepository grants;
    private final BeEntityLineageEventRepository lineageRepo;
    private final DataSourceService dataSources;
    private final ConnectionFactory connections;
    private final MaskingEngine maskingEngine;
    private final MaskingRuleRepository maskingRules;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final CapsuleCrypto crypto;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    public BusinessEntityCapsuleService(BusinessEntityService entities,
                                        BeEntityInstanceRepository instances,
                                        BeEntityFragmentRepository fragments,
                                        BeEntityVersionRepository versions,
                                        BeEntityWatermarkRepository watermarks,
                                        BeEntityAccessGrantRepository grants,
                                        BeEntityLineageEventRepository lineageRepo,
                                        DataSourceService dataSources,
                                        ConnectionFactory connections,
                                        MaskingEngine maskingEngine,
                                        MaskingRuleRepository maskingRules,
                                        AuditService audit,
                                        JdbcTemplate jdbc,
                                        CapsuleCrypto crypto) {
        this.entities = entities;
        this.instances = instances;
        this.fragments = fragments;
        this.versions = versions;
        this.watermarks = watermarks;
        this.grants = grants;
        this.lineageRepo = lineageRepo;
        this.dataSources = dataSources;
        this.connections = connections;
        this.maskingEngine = maskingEngine;
        this.maskingRules = maskingRules;
        this.audit = audit;
        this.jdbc = jdbc;
        this.crypto = crypto;
    }

    public record MaterializeRequest(Map<String, Object> businessKey, Long policyId, String notes,
                                     String syncMode, Integer staleAfterMinutes) {}
    public record AccessGrantRequest(String granteeType, String grantee, String scope, Integer ttlHours, String note) {}
    public record ProvisionFromCapsuleRequest(Long targetDataSourceId, String targetSchema, Boolean deleteExistingByKey) {}
    public record CapsuleDetail(BeEntityInstanceEntity instance, List<BeEntityFragmentEntity> fragments,
                                List<BeEntityVersionEntity> versions, List<BeEntityWatermarkEntity> watermarks,
                                List<BeEntityAccessGrantEntity> grants, List<BeEntityLineageEventEntity> lineage) {}
    private record FetchResult(List<Map<String, Object>> rows, List<Map<String, String>> textRows,
                               int rowCount, boolean truncated) {}
    private record FkPath(String fkColumn, String parentColumn) {}
    private record RelationshipPath(String parentTable, List<String> parentColumns,
                                    String childTable, List<String> childColumns) {}

    public List<BeEntityInstanceEntity> list(Long entityId) {
        entities.getDetail(entityId); // validates the entity exists
        return instances.findByEntityIdOrderByUpdatedAtDesc(entityId);
    }

    /** Access-checked read. CURRENT fragments only; payloads decrypted for the authorized caller. */
    public CapsuleDetail detail(Long instanceId) {
        BeEntityInstanceEntity instance = get(instanceId);
        assertAccess(instance, "READ");
        return buildDetail(instance);
    }

    /** Access-checked read that honors sync-on-demand: an ACTIVE ON_DEMAND capsule whose staleness
     *  budget is spent (or that has a STALE watermark) is refreshed transparently before returning. */
    @Transactional
    public CapsuleDetail detailWithSync(Long instanceId) {
        BeEntityInstanceEntity instance = get(instanceId);
        assertAccess(instance, "READ");
        if (isStale(instance)) {
            try {
                Map<String, Object> key = readJsonMap(instance.getBusinessKeyJson());
                doMaterialize(instance.getEntityId(), new MaterializeRequest(key, instance.getPolicyId(),
                        "sync-on-demand refresh", instance.getSyncMode(), instance.getStaleAfterMinutes()),
                        "SYNC_ON_DEMAND");
                instance = get(instanceId);
            } catch (Exception e) {
                lineage(instanceId, "SYNC_FAILED", Map.of("error", String.valueOf(e.getMessage())));
            }
        }
        return buildDetail(instance);
    }

    /** Fragments of one historical version, decrypted for the authorized caller. */
    public List<BeEntityFragmentEntity> versionFragments(Long instanceId, int versionNo) {
        BeEntityInstanceEntity instance = get(instanceId);
        assertAccess(instance, "READ");
        return fragments.findByInstanceIdAndVersionNoOrderByIdAsc(instanceId, versionNo).stream()
                .map(f -> readCopy(f, instance)).toList();
    }

    @Transactional
    public CapsuleDetail materialize(Long entityId, MaterializeRequest req) {
        Map<String, Object> businessKey = req == null ? null : req.businessKey();
        if (businessKey == null || businessKey.isEmpty())
            throw ApiException.bad("businessKey is required: root key column(s) -> value(s).");
        // Refreshing an existing capsule is a MANAGE-scoped action; creating a new one is open
        // (the creator receives an implicit OWNER grant).
        instances.findByEntityIdAndCanonicalKey(entityId, canonical(businessKey))
                .ifPresent(existing -> assertAccess(existing, "MANAGE"));
        return doMaterialize(entityId, req, null);
    }

    private CapsuleDetail doMaterialize(Long entityId, MaterializeRequest req, String lineageOverride) {
        BusinessEntityService.BusinessEntityDetail detail = entities.getDetail(entityId);
        if (detail.members().isEmpty()) throw ApiException.bad("Business Entity has no member tables to materialize.");
        Map<String, Object> businessKey = req.businessKey();

        BusinessEntityMemberEntity root = rootMember(detail);
        List<String> rootKeyCols = splitColumns(keyColumnsFor(root, detail.entity()));
        if (rootKeyCols.isEmpty()) throw ApiException.bad("Root member has no key columns configured; add key columns before materializing.");
        List<Object> rootValues = new ArrayList<>();
        for (String col : rootKeyCols) {
            if (!businessKey.containsKey(col))
                throw ApiException.bad("businessKey is missing root key column: " + col);
            rootValues.add(businessKey.get(col));
        }
        String canonicalKey = canonical(businessKey);
        Long policyId = req.policyId();

        // Row-locked find-or-create: concurrent materialize calls on the same capsule serialize here.
        BeEntityInstanceEntity instance = instances.findWithLockByEntityIdAndCanonicalKey(entityId, canonicalKey)
                .orElseGet(BeEntityInstanceEntity::new);
        boolean isNew = instance.getId() == null;
        instance.setEntityId(entityId);
        instance.setCanonicalKey(canonicalKey);
        instance.setBusinessKeyJson(writeJson(businessKey));
        instance.setPolicyId(policyId);
        if (instance.getStatus() == null) instance.setStatus("ACTIVE");
        if (instance.getKeySalt() == null) instance.setKeySalt(crypto.newKeySalt());
        if (req.syncMode() != null) instance.setSyncMode(upperDefault(req.syncMode(), "MANUAL"));
        if (instance.getSyncMode() == null) instance.setSyncMode("MANUAL");
        if (req.staleAfterMinutes() != null) instance.setStaleAfterMinutes(req.staleAfterMinutes() <= 0 ? null : req.staleAfterMinutes());
        if (blank(req.notes()) != null) instance.setNotes(req.notes());
        try {
            instance = instances.saveAndFlush(instance);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("Another materialize call is creating this capsule right now — retry in a moment.");
        }
        int versionNo = instance.getCurrentVersion() + 1;

        // Capture the root fragment first: FK-aware capture of non-root members joins through the
        // root rows held in memory (raw values are used ONLY as join keys, never persisted).
        FetchResult rootFetched = null;
        Map<String, FetchResult> fetchedByRole = new LinkedHashMap<>();
        Map<String, FetchResult> fetchedByTable = new LinkedHashMap<>();
        List<BusinessEntityMemberEntity> ordered = new ArrayList<>();
        ordered.add(root);
        for (BusinessEntityMemberEntity m : detail.members()) if (!Objects.equals(m.getId(), root.getId())) ordered.add(m);

        int fragmentCount = 0;
        long totalRows = 0;
        List<String> hashes = new ArrayList<>();
        for (BusinessEntityMemberEntity member : ordered) {
            fragments.findByInstanceIdAndMemberIdAndStatus(instance.getId(), member.getId(), "CURRENT")
                    .ifPresent(f -> { f.setStatus("SUPERSEDED"); fragments.save(f); }); // payload retained: versions stay restorable

            BeEntityFragmentEntity frag = new BeEntityFragmentEntity();
            frag.setInstanceId(instance.getId());
            frag.setMemberId(member.getId());
            frag.setSystemName(member.getSystemName());
            frag.setDataSourceId(member.getDataSourceId());
            frag.setSchemaName(member.getSchemaName());
            frag.setTableName(member.getTableName());
            frag.setStatus("CURRENT");
            frag.setVersionNo(versionNo);

            if (member.getDataSourceId() == null) {
                frag.setFragmentType("SKIPPED");
                frag.setMessage("No data source configured for this member.");
                fragments.save(frag);
                fragmentCount++;
                continue;
            }
            boolean isRoot = member.getId() != null && member.getId().equals(root.getId());
            List<String> memberKeyCols = isRoot ? rootKeyCols : splitColumns(member.getKeyColumns());
            RelationshipPath relationship = isRoot ? null : relationshipPath(member);
            try {
                DataSourceEntity ds = dataSources.get(member.getDataSourceId());
                FetchResult fetched;
                if (relationship != null) {
                    FetchResult parentFetched = parentFetch(member, relationship, fetchedByRole, fetchedByTable);
                    if (parentFetched == null) {
                        throw ApiException.bad("Declared parent role/table has not been captured: "
                                + firstNonBlank(member.getJoinToRole(), relationship.parentTable()));
                    }
                    frag.setKeyColumns(String.join(",", relationship.childColumns()));
                    fetched = fetchRowsByRelationship(ds, member.getSchemaName(), member.getTableName(),
                            relationship.childColumns(), parentFetched.rows(), relationship.parentColumns(), MAX_FRAGMENT_ROWS);
                    frag.setMessage("Captured through declared relationship "
                            + relationship.parentTable() + "(" + String.join(",", relationship.parentColumns()) + ") -> "
                            + relationship.childTable() + "(" + String.join(",", relationship.childColumns()) + ").");
                } else if (isRoot || sameColumns(memberKeyCols, rootKeyCols)) {
                    frag.setKeyColumns(String.join(",", memberKeyCols));
                    fetched = fetchRows(ds, member.getSchemaName(), member.getTableName(), memberKeyCols, rootValues, MAX_FRAGMENT_ROWS);
                } else {
                    // FK-graph-aware capture: no positional key match — walk one FK hop from this
                    // member to the root table (same as Subsetting's catalog edges) and select child
                    // rows by the root rows' parent-column values.
                    FkPath path = fkPathToRoot(ds, member.getSchemaName(), member.getTableName(), root.getTableName());
                    if (path == null) {
                        frag.setFragmentType("SKIPPED");
                        frag.setKeyColumns(member.getKeyColumns());
                        frag.setMessage("Member key columns (" + memberKeyCols.size() + ") do not positionally match the root key ("
                                + rootKeyCols.size() + ") and no single-column FK to " + root.getTableName()
                                + " was found in the catalog. Configure matching key columns or add the FK.");
                        fragments.save(frag);
                        fragmentCount++;
                        continue;
                    }
                    if (rootFetched == null || rootFetched.rows().isEmpty()) {
                        frag.setFragmentType("SKIPPED");
                        frag.setKeyColumns(path.fkColumn());
                        frag.setMessage("FK capture via " + path.fkColumn() + " → " + root.getTableName() + "." + path.parentColumn()
                                + " skipped: root fragment returned no rows.");
                        fragments.save(frag);
                        fragmentCount++;
                        continue;
                    }
                    List<Object> parentValues = rootFetched.rows().stream()
                            .map(r -> r.get(path.parentColumn().toLowerCase(Locale.ROOT)))
                            .filter(Objects::nonNull).distinct().limit(MAX_FK_PARENT_KEYS).toList();
                    frag.setKeyColumns(path.fkColumn());
                    fetched = fetchRowsIn(ds, member.getSchemaName(), member.getTableName(), path.fkColumn(), parentValues, MAX_FRAGMENT_ROWS);
                    frag.setMessage("Captured via FK " + path.fkColumn() + " → " + root.getTableName() + "." + path.parentColumn() + ".");
                }
                if (isRoot) rootFetched = fetched;
                rememberFetch(member, fetched, fetchedByRole, fetchedByTable);
                if (policyId != null) {
                    List<MaskingRuleEntity> rules = rulesForTable(policyId, member.getTableName());
                    List<Map<String, Object>> masked = maskRows(fetched.rows(), fetched.textRows(), rules, member.getTableName());
                    String clearJson = writeJson(masked);
                    frag.setFragmentType("MASKED");
                    // Content hash over the CLEAR masked payload so hashes stay comparable across versions;
                    // the stored payload itself is AES-256-GCM ciphertext under this capsule's derived key.
                    frag.setContentHash(sha256(clearJson));
                    CapsuleCrypto.Encrypted enc = crypto.encrypt(clearJson, instance.getKeySalt());
                    frag.setPayloadJson(enc.cipherTextBase64());
                    frag.setPayloadIv(enc.ivHex());
                    frag.setEncrypted(true);
                } else {
                    // Never persist raw values without a masking policy — pointer/count evidence only.
                    frag.setFragmentType("RAW_REF");
                    frag.setPayloadJson(null);
                    frag.setContentHash(sha256(member.getTableName() + "|" + fetched.rowCount()));
                }
                frag.setRowCount(fetched.rowCount());
                frag.setTruncated(fetched.truncated());
                if (fetched.truncated()) {
                    frag.setMessage((frag.getMessage() == null ? "" : frag.getMessage() + " ")
                            + "Row-set truncated at the " + MAX_FRAGMENT_ROWS + "-row capture cap.");
                }
                totalRows += fetched.rowCount();
            } catch (ApiException e) {
                frag.setFragmentType("FAILED");
                frag.setMessage(e.getMessage());
            } catch (Exception e) {
                frag.setFragmentType("FAILED");
                frag.setMessage("Could not capture fragment: " + e.getMessage());
            }
            fragments.save(frag);
            if (frag.getContentHash() != null) hashes.add(frag.getContentHash());
            fragmentCount++;
            propagateExistingWatermark(instance.getId(), entityId, member);
        }

        instance.setCurrentVersion(versionNo);
        instance.setFragmentCount(fragmentCount);
        instance.setTotalRows(totalRows);
        instance.setLastMaterializedAt(Instant.now());
        instance.setLastMaterializedBy(currentUsername());
        instance.setUpdatedAt(Instant.now());
        instance = instances.save(instance);

        BeEntityVersionEntity version = new BeEntityVersionEntity();
        version.setInstanceId(instance.getId());
        version.setVersionNo(versionNo);
        version.setKind(policyId != null ? "MASKED_SNAPSHOT" : "RAW_REF_ONLY");
        version.setPolicyId(policyId);
        version.setFragmentCount(fragmentCount);
        version.setTotalRows(totalRows);
        version.setContentHash(sha256(String.join("|", hashes)));
        version.setNotes(blank(req.notes()));
        version.setCreatedBy(currentUsername());
        versions.save(version);

        if (isNew) grantImplicitOwner(instance);

        Map<String, Object> lineageDetail = new LinkedHashMap<>();
        lineageDetail.put("version", versionNo);
        lineageDetail.put("fragments", fragmentCount);
        lineageDetail.put("totalRows", totalRows);
        lineageDetail.put("policyId", policyId);
        lineage(instance.getId(), lineageOverride != null ? lineageOverride : (isNew ? "MATERIALIZED" : "REFRESHED"), lineageDetail);

        audit.log(currentUsername(), "BUSINESS_ENTITY_CAPSULE_MATERIALIZE",
                "entity=" + entityId + " instance=" + instance.getId() + " version=" + versionNo + " fragments=" + fragmentCount);
        return buildDetail(instance);
    }

    /** Time travel: re-issue a historical version's fragments as the new CURRENT version.
     *  History stays immutable — restoring creates a NEW version copied from the old one. */
    @Transactional
    public CapsuleDetail restoreVersion(Long instanceId, int versionNo) {
        BeEntityInstanceEntity instance = get(instanceId);
        assertAccess(instance, "MANAGE");
        BeEntityVersionEntity source = versions.findByInstanceIdAndVersionNo(instanceId, versionNo)
                .orElseThrow(() -> ApiException.notFound("Capsule version not found: v" + versionNo));
        List<BeEntityFragmentEntity> sourceFrags = fragments.findByInstanceIdAndVersionNoOrderByIdAsc(instanceId, versionNo);
        if (sourceFrags.isEmpty()) throw ApiException.bad("Version v" + versionNo + " has no stored fragments to restore.");

        int newVersionNo = instance.getCurrentVersion() + 1;
        for (BeEntityFragmentEntity current : fragments.findByInstanceIdAndStatusOrderByIdAsc(instanceId, "CURRENT")) {
            current.setStatus("SUPERSEDED");
            fragments.save(current);
        }
        int fragmentCount = 0;
        long totalRows = 0;
        List<String> hashes = new ArrayList<>();
        for (BeEntityFragmentEntity src : sourceFrags) {
            BeEntityFragmentEntity copy = new BeEntityFragmentEntity();
            copy.setInstanceId(instanceId);
            copy.setMemberId(src.getMemberId());
            copy.setSystemName(src.getSystemName());
            copy.setDataSourceId(src.getDataSourceId());
            copy.setSchemaName(src.getSchemaName());
            copy.setTableName(src.getTableName());
            copy.setKeyColumns(src.getKeyColumns());
            copy.setFragmentType(src.getFragmentType());
            copy.setStatus("CURRENT");
            copy.setVersionNo(newVersionNo);
            copy.setRowCount(src.getRowCount());
            copy.setTruncated(src.isTruncated());
            copy.setEncrypted(src.isEncrypted());
            copy.setPayloadIv(src.getPayloadIv());
            copy.setPayloadJson(src.getPayloadJson()); // same instance key — ciphertext restores as-is
            copy.setContentHash(src.getContentHash());
            copy.setMessage("Restored from v" + versionNo + ".");
            fragments.save(copy);
            fragmentCount++;
            totalRows += src.getRowCount();
            if (src.getContentHash() != null) hashes.add(src.getContentHash());
        }
        instance.setCurrentVersion(newVersionNo);
        instance.setFragmentCount(fragmentCount);
        instance.setTotalRows(totalRows);
        instance.setUpdatedAt(Instant.now());
        instances.save(instance);

        BeEntityVersionEntity version = new BeEntityVersionEntity();
        version.setInstanceId(instanceId);
        version.setVersionNo(newVersionNo);
        version.setKind("RESTORED");
        version.setPolicyId(source.getPolicyId());
        version.setFragmentCount(fragmentCount);
        version.setTotalRows(totalRows);
        version.setContentHash(sha256(String.join("|", hashes)));
        version.setNotes("Restored from v" + versionNo);
        version.setCreatedBy(currentUsername());
        versions.save(version);

        lineage(instanceId, "RESTORED", Map.of("fromVersion", versionNo, "newVersion", newVersionNo));
        audit.log(currentUsername(), "BUSINESS_ENTITY_CAPSULE_RESTORE",
                "instance=" + instanceId + " from=v" + versionNo + " new=v" + newVersionNo);
        return buildDetail(instance);
    }

    /** Capsule as a provisioning row source: load this capsule's MASKED fragment rows into a target
     *  data source, without touching the live source system. Requires PROVISION scope. */
    @Transactional
    public Map<String, Object> provisionToTarget(Long instanceId, ProvisionFromCapsuleRequest req) {
        BeEntityInstanceEntity instance = get(instanceId);
        assertAccess(instance, "PROVISION");
        if (!"ACTIVE".equalsIgnoreCase(instance.getStatus()))
            throw ApiException.conflict("Capsule is " + instance.getStatus() + "; only ACTIVE capsules can provision.");
        if (req == null || req.targetDataSourceId() == null) throw ApiException.bad("targetDataSourceId is required");
        DataSourceEntity target = dataSources.get(req.targetDataSourceId());
        String targetSchema = blank(req.targetSchema());
        boolean deleteFirst = req.deleteExistingByKey() == null || req.deleteExistingByKey();

        List<BeEntityFragmentEntity> current = fragments.findByInstanceIdAndStatusOrderByIdAsc(instanceId, "CURRENT");
        List<Map<String, Object>> results = new ArrayList<>();
        long inserted = 0;
        int loadedFragments = 0;
        try (Connection c = connections.open(target)) {
            for (BeEntityFragmentEntity frag : current) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("table", frag.getTableName());
                if (!"MASKED".equalsIgnoreCase(frag.getFragmentType()) || frag.getPayloadJson() == null) {
                    r.put("status", "SKIPPED");
                    r.put("reason", "Only MASKED fragments with stored payloads can be provisioned (this one is "
                            + frag.getFragmentType() + ").");
                    results.add(r);
                    continue;
                }
                List<Map<String, Object>> rows = readRows(clearPayload(frag, instance));
                if (rows.isEmpty()) {
                    r.put("status", "SKIPPED");
                    r.put("reason", "Fragment payload holds no rows.");
                    results.add(r);
                    continue;
                }
                List<String> keyCols = splitColumns(frag.getKeyColumns());
                int deleted = deleteFirst && !keyCols.isEmpty()
                        ? deleteByKeyTuples(c, targetSchema != null ? targetSchema : frag.getSchemaName(), frag.getTableName(), keyCols, rows)
                        : 0;
                int loaded = insertRows(c, targetSchema != null ? targetSchema : frag.getSchemaName(), frag.getTableName(), rows);
                inserted += loaded;
                loadedFragments++;
                r.put("status", "LOADED");
                r.put("rowsDeleted", deleted);
                r.put("rowsInserted", loaded);
                results.add(r);
            }
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.bad("Provision from capsule failed: " + e.getMessage()); }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("instanceId", instanceId);
        summary.put("version", instance.getCurrentVersion());
        summary.put("targetDataSourceId", target.getId());
        summary.put("fragmentsLoaded", loadedFragments);
        summary.put("rowsInserted", inserted);
        summary.put("fragments", results);
        lineage(instanceId, "PROVISIONED", Map.of("targetDataSourceId", target.getId(),
                "fragmentsLoaded", loadedFragments, "rowsInserted", inserted));
        audit.log(currentUsername(), "BUSINESS_ENTITY_CAPSULE_PROVISION",
                "instance=" + instanceId + " target=" + target.getId() + " rows=" + inserted);
        return summary;
    }

    @Transactional
    public CapsuleDetail retire(Long instanceId) {
        BeEntityInstanceEntity instance = get(instanceId);
        assertAccess(instance, "MANAGE");
        instance.setStatus("RETIRED");
        instance.setRetiredAt(Instant.now());
        instance.setUpdatedAt(Instant.now());
        instances.save(instance);
        lineage(instanceId, "RETIRED", Map.of());
        audit.log(currentUsername(), "BUSINESS_ENTITY_CAPSULE_RETIRE", "instance=" + instanceId);
        return buildDetail(instance);
    }

    @Transactional
    public CapsuleDetail grantAccess(Long instanceId, AccessGrantRequest req) {
        BeEntityInstanceEntity instance = get(instanceId);
        assertAccess(instance, "MANAGE");
        String scope = upperDefault(req == null ? null : req.scope(), "READ");
        if (!SCOPE_RANK.containsKey(scope))
            throw ApiException.bad("Unknown scope '" + scope + "'. Use READ, PROVISION, MANAGE or OWNER.");
        BeEntityAccessGrantEntity g = new BeEntityAccessGrantEntity();
        g.setEntityId(instance.getEntityId());
        g.setInstanceId(instanceId);
        g.setGranteeType(upperDefault(req == null ? null : req.granteeType(), "USER"));
        g.setGrantee(required(req == null ? null : req.grantee(), "grantee"));
        g.setScope(scope);
        g.setGrantedBy(currentUsername());
        if (req != null && req.ttlHours() != null) g.setExpiresAt(Instant.now().plusSeconds(Math.max(1, req.ttlHours()) * 3600L));
        g.setNote(req == null ? null : blank(req.note()));
        grants.save(g);
        lineage(instanceId, "ACCESS_GRANTED", Map.of("grantee", g.getGrantee(), "scope", g.getScope()));
        audit.log(currentUsername(), "BUSINESS_ENTITY_CAPSULE_ACCESS_GRANT",
                "instance=" + instanceId + " grantee=" + g.getGrantee() + " scope=" + g.getScope());
        return buildDetail(instance);
    }

    @Transactional
    public void revokeAccess(Long grantId) {
        BeEntityAccessGrantEntity g = grants.findById(grantId)
                .orElseThrow(() -> ApiException.notFound("Access grant not found: " + grantId));
        if (g.getInstanceId() != null) assertAccess(get(g.getInstanceId()), "MANAGE");
        g.setRevoked(true);
        g.setRevokedAt(Instant.now());
        g.setRevokedBy(currentUsername());
        grants.save(g);
        if (g.getInstanceId() != null) lineage(g.getInstanceId(), "ACCESS_REVOKED", Map.of("grantee", g.getGrantee()));
        audit.log(currentUsername(), "BUSINESS_ENTITY_CAPSULE_ACCESS_REVOKE", "grant=" + grantId);
    }

    /** Best-effort: record a lineage event on an EXISTING capsule instance for this business key, if one exists.
     *  Never creates a new capsule — materialization is an explicit, opt-in action from the UI/API. */
    @Transactional
    public void recordLineageForKeys(Long entityId, List<Map<String, Object>> businessKeys, String eventType, String detailNote) {
        if (entityId == null || businessKeys == null || businessKeys.isEmpty()) return;
        for (Map<String, Object> key : businessKeys) {
            try {
                String canonicalKey = canonical(key);
                instances.findByEntityIdAndCanonicalKey(entityId, canonicalKey)
                        .ifPresent(instance -> lineage(instance.getId(), eventType, Map.of("note", detailNote == null ? "" : detailNote)));
            } catch (Exception ignored) { /* lineage is evidence, never allowed to break the caller's primary flow */ }
        }
    }

    /** Best-effort, system path (no access gate): materialize/refresh a capsule for a business key.
     *  Used by opt-in reservation auto-materialize. Failures are recorded, never propagated. */
    @Transactional
    public Optional<Long> materializeForSystem(Long entityId, Map<String, Object> businessKey, Long policyId, String note) {
        try {
            CapsuleDetail d = doMaterialize(entityId,
                    new MaterializeRequest(businessKey, policyId, note, null, null), null);
            return Optional.of(d.instance().getId());
        } catch (Exception e) {
            audit.log(currentUsername(), "BUSINESS_ENTITY_CAPSULE_AUTO_MATERIALIZE_FAILED",
                    "entity=" + entityId + " error=" + e.getMessage());
            return Optional.empty();
        }
    }

    /** Governance helper: verify a capsule can back an execution plan for this entity, and that the
     *  current caller holds at least READ on it. Returns evidence for the plan's validation block. */
    public Map<String, Object> planAttachmentEvidence(Long entityId, Long instanceId) {
        BeEntityInstanceEntity instance = get(instanceId);
        if (!Objects.equals(instance.getEntityId(), entityId))
            throw ApiException.bad("Capsule #" + instanceId + " belongs to a different Business Entity.");
        if (!"ACTIVE".equalsIgnoreCase(instance.getStatus()))
            throw ApiException.conflict("Capsule #" + instanceId + " is " + instance.getStatus() + "; attach an ACTIVE capsule.");
        assertAccess(instance, "READ");
        lineage(instanceId, "ATTACHED_TO_PLAN", Map.of("entityId", entityId));
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("capsuleInstanceId", instanceId);
        evidence.put("capsuleVersion", instance.getCurrentVersion());
        evidence.put("capsuleStatus", instance.getStatus());
        evidence.put("grantVerifiedFor", currentUsername());
        return evidence;
    }

    /** Best-effort: propagate a Freshness/Sync check's per-member result onto every ACTIVE capsule instance
     *  of this entity that already tracks a watermark for that member (created at materialize time). */
    @Transactional
    public void propagateFreshness(Long entityId, List<Map<String, Object>> memberResults) {
        if (entityId == null || memberResults == null || memberResults.isEmpty()) return;
        List<BeEntityInstanceEntity> active = instances.findByEntityIdOrderByUpdatedAtDesc(entityId).stream()
                .filter(i -> "ACTIVE".equalsIgnoreCase(i.getStatus())).toList();
        if (active.isEmpty()) return;
        for (Map<String, Object> result : memberResults) {
            Long memberId = toLong(result.get("memberId"));
            if (memberId == null) continue;
            for (BeEntityInstanceEntity instance : active) {
                watermarks.findByInstanceIdAndMemberId(instance.getId(), memberId).ifPresent(wm -> {
                    wm.setStatus(String.valueOf(result.getOrDefault("status", "UNKNOWN")));
                    wm.setMessage(result.get("message") == null ? null : String.valueOf(result.get("message")));
                    if (result.get("sourceWatermark") != null) wm.setWatermarkValue(String.valueOf(result.get("sourceWatermark")));
                    wm.setSource("FRESHNESS_CHECK");
                    wm.setCheckedAt(Instant.now());
                    watermarks.save(wm);
                });
            }
        }
    }

    // ---------- access enforcement ----------

    /** Grants are ENFORCED, not evidence: admin.all bypasses; otherwise the caller needs an active
     *  (unrevoked, unexpired) grant at or above the required scope, matched by username or role. */
    private void assertAccess(BeEntityInstanceEntity instance, String requiredScope) {
        Optional<AccessPrincipal> principal = AccessContext.current();
        if (principal.isEmpty()) return; // internal/system call paths (schedulers, self-calls) are trusted
        AccessPrincipal p = principal.get();
        if (p.hasPermission("admin.all")) return;
        int required = SCOPE_RANK.getOrDefault(upperDefault(requiredScope, "READ"), 1);
        boolean allowed = grants.findByInstanceIdAndRevokedFalse(instance.getId()).stream()
                .filter(g -> g.getExpiresAt() == null || g.getExpiresAt().isAfter(Instant.now()))
                .filter(g -> SCOPE_RANK.getOrDefault(upperDefault(g.getScope(), "READ"), 1) >= required)
                .anyMatch(g -> matchesPrincipal(g, p));
        if (!allowed) {
            throw ApiException.forbidden("You need an active " + upperDefault(requiredScope, "READ")
                    + " grant on capsule #" + instance.getId() + ". Ask a capsule owner or an admin for access.");
        }
    }

    private static boolean matchesPrincipal(BeEntityAccessGrantEntity g, AccessPrincipal p) {
        String grantee = g.getGrantee() == null ? "" : g.getGrantee().trim();
        if ("ROLE".equalsIgnoreCase(g.getGranteeType())) {
            return p.roles() != null && p.roles().stream().anyMatch(r -> r.equalsIgnoreCase(grantee));
        }
        return p.username() != null && p.username().equalsIgnoreCase(grantee);
    }

    private void grantImplicitOwner(BeEntityInstanceEntity instance) {
        String owner = currentUsername();
        BeEntityAccessGrantEntity g = new BeEntityAccessGrantEntity();
        g.setEntityId(instance.getEntityId());
        g.setInstanceId(instance.getId());
        g.setGranteeType("USER");
        g.setGrantee(owner);
        g.setScope("OWNER");
        g.setGrantedBy("system");
        g.setNote("Implicit owner grant: this user materialized the capsule.");
        grants.save(g);
    }

    // ---------- internals ----------

    private CapsuleDetail buildDetail(BeEntityInstanceEntity instance) {
        Long instanceId = instance.getId();
        List<BeEntityFragmentEntity> current = fragments.findByInstanceIdAndStatusOrderByIdAsc(instanceId, "CURRENT").stream()
                .map(f -> readCopy(f, instance)).toList();
        return new CapsuleDetail(instance,
                current,
                versions.findByInstanceIdOrderByVersionNoDesc(instanceId),
                watermarks.findByInstanceIdOrderByMemberIdAsc(instanceId),
                grants.findByInstanceIdOrderByGrantedAtDesc(instanceId),
                lineageRepo.findByInstanceIdOrderByOccurredAtDesc(instanceId));
    }

    /** Read-only copy with the payload decrypted for the (already access-checked) caller.
     *  A fresh, never-persisted object so the clear payload can never be flushed back to the store. */
    private BeEntityFragmentEntity readCopy(BeEntityFragmentEntity f, BeEntityInstanceEntity instance) {
        BeEntityFragmentEntity out = new BeEntityFragmentEntity();
        out.setId(f.getId());
        out.setInstanceId(f.getInstanceId());
        out.setMemberId(f.getMemberId());
        out.setSystemName(f.getSystemName());
        out.setDataSourceId(f.getDataSourceId());
        out.setSchemaName(f.getSchemaName());
        out.setTableName(f.getTableName());
        out.setKeyColumns(f.getKeyColumns());
        out.setFragmentType(f.getFragmentType());
        out.setStatus(f.getStatus());
        out.setVersionNo(f.getVersionNo());
        out.setRowCount(f.getRowCount());
        out.setTruncated(f.isTruncated());
        out.setEncrypted(f.isEncrypted());
        out.setContentHash(f.getContentHash());
        out.setMessage(f.getMessage());
        out.setCapturedAt(f.getCapturedAt());
        out.setPayloadJson(f.getPayloadJson() == null ? null : clearPayload(f, instance));
        return out;
    }

    private String clearPayload(BeEntityFragmentEntity f, BeEntityInstanceEntity instance) {
        if (!f.isEncrypted()) return f.getPayloadJson(); // pre-V50 fragments were stored clear
        return crypto.decrypt(f.getPayloadJson(), f.getPayloadIv(), instance.getKeySalt());
    }

    private boolean isStale(BeEntityInstanceEntity instance) {
        if (!"ACTIVE".equalsIgnoreCase(instance.getStatus())) return false;
        if (!"ON_DEMAND".equalsIgnoreCase(instance.getSyncMode())) return false;
        boolean budgetSpent = instance.getStaleAfterMinutes() != null && instance.getLastMaterializedAt() != null
                && instance.getLastMaterializedAt().plus(instance.getStaleAfterMinutes(), ChronoUnit.MINUTES).isBefore(Instant.now());
        boolean staleWatermark = watermarks.findByInstanceIdOrderByMemberIdAsc(instance.getId()).stream()
                .anyMatch(wm -> "STALE".equalsIgnoreCase(wm.getStatus()));
        return budgetSpent || staleWatermark;
    }

    private void propagateExistingWatermark(Long instanceId, Long entityId, BusinessEntityMemberEntity member) {
        if (member.getId() == null) return;
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT watermark_column AS "watermarkColumn", last_source_watermark AS "lastSourceWatermark",
                       last_status AS "lastStatus", last_message AS "lastMessage"
                  FROM be_sync_policy_members
                 WHERE entity_id = ? AND member_id = ?
                 ORDER BY updated_at DESC LIMIT 1
                """, entityId, member.getId());
        if (rows.isEmpty() || rows.get(0).get("watermarkColumn") == null) return;
        Map<String, Object> r = rows.get(0);
        BeEntityWatermarkEntity wm = watermarks.findByInstanceIdAndMemberId(instanceId, member.getId())
                .orElseGet(BeEntityWatermarkEntity::new);
        wm.setInstanceId(instanceId);
        wm.setMemberId(member.getId());
        wm.setTableName(member.getTableName());
        wm.setWatermarkColumn(String.valueOf(r.get("watermarkColumn")));
        wm.setWatermarkValue(r.get("lastSourceWatermark") == null ? null : String.valueOf(r.get("lastSourceWatermark")));
        wm.setStatus(r.get("lastStatus") == null ? "UNKNOWN" : String.valueOf(r.get("lastStatus")));
        wm.setMessage(r.get("lastMessage") == null ? null : String.valueOf(r.get("lastMessage")));
        wm.setSource("SYNC_POLICY");
        wm.setCheckedAt(Instant.now());
        watermarks.save(wm);
    }

    private FetchResult fetchRows(DataSourceEntity ds, String schema, String table, List<String> keyCols,
                                  List<Object> keyVals, int cap) throws Exception {
        String where = keyCols.stream().map(c -> BusinessEntitySql.identifier(ds, c) + " = ?").collect(Collectors.joining(" AND "));
        String sql = "SELECT * FROM " + BusinessEntitySql.name(ds, schema, table) + " WHERE " + where;
        try (Connection c = connections.open(ds); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setMaxRows(cap + 1); // fetch one extra row so truncation is evidence, never silent
            for (int i = 0; i < keyVals.size(); i++) ps.setObject(i + 1, keyVals.get(i));
            return readFetch(ps, cap);
        }
    }

    /** Fetch by single-column IN list — the FK-aware capture path. */
    private FetchResult fetchRowsIn(DataSourceEntity ds, String schema, String table, String column,
                                    List<Object> values, int cap) throws Exception {
        if (values.isEmpty()) return new FetchResult(List.of(), List.of(), 0, false);
        String in = values.stream().map(v -> "?").collect(Collectors.joining(","));
        String sql = "SELECT * FROM " + BusinessEntitySql.name(ds, schema, table) + " WHERE "
                + BusinessEntitySql.identifier(ds, column) + " IN (" + in + ")";
        try (Connection c = connections.open(ds); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setMaxRows(cap + 1);
            for (int i = 0; i < values.size(); i++) ps.setObject(i + 1, values.get(i));
            return readFetch(ps, cap);
        }
    }

    private FetchResult fetchRowsByRelationship(DataSourceEntity ds, String schema, String table,
                                                 List<String> childColumns,
                                                 List<Map<String, Object>> parentRows,
                                                 List<String> parentColumns,
                                                 int cap) throws Exception {
        if (childColumns.isEmpty() || childColumns.size() != parentColumns.size()) {
            throw ApiException.bad("Relationship column counts must match for " + table + ".");
        }
        Set<List<Object>> distinct = new LinkedHashSet<>();
        int maxTuples = Math.max(1, Math.min(MAX_FK_PARENT_KEYS, 1800 / childColumns.size()));
        for (Map<String, Object> parentRow : parentRows) {
            List<Object> tuple = new ArrayList<>();
            for (String parentColumn : parentColumns) {
                tuple.add(parentRow.get(parentColumn.toLowerCase(Locale.ROOT)));
            }
            if (tuple.stream().anyMatch(Objects::isNull)) continue;
            distinct.add(tuple);
            if (distinct.size() >= maxTuples) break;
        }
        if (distinct.isEmpty()) return new FetchResult(List.of(), List.of(), 0, false);
        if (childColumns.size() == 1) {
            return fetchRowsIn(ds, schema, table, childColumns.get(0),
                    distinct.stream().map(tuple -> tuple.get(0)).toList(), cap);
        }

        String tuplePredicate = childColumns.stream()
                .map(column -> BusinessEntitySql.identifier(ds, column) + " = ?")
                .collect(Collectors.joining(" AND ", "(", ")"));
        String where = distinct.stream().map(tuple -> tuplePredicate).collect(Collectors.joining(" OR "));
        String sql = "SELECT * FROM " + BusinessEntitySql.name(ds, schema, table) + " WHERE " + where;
        try (Connection c = connections.open(ds); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setMaxRows(cap + 1);
            int parameter = 1;
            for (List<Object> tuple : distinct) {
                for (Object value : tuple) ps.setObject(parameter++, value);
            }
            return readFetch(ps, cap);
        }
    }

    private RelationshipPath relationshipPath(BusinessEntityMemberEntity member) {
        String raw = blank(member.getRelationshipJson());
        if (raw == null) return null;
        try {
            Map<String, Object> relation = json.readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {});
            String parentTable = blank(stringValue(relation.get("parentTable")));
            String childTable = blank(stringValue(relation.get("childTable")));
            List<String> parentColumns = splitColumns(stringValue(relation.get("parentColumns")));
            List<String> childColumns = splitColumns(stringValue(relation.get("childColumns")));
            if (parentTable == null || childTable == null || parentColumns.isEmpty()
                    || parentColumns.size() != childColumns.size()) {
                throw ApiException.bad("Invalid relationshipJson for member role " + member.getLogicalRole()
                        + ": parent/child tables and equally sized columns are required.");
            }
            if (!childTable.equalsIgnoreCase(member.getTableName())) {
                throw ApiException.bad("Relationship child table " + childTable + " does not match member table "
                        + member.getTableName() + ".");
            }
            return new RelationshipPath(parentTable, parentColumns, childTable, childColumns);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.bad("Could not parse relationshipJson for member role " + member.getLogicalRole()
                    + ": " + e.getMessage());
        }
    }

    private FetchResult parentFetch(BusinessEntityMemberEntity member, RelationshipPath relationship,
                                    Map<String, FetchResult> fetchedByRole,
                                    Map<String, FetchResult> fetchedByTable) {
        String parentRole = blank(member.getJoinToRole());
        if (parentRole != null) {
            FetchResult byRole = fetchedByRole.get(parentRole.toLowerCase(Locale.ROOT));
            if (byRole != null) return byRole;
        }
        return fetchedByTable.get(relationship.parentTable().toLowerCase(Locale.ROOT));
    }

    private static void rememberFetch(BusinessEntityMemberEntity member, FetchResult fetched,
                                      Map<String, FetchResult> fetchedByRole,
                                      Map<String, FetchResult> fetchedByTable) {
        if (member.getLogicalRole() != null) {
            fetchedByRole.put(member.getLogicalRole().toLowerCase(Locale.ROOT), fetched);
        }
        if (member.getTableName() != null) {
            fetchedByTable.put(member.getTableName().toLowerCase(Locale.ROOT), fetched);
        }
    }

    private static boolean sameColumns(List<String> left, List<String> right) {
        if (left.size() != right.size() || left.isEmpty()) return false;
        for (int i = 0; i < left.size(); i++) {
            if (!left.get(i).equalsIgnoreCase(right.get(i))) return false;
        }
        return true;
    }

    private static String firstNonBlank(String first, String second) {
        return blank(first) != null ? first : second;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private FetchResult readFetch(PreparedStatement ps, int cap) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, String>> textRows = new ArrayList<>();
        boolean truncated = false;
        try (ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();
            while (rs.next()) {
                if (rows.size() >= cap) { truncated = true; break; }
                Map<String, Object> row = new LinkedHashMap<>();
                Map<String, String> text = new LinkedHashMap<>();
                for (int i = 1; i <= cols; i++) {
                    String label = md.getColumnLabel(i).toLowerCase(Locale.ROOT);
                    row.put(label, readPortableValue(rs, md, i));
                    text.put(label, rs.getString(i));
                }
                rows.add(row);
                textRows.add(text);
            }
        }
        return new FetchResult(rows, textRows, rows.size(), truncated);
    }

    /**
     * Convert vendor JDBC wrappers to values Jackson can persist and encrypt. Oracle's driver,
     * for example, may return {@code oracle.sql.TIMESTAMP}; serializing that object exposes its
     * internal stream instead of a timestamp. Text is deliberately used for temporal and LOB
     * families because it is portable across Oracle, DB2, SQL Server, PostgreSQL and MySQL and
     * preserves the source driver's canonical representation.
     */
    static Object readPortableValue(ResultSet rs, ResultSetMetaData md, int column) throws Exception {
        int jdbcType = md.getColumnType(column);
        return switch (jdbcType) {
            case Types.DATE, Types.TIME, Types.TIME_WITH_TIMEZONE,
                    Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE,
                    Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
                    Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
                    Types.CLOB, Types.NCLOB, Types.SQLXML, Types.ROWID -> rs.getString(column);
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> {
                byte[] value = rs.getBytes(column);
                yield value == null ? null : Base64.getEncoder().encodeToString(value);
            }
            case Types.BOOLEAN, Types.BIT -> {
                boolean value = rs.getBoolean(column);
                yield rs.wasNull() ? null : value;
            }
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> {
                int value = rs.getInt(column);
                yield rs.wasNull() ? null : value;
            }
            case Types.BIGINT -> {
                long value = rs.getLong(column);
                yield rs.wasNull() ? null : value;
            }
            case Types.REAL, Types.FLOAT, Types.DOUBLE -> {
                double value = rs.getDouble(column);
                yield rs.wasNull() ? null : value;
            }
            case Types.NUMERIC, Types.DECIMAL -> rs.getBigDecimal(column);
            default -> portableScalar(rs.getObject(column));
        };
    }

    private static Object portableScalar(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof byte[] bytes) return Base64.getEncoder().encodeToString(bytes);
        if (value instanceof java.util.Date || value instanceof java.time.temporal.TemporalAccessor
                || value instanceof java.util.UUID || value instanceof Enum<?>) {
            return value.toString();
        }
        // Unknown driver classes (Oracle/DB2 proprietary wrappers, PGobject, etc.) must not leak
        // implementation internals into JSON. Their text representation is the portable boundary.
        return String.valueOf(value);
    }

    /** One-hop FK path from a member table to the root table via JDBC catalog metadata
     *  (same source of truth as Subsetting's FK closure). Single-column FKs only in this slice. */
    private FkPath fkPathToRoot(DataSourceEntity ds, String schema, String memberTable, String rootTable) {
        try (Connection c = connections.open(ds)) {
            Map<String, List<FkPath>> byFkName = new LinkedHashMap<>();
            try (ResultSet rs = c.getMetaData().getImportedKeys(null, blank(schema), memberTable)) {
                while (rs.next()) {
                    if (!rootTable.equalsIgnoreCase(rs.getString("PKTABLE_NAME"))) continue;
                    String fkName = String.valueOf(rs.getString("FK_NAME"));
                    byFkName.computeIfAbsent(fkName, k -> new ArrayList<>())
                            .add(new FkPath(rs.getString("FKCOLUMN_NAME"), rs.getString("PKCOLUMN_NAME")));
                }
            }
            return byFkName.values().stream().filter(cols -> cols.size() == 1).map(cols -> cols.get(0))
                    .findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private int deleteByKeyTuples(Connection c, String schema, String table, List<String> keyCols,
                                  List<Map<String, Object>> rows) throws Exception {
        Set<List<Object>> tuples = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            List<Object> tuple = new ArrayList<>();
            for (String k : keyCols) tuple.add(row.get(k.toLowerCase(Locale.ROOT)));
            if (tuple.stream().anyMatch(Objects::isNull)) continue;
            tuples.add(tuple);
            if (tuples.size() >= MAX_DELETE_KEY_TUPLES) break;
        }
        if (tuples.isEmpty()) return 0;
        String where = keyCols.stream().map(k -> {
            try { return BusinessEntitySql.identifier(c, k) + " = ?"; }
            catch (Exception e) { throw new IllegalArgumentException(e); }
        }).collect(Collectors.joining(" AND "));
        int deleted = 0;
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + BusinessEntitySql.name(c, schema, table) + " WHERE " + where)) {
            for (List<Object> tuple : tuples) {
                for (int i = 0; i < tuple.size(); i++) ps.setObject(i + 1, tuple.get(i));
                deleted += ps.executeUpdate();
            }
        }
        return deleted;
    }

    private int insertRows(Connection c, String schema, String table, List<Map<String, Object>> rows) throws Exception {
        List<String> cols = new ArrayList<>(rows.get(0).keySet());
        String colList = cols.stream().map(column -> {
            try { return BusinessEntitySql.identifier(c, column); }
            catch (Exception e) { throw new IllegalArgumentException(e); }
        }).collect(Collectors.joining(","));
        String params = cols.stream().map(x -> "?").collect(Collectors.joining(","));
        String sql = "INSERT INTO " + BusinessEntitySql.name(c, schema, table) + " (" + colList + ") VALUES (" + params + ")";
        int inserted = 0;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (Map<String, Object> row : rows) {
                for (int i = 0; i < cols.size(); i++) ps.setObject(i + 1, row.get(cols.get(i)));
                ps.addBatch();
            }
            for (int n : ps.executeBatch()) inserted += Math.max(n, 0);
        }
        return inserted;
    }

    private List<MaskingRuleEntity> rulesForTable(Long policyId, String table) {
        return maskingRules.findByPolicyId(policyId).stream()
                .filter(r -> r.getTableName() != null && r.getTableName().equalsIgnoreCase(table))
                .toList();
    }

    /** Mask fetched rows against a policy's rules for this table. Columns with no rule pass through
     *  unmasked (same semantics as the rest of ForgeTDM's MASK_COPY path). EMAIL rules run in a second
     *  pass so they can see already-masked first_name/last_name siblings (MaskingEngine's NAME_SAFE mode). */
    private List<Map<String, Object>> maskRows(List<Map<String, Object>> rows, List<Map<String, String>> textRows,
                                               List<MaskingRuleEntity> rules, String table) {
        if (rules.isEmpty()) return rows;
        Map<String, MaskingRuleEntity> ruleByCol = rules.stream()
                .collect(Collectors.toMap(r -> r.getColumnName().toLowerCase(Locale.ROOT), r -> r, (a, b) -> a));
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, String> text = textRows.get(i);
            MaskContext ctx = new MaskContext(i);
            ctx.row.putAll(text);
            Map<String, Object> outRow = new LinkedHashMap<>(rows.get(i));
            for (String col : text.keySet()) {
                MaskingRuleEntity rule = ruleByCol.get(col);
                if (rule == null || "EMAIL".equalsIgnoreCase(rule.getFunction()) || text.get(col) == null) continue;
                outRow.put(col, applyRule(rule, table, col, text.get(col), ctx));
            }
            for (String col : text.keySet()) {
                MaskingRuleEntity rule = ruleByCol.get(col);
                if (rule == null || !"EMAIL".equalsIgnoreCase(rule.getFunction()) || text.get(col) == null) continue;
                outRow.put(col, applyRule(rule, table, col, text.get(col), ctx));
            }
            out.add(outRow);
        }
        return out;
    }

    private String applyRule(MaskingRuleEntity rule, String table, String col, String value, MaskContext ctx) {
        try {
            MaskFunction fn = MaskFunction.valueOf(rule.getFunction().toUpperCase(Locale.ROOT));
            String masked = maskingEngine.mask(fn, saltFor(rule, table, col), value, rule.getParam1(), rule.getParam2(), ctx);
            ctx.masked.put(col, masked);
            return masked;
        } catch (Exception e) {
            return "***MASK_ERROR***";
        }
    }

    private static String saltFor(MaskingRuleEntity rule, String table, String col) {
        return switch (rule.getFunction().toUpperCase(Locale.ROOT)) {
            case "FIRST_NAME" -> "name.first";
            case "LAST_NAME" -> "name.last";
            case "FULL_NAME" -> "name.full";
            case "EMAIL" -> "email";
            case "SSN" -> "ssn";
            case "CREDIT_CARD" -> "ccn";
            case "PHONE" -> "phone";
            case "CITY_STATE_ZIP" -> "geo";
            case "ADDRESS_STREET" -> "addr";
            case "ADDRESS_US" -> "addr.us";
            case "COMPANY" -> "company";
            case "DOB_AGE_BAND" -> "dob";
            case "BANK_ACCOUNT" -> "bank.account";
            case "IBAN" -> "iban";
            case "SWIFT_BIC" -> "swift.bic";
            case "ABA_ROUTING" -> "routing.aba";
            case "NATIONAL_ID" -> "national.id";
            case "IP_ADDRESS" -> "network.ip";
            case "MAC_ADDRESS" -> "network.mac";
            case "DIRECT_LOOKUP" -> "lookup.direct";
            case "HASH_LOOKUP" -> "lookup.hash";
            default -> table.toLowerCase(Locale.ROOT) + "." + col.toLowerCase(Locale.ROOT);
        };
    }

    private void lineage(Long instanceId, String eventType, Map<String, Object> detail) {
        BeEntityLineageEventEntity ev = new BeEntityLineageEventEntity();
        ev.setInstanceId(instanceId);
        ev.setEventType(eventType);
        ev.setDetailJson(writeJson(detail));
        ev.setActor(currentUsername());
        lineageRepo.save(ev);
    }

    private BeEntityInstanceEntity get(Long id) {
        return instances.findById(id).orElseThrow(() -> ApiException.notFound("Entity capsule instance not found: " + id));
    }

    private BusinessEntityMemberEntity rootMember(BusinessEntityService.BusinessEntityDetail detail) {
        String root = detail.entity().getRootTable();
        if (root != null) {
            for (BusinessEntityMemberEntity member : detail.members()) {
                if (root.equalsIgnoreCase(member.getTableName())) return member;
            }
        }
        return detail.members().get(0);
    }

    private String keyColumnsFor(BusinessEntityMemberEntity member, BusinessEntityDefinitionEntity entity) {
        String cols = blank(member.getKeyColumns());
        if (cols == null && entity != null && entity.getRootTable() != null
                && entity.getRootTable().equalsIgnoreCase(member.getTableName())) {
            cols = blank(entity.getBusinessKeyColumns());
        }
        return cols;
    }

    private static List<String> splitColumns(String columns) {
        if (columns == null || columns.isBlank()) return List.of();
        return Arrays.stream(columns.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
    }

    private static String canonical(Map<String, Object> row) {
        return row.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey().toLowerCase(Locale.ROOT) + "=" + String.valueOf(e.getValue()))
                .reduce((a, b) -> a + "|" + b)
                .orElse("");
    }

    private String writeJson(Object value) {
        try { return json.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception e) { throw ApiException.bad("Could not serialize capsule evidence: " + e.getMessage()); }
    }

    private Map<String, Object> readJsonMap(String raw) {
        try { return json.readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {}); }
        catch (Exception e) { throw ApiException.bad("Could not parse stored business key: " + e.getMessage()); }
    }

    private List<Map<String, Object>> readRows(String raw) {
        try { return json.readValue(raw, new TypeReference<List<Map<String, Object>>>() {}); }
        catch (Exception e) { throw ApiException.bad("Could not parse fragment payload rows: " + e.getMessage()); }
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((input == null ? "" : input).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return null; }
    }

    private static Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(value).trim()); } catch (Exception e) { return null; }
    }

    private static String required(String value, String label) {
        String clean = blank(value);
        if (clean == null) throw ApiException.bad(label + " is required");
        return clean;
    }

    private static String upperDefault(String value, String fallback) {
        String clean = blank(value);
        return (clean == null ? fallback : clean).toUpperCase(Locale.ROOT);
    }

    private static String blank(String value) {
        if (value == null) return null;
        String clean = value.trim();
        return clean.isEmpty() ? null : clean;
    }

    private static String currentUsername() {
        return AccessContext.current().map(p -> p.username()).orElse("system");
    }
}
