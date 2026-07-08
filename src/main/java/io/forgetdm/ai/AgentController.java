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

    public AgentController(AgentService svc) { this.svc = svc; }

    @PostMapping("/plan")
    public Map<String, Object> plan(@RequestBody JsonNode body) {
        return svc.plan(body.path("goal").asText(null),
                body.path("provider").asText(null), body.path("model").asText(null));
    }

    @GetMapping("/runs")
    public List<Map<String, Object>> runs() { return svc.list(); }

    @GetMapping("/runs/{id}")
    public Map<String, Object> run(@PathVariable Long id) { return svc.get(id); }

    @PostMapping("/runs/{id}/next")
    public Map<String, Object> next(@PathVariable Long id) { return svc.runNext(id); }

    @PostMapping("/runs/{id}/approve")
    public Map<String, Object> approve(@PathVariable Long id) { return svc.approve(id); }

    @PostMapping("/runs/{id}/reject")
    public Map<String, Object> reject(@PathVariable Long id) { return svc.reject(id); }

    @PostMapping("/runs/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable Long id) { return svc.cancel(id); }
}
