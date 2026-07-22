package io.forgetdm.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Guards the audit hash contract found broken by AUD-001-08 (DEF-0009).
 *
 * <p>These are pure-function tests over {@link AuditHash}: the writer and {@code verifyChain()} must
 * hash identically, and any change to a recorded field must change the hash — otherwise history could
 * be edited without detection.
 */
class AuditHashChainTest {

    private AuditEventEntity event(long seq, String actor, String action, String detail, Instant at) {
        AuditEventEntity e = new AuditEventEntity();
        e.setSeq(seq);
        e.setActor(actor);
        e.setAction(action);
        e.setCategory("SECURITY");
        e.setOutcome("SUCCESS");
        e.setSeverity("INFO");
        e.setDetail(detail);
        e.setCreatedAt(at);
        return e;
    }

    @Test
    void hashIsDeterministicForIdenticalInput() {
        Instant at = Instant.parse("2026-07-18T10:00:00Z");
        String a = AuditHash.compute("prev", event(1, "admin", "LOGIN_SUCCESS", "x", at));
        String b = AuditHash.compute("prev", event(1, "admin", "LOGIN_SUCCESS", "x", at));
        assertEquals(a, b, "same inputs must hash identically or the chain self-invalidates");
    }

    @Test
    void tamperingWithAnyRecordedFieldChangesTheHash() {
        Instant at = Instant.parse("2026-07-18T10:00:00Z");
        AuditEventEntity base = event(1, "admin", "POLICY_DELETED", "id=34", at);
        String original = AuditHash.compute("prev", base);

        assertNotEquals(original, AuditHash.compute("prev", event(1, "attacker", "POLICY_DELETED", "id=34", at)),
                "actor rewrite must be detected");
        assertNotEquals(original, AuditHash.compute("prev", event(1, "admin", "POLICY_CREATED", "id=34", at)),
                "action rewrite must be detected");
        assertNotEquals(original, AuditHash.compute("prev", event(1, "admin", "POLICY_DELETED", "id=99", at)),
                "detail rewrite must be detected");
        assertNotEquals(original, AuditHash.compute("prev", event(2, "admin", "POLICY_DELETED", "id=34", at)),
                "sequence rewrite must be detected");
        assertNotEquals(original, AuditHash.compute("prev", event(1, "admin", "POLICY_DELETED", "id=34",
                        at.plusSeconds(1))),
                "timestamp rewrite must be detected");
    }

    @Test
    void reParentingAnEventChangesTheHash() {
        Instant at = Instant.parse("2026-07-18T10:00:00Z");
        AuditEventEntity e = event(5, "admin", "LOGIN_SUCCESS", "x", at);
        assertNotEquals(AuditHash.compute("parentA", e), AuditHash.compute("parentB", e),
                "an event moved to a different parent must not keep its hash - this is what makes "
                        + "deleting an intermediate row detectable");
    }

    /**
     * DEF-0015: the hash consumes {@code createdAt.toEpochMilli()}, but Postgres stores microseconds
     * and the nano→micro conversion rounds. A timestamp sitting just under a millisecond boundary
     * therefore re-read one millisecond later than it was hashed, producing a CONTENT_MISMATCH on an
     * untampered row — a false tamper alarm. Writing millisecond-truncated timestamps makes the hash
     * stable across the database round trip.
     */
    @Test
    void millisecondTruncationSurvivesMicrosecondRoundTrip() {
        Instant nanos = Instant.parse("2026-07-18T09:41:25.592999600Z");   // rounds up to .593000 in PG
        Instant truncated = nanos.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);

        // What the database would hand back after rounding the *untruncated* value.
        Instant roundedByDb = Instant.parse("2026-07-18T09:41:25.593000Z");
        assertNotEquals(AuditHash.compute("p", event(1, "admin", "LOGIN_SUCCESS", "x", nanos)),
                        AuditHash.compute("p", event(1, "admin", "LOGIN_SUCCESS", "x", roundedByDb)),
                        "this mismatch is exactly the false alarm DEF-0015 describes");

        // After truncation the stored value is already exact, so the round trip is lossless.
        Instant roundTripped = Instant.parse("2026-07-18T09:41:25.592000Z");
        assertEquals(truncated, roundTripped);
        assertEquals(AuditHash.compute("p", event(1, "admin", "LOGIN_SUCCESS", "x", truncated)),
                     AuditHash.compute("p", event(1, "admin", "LOGIN_SUCCESS", "x", roundTripped)),
                     "millisecond-truncated timestamps must hash identically after persistence");
    }

    @Test
    void nullFieldsHashAsEmptyRatherThanThrowing() {
        AuditEventEntity e = new AuditEventEntity();
        e.setSeq(1L);
        e.setAction("X");
        assertNotNull(AuditHash.compute(null, e), "genesis/sparse events must still hash");
    }

    @Test
    void legacyHashVersionIgnoresNewTenancyFields() {
        AuditEventEntity e = event(1, "admin", "LOGIN_SUCCESS", "x",
                Instant.parse("2026-07-18T10:00:00Z"));
        e.setHashVersion(1);
        String legacy = AuditHash.compute("prev", e);
        e.setOwnerUserId(99L);
        e.setOwnerUsername("changed-owner");
        e.setOwnerGroupId(77L);
        e.setVisibility("PRIVATE");
        assertEquals(legacy, AuditHash.compute("prev", e),
                "V70 must not invalidate historical version-1 ledger rows");
    }

    @Test
    void versionTwoMakesTenancyTamperEvident() {
        AuditEventEntity e = event(1, "admin", "LOGIN_SUCCESS", "x",
                Instant.parse("2026-07-18T10:00:00Z"));
        e.setHashVersion(2);
        e.setOwnerUserId(1L);
        e.setOwnerUsername("alpha");
        e.setOwnerGroupId(10L);
        e.setVisibility("GROUP");
        String original = AuditHash.compute("prev", e);

        e.setVisibility("SHARED");
        assertNotEquals(original, AuditHash.compute("prev", e),
                "changing audit visibility must break a version-2 event hash");
    }

    @Test
    void verificationReanchorsAfterHistoricalLinkBreakAndChecksLaterEvents() {
        Instant at = Instant.parse("2026-07-18T10:00:00Z");
        AuditEventEntity first = chainedEvent(1, "", at);
        AuditEventEntity second = chainedEvent(2, first.getHash(), at.plusSeconds(1));

        // This models a preserved pre-V62 fork: its content is intact, but its parent link is not.
        AuditEventEntity historicalFork = chainedEvent(3050, "missing-parent-hash", at.plusSeconds(2));
        AuditEventEntity afterFork = chainedEvent(3052, historicalFork.getHash(), at.plusSeconds(3));

        AuditEventRepository repository = mock(AuditEventRepository.class);
        when(repository.findAllByOrderBySeqAsc())
                .thenReturn(List.of(first, second, historicalFork, afterFork));

        AuditService service = new AuditService(repository, mock(AuditWriter.class), new ObjectMapper());
        Map<String, Object> result = service.verifyChain();

        assertEquals(false, result.get("valid"));
        assertEquals(false, result.get("tamperSuspected"),
                "a known link discontinuity must not be reported as edited content");
        assertEquals(1, result.get("linkBreaks"));
        assertEquals(0, result.get("contentBreaks"));
        assertEquals(3, result.get("verifiedCount"),
                "verification must continue with the valid segment after the historical break");
        assertEquals(3052L, result.get("verifiedThroughSeq"));
    }

    @Test
    void disposableChainVerifiesCleanThenDetectsTamperAtExactSequence() {
        Instant at = Instant.parse("2026-07-18T11:00:00Z");
        AuditEventEntity first = chainedEvent(1, "", at);
        AuditEventEntity second = chainedEvent(2, first.getHash(), at.plusSeconds(1));
        AuditEventEntity third = chainedEvent(3, second.getHash(), at.plusSeconds(2));

        AuditEventEntity tamperedSecond = chainedEvent(2, first.getHash(), at.plusSeconds(1));
        tamperedSecond.setDetail("edited after persistence");
        // Preserve the original stored hash: this models an unauthorized database edit.
        tamperedSecond.setHash(second.getHash());

        AuditEventRepository repository = mock(AuditEventRepository.class);
        when(repository.findAllByOrderBySeqAsc())
                .thenReturn(List.of(first, second, third))
                .thenReturn(List.of(first, tamperedSecond, third));
        AuditService service = new AuditService(repository, mock(AuditWriter.class), new ObjectMapper());

        Map<String, Object> clean = service.verifyChain();
        assertEquals(true, clean.get("valid"));
        assertEquals(3, clean.get("verifiedCount"));

        Map<String, Object> tampered = service.verifyChain();
        assertEquals(false, tampered.get("valid"));
        assertEquals(true, tampered.get("tamperSuspected"));
        assertEquals(1, tampered.get("contentBreaks"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> breaks = (List<Map<String, Object>>) tampered.get("breaks");
        assertEquals(2L, breaks.get(0).get("seq"));
        assertEquals("CONTENT_MISMATCH", breaks.get(0).get("reason"));
    }

    @Test
    void signedReanchorAcknowledgesHistoryButStillDetectsLaterHistoricalEdits() {
        Instant at = Instant.parse("2026-07-18T12:00:00Z");
        AuditEventEntity first = chainedEvent(1, "", at);
        AuditEventEntity historicalFork = chainedEvent(2, "missing-parent", at.plusSeconds(1));
        List<AuditEventEntity> history = List.of(first, historicalFork);

        AuditEventEntity anchor = event(3, "admin", "AUDIT_CHAIN_REANCHORED",
                "Approved checkpoint", at.plusSeconds(2));
        anchor.setPrevHash("");
        anchor.setMetadata("{\"historyDigest\":\"" + AuditService.historyDigest(history) + "\"}");
        anchor.setHash(AuditHash.compute("", anchor));
        AuditEventEntity active = chainedEvent(4, anchor.getHash(), at.plusSeconds(3));

        AuditEventEntity editedHistorical = chainedEvent(1, "", at);
        editedHistorical.setDetail("edited after checkpoint");
        editedHistorical.setHash(first.getHash());

        AuditEventRepository repository = mock(AuditEventRepository.class);
        when(repository.findAllByOrderBySeqAsc())
                .thenReturn(List.of(first, historicalFork, anchor, active))
                .thenReturn(List.of(editedHistorical, historicalFork, anchor, active));
        AuditService service = new AuditService(repository, mock(AuditWriter.class), new ObjectMapper());

        Map<String, Object> acknowledged = service.verifyChain();
        assertEquals(true, acknowledged.get("valid"));
        assertEquals(true, acknowledged.get("historicalIntegrityAcknowledged"));
        assertEquals(1, acknowledged.get("historicalBreaks"));
        assertEquals(0, acknowledged.get("activeContentBreaks"));

        Map<String, Object> edited = service.verifyChain();
        assertEquals(false, edited.get("valid"));
        assertEquals(true, edited.get("tamperSuspected"));
        assertEquals(1, edited.get("activeContentBreaks"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> breaks = (List<Map<String, Object>>) edited.get("breaks");
        assertTrue(breaks.stream().anyMatch(b -> "ANCHOR_HISTORY_DIGEST_MISMATCH".equals(b.get("reason"))));
    }

    private AuditEventEntity chainedEvent(long seq, String prevHash, Instant at) {
        AuditEventEntity event = event(seq, "admin", "AUDIT_TEST", "seq=" + seq, at);
        event.setPrevHash(prevHash);
        event.setHash(AuditHash.compute(prevHash, event));
        return event;
    }
}
