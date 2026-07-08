package io.forgetdm.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Agentic provisioning: turn a natural-language goal into an ordered plan, then execute it one step at
 * a time through the tool-using Copilot. Read-only steps run automatically; any data-changing action
 * pauses for explicit human approval. State is held per run (in-memory) and every action is audited.
 *
 * Pattern: plan-and-execute with per-step grounding + approval gates — a standard, safe agent shape.
 */
@Service
public class AgentService {

    private final AiAssistantService assistant;
    private final AuditService audit;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicLong seq = new AtomicLong(1000);
    private final Map<Long, Run> runs = new ConcurrentHashMap<>();

    public AgentService(AiAssistantService assistant, AuditService audit) {
        this.assistant = assistant; this.audit = audit;
    }

    // ------------------------------------------------------------------ model
    static final class Step {
        long id; int ord;
        String title, detail;
        String status = "PENDING";          // PENDING | RUNNING | AWAITING_APPROVAL | DONE | SKIPPED
        String result;
        String actionName; JsonNode actionArgs; String actionSummary;
    }
    static final class Run {
        long id; String goal, status, summary, provider, model;
        final List<Step> steps = new ArrayList<>();
        final Instant createdAt = Instant.now();
    }

    // ------------------------------------------------------------------ planning
    public Map<String, Object> plan(String goal, String provider, String model) {
        if (goal == null || goal.isBlank()) throw ApiException.bad("Describe what you want to provision");
        if (!assistant.ready()) throw ApiException.bad("No AI provider is configured — set one up first (see AI_COPILOT_SETUP.txt).");

        // Local/CPU models are slow and do multiple calls (plan + one per step), so ask for fewer steps and skip
        // the plan retry — fewer LLM round-trips on an on-prem box.
        boolean lean = assistant.isLocalProvider(provider);
        String sys = "You are a Test Data Management provisioning planner inside ForgeTDM. Break the user's goal "
                + "into a SHORT ordered list of concrete steps ForgeTDM can perform: PII discovery, masking-policy "
                + "generation, DataScope/subset definition, provisioning jobs (mask copy / subset / in-place), synthetic "
                + "generation, and validation. Each step should be a single clear objective. Return STRICT JSON only, no "
                + "prose, no code fences: {\"summary\":\"one line\",\"steps\":[{\"title\":\"short\",\"detail\":\"what to do\"}]}. "
                + "Use " + (lean ? "2 to 4" : "3 to 7") + " steps.";
        Run run = new Run();
        run.id = seq.incrementAndGet();
        run.goal = goal; run.provider = provider; run.model = model;

        JsonNode node = tryPlan(sys, goal, provider, model, lean);   // null if the model couldn't produce JSON
        if (node != null && node.path("steps").isArray() && node.path("steps").size() > 0) {
            run.summary = node.path("summary").asText("");
            int i = 0;
            for (JsonNode s : node.path("steps")) {
                String title = s.isTextual() ? s.asText() : s.path("title").asText("Step " + (i + 1));
                Step st = new Step();
                st.id = run.id * 100 + (++i); st.ord = i;
                st.title = title;
                st.detail = s.path("detail").asText("");
                run.steps.add(st);
            }
        }
        if (run.steps.isEmpty()) {
            // Graceful degradation: run the whole goal as a single step. The tool-using Copilot still
            // executes it (with read tools + action approval), so the agent works even on weak models.
            Step st = new Step();
            st.id = run.id * 100 + 1; st.ord = 1; st.title = goal;
            st.detail = "Accomplish this goal using the available ForgeTDM tools.";
            run.steps.add(st);
            if (run.summary == null || run.summary.isBlank()) run.summary = "Single-step plan (the model didn't return a structured plan).";
        }
        run.status = "READY";
        runs.put(run.id, run);
        audit.log("system", "AGENT_PLANNED", "run=" + run.id + " steps=" + run.steps.size() + " goal=" + clip(goal, 120));
        return view(run);
    }

    /** Ask the model for a JSON plan (JSON mode), retry once more firmly, return null if it still fails.
     *  @param singleAttempt skip the retry (used for slow local models — falls back to a single-step plan). */
    private JsonNode tryPlan(String sys, String goal, String provider, String model, boolean singleAttempt) {
        try { return parseLoose(assistant.complete(sys, "Goal: " + goal, provider, model, true)); }
        catch (Exception e1) {
            if (singleAttempt) return null;
            try { return parseLoose(assistant.complete(sys + " Output ONLY the JSON object, nothing else.",
                    "Goal: " + goal, provider, model, true)); }
            catch (Exception e2) { return null; }
        }
    }

    // ------------------------------------------------------------------ execution
    public Map<String, Object> runNext(Long runId) {
        Run run = req(runId);
        if ("AWAITING_APPROVAL".equals(run.status)) throw ApiException.bad("A step is awaiting your approval.");
        if ("CANCELED".equals(run.status)) throw ApiException.bad("This run was canceled.");
        Step step = nextPending(run);
        if (step == null) { finish(run); return view(run); }

        step.status = "RUNNING"; run.status = "RUNNING";
        try {
            Map<String, Object> r = assistant.chat(instruction(run, step), run.provider, run.model);
            if ("action".equals(r.get("type"))) {
                step.actionName = str(r.get("name"));
                step.actionArgs = json.valueToTree(r.get("arguments"));
                step.actionSummary = str(r.get("summary"));
                step.status = "AWAITING_APPROVAL"; run.status = "AWAITING_APPROVAL";
                audit.log("system", "AGENT_ACTION_PROPOSED", "run=" + run.id + " step=" + step.ord + " action=" + step.actionName);
            } else {
                step.result = str(r.get("content"));
                step.status = "DONE";
                advance(run);
            }
        } catch (Exception e) {
            step.status = "FAILED"; step.result = "Failed: " + e.getMessage();
            run.status = "FAILED";
        }
        return view(run);
    }

    public Map<String, Object> approve(Long runId) {
        Run run = req(runId);
        Step step = run.steps.stream().filter(s -> "AWAITING_APPROVAL".equals(s.status)).findFirst()
                .orElseThrow(() -> ApiException.bad("No step is awaiting approval."));
        try {
            Map<String, Object> r = assistant.act(step.actionName, step.actionArgs, instruction(run, step), run.provider, run.model, false);
            String out = str(r.get("content"));
            step.result = (step.result == null ? "" : step.result + "\n") + (out.isEmpty() ? "Done." : out);
            step.status = "DONE";
            audit.log("system", "AGENT_ACTION_EXECUTED", "run=" + run.id + " step=" + step.ord + " action=" + step.actionName);
            advance(run);
        } catch (Exception e) {
            step.status = "FAILED"; step.result = "Action failed: " + e.getMessage(); run.status = "FAILED";
            audit.log("system", "AGENT_ACTION_FAILED", "run=" + run.id + " step=" + step.ord + ": " + e.getMessage());
        }
        return view(run);
    }

    public Map<String, Object> reject(Long runId) {
        Run run = req(runId);
        run.steps.stream().filter(s -> "AWAITING_APPROVAL".equals(s.status)).forEach(s -> {
            s.status = "SKIPPED"; s.result = "Skipped by user — action not run.";
        });
        advance(run);
        return view(run);
    }

    public Map<String, Object> cancel(Long runId) { Run run = req(runId); run.status = "CANCELED"; return view(run); }
    public Map<String, Object> get(Long id) { return view(req(id)); }
    public List<Map<String, Object>> list() {
        return runs.values().stream().sorted((a, b) -> Long.compare(b.id, a.id)).map(this::view).toList();
    }

    // ------------------------------------------------------------------ helpers
    private Step nextPending(Run run) {
        return run.steps.stream().filter(s -> "PENDING".equals(s.status)).findFirst().orElse(null);
    }
    private void advance(Run run) {
        boolean done = run.steps.stream().allMatch(s -> "DONE".equals(s.status) || "SKIPPED".equals(s.status));
        run.status = done ? "DONE" : "READY";
    }
    private void finish(Run run) { run.status = "DONE"; }

    /** Build the single-message instruction history for one step, grounded with the goal + prior results. */
    private JsonNode instruction(Run run, Step step) {
        String prior = run.steps.stream()
                .filter(s -> "DONE".equals(s.status) && s.result != null && !s.result.isBlank())
                .map(s -> "- " + s.title + ": " + clip(s.result, 300))
                .collect(Collectors.joining("\n"));
        StringBuilder sb = new StringBuilder();
        sb.append("You are executing ONE step of a provisioning plan. Use the available tools.\n")
          .append("Overall goal: ").append(run.goal).append("\n");
        if (!prior.isBlank()) sb.append("Already completed:\n").append(prior).append("\n");
        sb.append("\nDO THIS STEP NOW: ").append(step.title);
        if (step.detail != null && !step.detail.isBlank()) sb.append(" — ").append(step.detail);
        sb.append("\nIf this step changes data, call the appropriate action tool (the user will be asked to confirm). "
                + "Otherwise gather what's needed with read tools and reply concisely with the outcome. "
                + "Do only this step.");
        ArrayNode hist = json.createArrayNode();
        ObjectNode m = json.createObjectNode(); m.put("role", "user"); m.put("content", sb.toString());
        hist.add(m);
        return hist;
    }

    private Map<String, Object> view(Run run) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", run.id); m.put("goal", run.goal); m.put("status", run.status); m.put("summary", run.summary);
        List<Map<String, Object>> steps = new ArrayList<>();
        for (Step s : run.steps) {
            Map<String, Object> sm = new java.util.LinkedHashMap<>();
            sm.put("ord", s.ord); sm.put("title", s.title); sm.put("detail", s.detail);
            sm.put("status", s.status); sm.put("result", s.result);
            if ("AWAITING_APPROVAL".equals(s.status)) {
                sm.put("actionName", s.actionName);
                sm.put("actionArgs", s.actionArgs);
                sm.put("actionSummary", s.actionSummary);
            }
            steps.add(sm);
        }
        m.put("steps", steps);
        return m;
    }

    private Run req(Long id) {
        Run r = id == null ? null : runs.get(id);
        if (r == null) throw ApiException.notFound("Agent run " + id + " not found");
        return r;
    }

    /** Parse JSON that may be wrapped in code fences or have leading prose. */
    private JsonNode parseLoose(String s) {
        if (s == null) s = "";
        String t = s.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) t = t.substring(nl + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
            t = t.trim();
        }
        int a = t.indexOf('{'), b = t.lastIndexOf('}');
        int la = t.indexOf('['), lb = t.lastIndexOf(']');
        String cand;
        if (a >= 0 && b > a) cand = t.substring(a, b + 1);            // prefer a JSON object
        else if (la >= 0 && lb > la) cand = t.substring(la, lb + 1);  // else a bare array of steps
        else cand = t;
        try {
            JsonNode node = json.readTree(cand);
            if (node.isArray()) { ObjectNode o = json.createObjectNode(); o.set("steps", node); return o; }
            return node;
        } catch (Exception e) {
            throw ApiException.bad("planner returned non-JSON");
        }
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }
    private static String clip(String s, int n) { return s != null && s.length() > n ? s.substring(0, n) + "…" : s; }
}
