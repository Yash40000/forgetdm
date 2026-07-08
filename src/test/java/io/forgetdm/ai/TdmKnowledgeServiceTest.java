package io.forgetdm.ai;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The TDM Advisor retriever must load the playbook and surface the right scenario for real questions. */
class TdmKnowledgeServiceTest {

    static TdmKnowledgeService svc;

    @BeforeAll
    static void setup() {
        svc = new TdmKnowledgeService();
        svc.load();   // package-private @PostConstruct — call it directly (no Spring in this unit test)
    }

    @Test void loadsPlaybook() {
        assertTrue(svc.size() >= 15, "expected the playbook to load; got " + svc.size() + " entries");
    }

    @Test void topHitForUniqueCollision() {
        List<TdmKnowledgeService.Entry> hits =
                svc.search("my masked email keeps failing with a unique constraint duplicate key error", 3);
        assertFalse(hits.isEmpty());
        assertTrue(hits.get(0).title().toLowerCase().contains("unique column"),
                "expected the collision entry first, got: " + hits.get(0).title());
    }

    @Test void findsSubsetting() {
        var hits = svc.search("take a small referentially complete slice of a huge production database for QA", 3);
        assertTrue(hits.stream().anyMatch(h -> h.title().toLowerCase().contains("subset")), titles(hits));
    }

    @Test void findsCompliance() {
        var hits = svc.search("we must make non production data GDPR and HIPAA compliant", 2);
        assertTrue(hits.stream().anyMatch(h -> h.title().toLowerCase().contains("gdpr")), titles(hits));
    }

    @Test void findsCicd() {
        var hits = svc.search("provision fresh test data automatically from our Jenkins CI/CD pipeline each build", 3);
        assertTrue(hits.stream().anyMatch(h -> h.title().toLowerCase().contains("ci/cd")), titles(hits));
    }

    @Test void findsSyntheticVsMasking() {
        var hits = svc.search("should I generate synthetic data or mask a copy of production", 3);
        assertTrue(hits.stream().anyMatch(h -> h.title().toLowerCase().contains("synthetic")), titles(hits));
    }

    @Test void emptyWhenNothingRelevant() {
        assertTrue(svc.search("zzzz qqqq wobble", 3).isEmpty());
    }

    private static String titles(List<TdmKnowledgeService.Entry> hits) {
        return "got: " + hits.stream().map(TdmKnowledgeService.Entry::title).toList();
    }
}
