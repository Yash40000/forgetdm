package io.forgetdm.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Persists audit events in their own transaction (DEF-0008 / DEF-0009).
 *
 * <p>Two properties this class exists to guarantee:
 *
 * <ol>
 *   <li><b>Survives caller rollback.</b> {@code REQUIRES_NEW} means an audit row commits even when the
 *       business transaction that triggered it rolls back. Previously a failed login wrote
 *       {@code LOGIN_FAILED} and then threw, so the very exception being recorded discarded the
 *       record — no failed authentication was ever persisted.</li>
 *   <li><b>Atomic, unique sequence.</b> {@code seq} comes from the Postgres sequence
 *       {@code audit_event_seq}, not an in-JVM counter, and the column is UNIQUE (V62). Two
 *       instances can no longer allocate the same number and fork the hash chain, which is what
 *       produced the duplicate seq 702/703 found by AUD-001-08.</li>
 * </ol>
 *
 * <p>{@code prevHash} is read from the database inside this transaction rather than cached in
 * memory, so the chain stays correct across restarts and rolled-back writes.
 */
@Component
public class AuditWriter {

    private final AuditEventRepository repo;
    private final JdbcTemplate jdbc;

    public AuditWriter(AuditEventRepository repo, JdbcTemplate jdbc) {
        this.repo = repo;
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditEventEntity append(AuditEventEntity e) {
        // DEF-0015: the hash covers createdAt.toEpochMilli(), but Instant.now() carries nanoseconds
        // and Postgres stores microseconds — the nano->micro conversion ROUNDS, so a timestamp whose
        // remainder sits just under a millisecond boundary re-reads one millisecond later than the
        // value that was hashed. That produced CONTENT_MISMATCH on untampered rows, i.e. a false
        // tamper alarm. Truncating to milliseconds makes the hashed value and the stored value
        // identical by construction, and loses nothing the hash was protecting.
        Instant when = e.getCreatedAt() != null ? e.getCreatedAt() : Instant.now();
        e.setCreatedAt(when.truncatedTo(ChronoUnit.MILLIS));
        if (e.getOutcome() == null)   e.setOutcome("SUCCESS");
        if (e.getSeverity() == null)  e.setSeverity("INFO");
        if (e.getCategory() == null)  e.setCategory("GENERAL");

        e.setSeq(nextSeq());
        e.setPrevHash(currentHead());
        e.setHash(AuditHash.compute(e.getPrevHash(), e));
        return repo.save(e);
    }

    /**
     * Appends an explicit operator-approved checkpoint without rewriting historical rows. The empty
     * parent is intentional: verification treats this signed event as a new active-ledger genesis
     * and validates its immutable digest of every row that came before it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditEventEntity appendAnchor(AuditEventEntity e) {
        Instant when = e.getCreatedAt() != null ? e.getCreatedAt() : Instant.now();
        e.setCreatedAt(when.truncatedTo(ChronoUnit.MILLIS));
        if (e.getOutcome() == null) e.setOutcome("SUCCESS");
        if (e.getSeverity() == null) e.setSeverity("NOTICE");
        if (e.getCategory() == null) e.setCategory("SECURITY");
        e.setSeq(nextSeq());
        e.setPrevHash("");
        e.setHash(AuditHash.compute("", e));
        return repo.save(e);
    }

    private Long nextSeq() {
        Long seq = jdbc.queryForObject("SELECT nextval('audit_event_seq')", Long.class);
        return seq == null ? 1L : seq;
    }

    /** Hash of the newest committed event — the parent this event chains onto ("" for genesis). */
    private String currentHead() {
        String head = jdbc.query(
                "SELECT hash FROM audit_events WHERE hash IS NOT NULL ORDER BY seq DESC LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : null);
        return head == null ? "" : head;
    }
}
