package io.forgetdm.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Agentic provisioning API.
 *   POST /api/agent/plan            { goal, provider?, model? } → run with an ordered plan
 *   GET  /api/agent/runs                                        → recent runs
 *   GET  /api/agent/runs/{id}                                   → one run
 *   POST /api/agent/runs/{id}/next                              → execute the next step (pauses at actions)
 *   POST /api/agent/runs/{id}/approve | /reject | /cancel       → control gated actions
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentService svc;
    private final ForgeIntelligenceStoreService store;

    public AgentController(AgentService svc, ForgeIntelligenceStoreService store) {
        this.svc = svc;
        this.store = store;
    }

    @PostMapping("/plan")
    public Map<String, Object> plan(@RequestBody JsonNode body) {
        return svc.plan(body.path("goal").asText(null),
                body.path("provider").asText(null), body.path("model").asText(null),
                body.path("refreshDataStore").asBoolean(false));
    }

    @GetMapping("/runs")
    public List<Map<String, Object>> runs() { return svc.list(); }

    @GetMapping("/runs/{id}")
    public Map<String, Object> run(@PathVariable Long id) { return svc.get(id); }

    @PostMapping("/runs/{id}/next")
    public Map<String, Object> next(@PathVariable Long id) { return svc.runNext(id); }

    @PostMapping("/runs/{id}/run")
    public Map<String, Object> runUntilGate(@PathVariable Long id) { return svc.runUntilGate(id); }

    @PostMapping("/runs/{id}/approve-plan")
    public Map<String, Object> approvePlan(@PathVariable Long id) { return svc.approvePlan(id); }

    @PostMapping("/runs/{id}/approve")
    public Map<String, Object> approve(@PathVariable Long id) { return svc.approve(id); }

    @PostMapping("/runs/{id}/reject")
    public Map<String, Object> reject(@PathVariable Long id) { return svc.reject(id); }

    @PostMapping("/runs/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable Long id) { return svc.cancel(id); }

    @PostMapping("/runs/{id}/revise")
    public Map<String, Object> revise(@PathVariable Long id, @RequestBody JsonNode body) {
        return svc.revise(id, body.path("answers"), body.path("provider").asText(null), body.path("model").asText(null));
    }

    @PostMapping("/runs/{id}/feedback")
    public Map<String, Object> feedback(@PathVariable Long id, @RequestBody JsonNode body) {
        Integer rating = body.path("rating").isInt() ? body.path("rating").asInt() : null;
        Boolean accepted = body.path("accepted").isBoolean() ? body.path("accepted").asBoolean() : null;
        return svc.feedback(id, rating, accepted, body.get("correction"), body.path("comment").asText(null));
    }

    @GetMapping("/runs/{id}/events")
    public List<Map<String, Object>> events(@PathVariable Long id) { return svc.events(id); }

    @GetMapping("/data-store/status")
    public Map<String, Object> dataStoreStatus() { return store.status(); }

    @PostMapping("/data-store/sync")
    public Map<String, Object> syncDataStore() { return store.sync(); }

    @GetMapping("/data-store/documents")
    public List<ForgeIntelligenceStoreService.SearchHit> documents(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "50") int limit) {
        return store.documents(q, type, limit);
    }

    @PostMapping("/data-store/documents")
    public ForgeIntelligenceStoreService.SearchHit addDocument(@RequestBody JsonNode body) {
        return store.addManualDocument(body.path("type").asText(null), body.path("title").asText(null),
                body.path("content").asText(null), body.get("metadata"));
    }

    @DeleteMapping("/data-store/documents/{id}")
    public Map<String, Object> deleteDocument(@PathVariable long id) {
        store.deleteManualDocument(id);
        return Map.of("ok", true);
    }
}
