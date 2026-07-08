package io.forgetdm.reservation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.subset.SubsetService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Find & Reserve: a tester asks for rows matching criteria; ForgeTDM finds rows
 * NOT already reserved by anyone else, locks them with a TTL, and returns the keys.
 * Prevents two test streams consuming/mutating the same records.
 */
@Service
public class ReservationService {

    private final ReservationRepository repo;
    private final DataSourceService dataSources;
    private final ConnectionFactory connections;
    private final SubsetService subsets;
    private final AuditService audit;
    private final ObjectMapper json = new ObjectMapper();

    public ReservationService(ReservationRepository repo, DataSourceService dataSources,
                              ConnectionFactory connections, SubsetService subsets, AuditService audit) {
        this.repo = repo; this.dataSources = dataSources; this.connections = connections;
        this.subsets = subsets; this.audit = audit;
    }

    public synchronized ReservationEntity findAndReserve(Long dsId, String table, String criteria,
                                                         int count, String reservedBy, String purpose, int ttlHours) {
        SubsetService.guardFilter(criteria);
        DataSourceEntity ds = dataSources.get(dsId);

        Set<String> alreadyReserved = activeKeys(dsId, table);
        List<String> picked = new ArrayList<>();
        try (Connection c = connections.open(ds)) {
            String pk = subsets.primaryKey(c, table);
            String sql = "SELECT " + q(pk) + " FROM " + q(table)
                    + (criteria == null || criteria.isBlank() ? "" : " WHERE " + criteria);
            try (Statement st = c.createStatement()) {
                st.setMaxRows(Math.max(count * 5, 500)); // overscan, then skip conflicts
                try (ResultSet rs = st.executeQuery(sql)) {
                    while (rs.next() && picked.size() < count) {
                        String key = rs.getString(1);
                        if (key != null && !alreadyReserved.contains(key)) picked.add(key);
                    }
                }
            }
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.bad("Find & reserve failed: " + e.getMessage()); }

        if (picked.size() < count)
            throw ApiException.conflict("Only " + picked.size() + " unreserved rows match — "
                    + (count - picked.size()) + " short. Release stale reservations or widen criteria.");

        ReservationEntity r = new ReservationEntity();
        r.setDataSourceId(dsId);
        r.setTableName(table);
        r.setCriteria(criteria);
        r.setReservedBy(reservedBy == null ? "anonymous" : reservedBy);
        r.setPurpose(purpose);
        r.setExpiresAt(Instant.now().plus(Math.max(1, ttlHours), ChronoUnit.HOURS));
        try { r.setRowKeysJson(json.writeValueAsString(picked)); }
        catch (Exception e) { throw new IllegalStateException(e); }
        ReservationEntity saved = repo.save(r);
        audit.log(r.getReservedBy(), "DATA_RESERVED", table + " x" + picked.size() + " until " + r.getExpiresAt());
        return saved;
    }

    public Set<String> activeKeys(Long dsId, String table) {
        Set<String> keys = new HashSet<>();
        for (ReservationEntity r : repo.findByDataSourceIdAndTableNameAndStatus(dsId, table, "ACTIVE")) {
            if (r.getExpiresAt().isBefore(Instant.now())) continue;
            keys.addAll(parseKeys(r.getRowKeysJson()));
        }
        return keys;
    }

    public ReservationEntity release(Long id) {
        ReservationEntity r = repo.findById(id).orElseThrow(() -> ApiException.notFound("Reservation " + id + " not found"));
        r.setStatus("RELEASED");
        audit.log(r.getReservedBy(), "DATA_RELEASED", "reservation=" + id);
        return repo.save(r);
    }

    public List<ReservationEntity> list() {
        List<ReservationEntity> all = repo.findAll();
        all.sort(Comparator.comparing(ReservationEntity::getId).reversed());
        return all;
    }

    @Scheduled(fixedDelay = 60_000)
    public void expireStale() {
        for (ReservationEntity r : repo.findByStatus("ACTIVE")) {
            if (r.getExpiresAt().isBefore(Instant.now())) { r.setStatus("EXPIRED"); repo.save(r); }
        }
    }

    private List<String> parseKeys(String jsonStr) {
        try { return json.readValue(jsonStr, new TypeReference<List<String>>() {}); }
        catch (Exception e) { return List.of(); }
    }

    private static String q(String ident) {
        if (!ident.matches("[A-Za-z0-9_]+")) throw ApiException.bad("Illegal identifier: " + ident);
        return "\"" + ident + "\"";
    }
}
