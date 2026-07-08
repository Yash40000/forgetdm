package io.forgetdm.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Orchestrates the TDM assistant: builds a grounded system prompt, runs an OpenAI-style tool loop
 * for read-only tools, and — for any data-changing action — STOPS and returns a pending action for
 * the user to confirm in the UI (executed later via {@link #act}). Real data values are never sent
 * to the model; only metadata, schema and the assistant's own tool results.
 */
@Service
public class AiAssistantService {

    private final AiProperties props;
    private final LlmClient llm;
    private final AiTools tools;
    private final AuditService audit;
    private final TdmKnowledgeService knowledge;
    private final ObjectMapper json = new ObjectMapper();

    public AiAssistantService(AiProperties props, LlmClient llm, AiTools tools, AuditService audit,
                              TdmKnowledgeService knowledge) {
        this.props = props; this.llm = llm; this.tools = tools; this.audit = audit; this.knowledge = knowledge;
    }

    public Map<String, Object> status() {
        Map<String, AiProperties.Provider> eff = props.effectiveProviders();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, AiProperties.Provider> e : eff.entrySet()) {
            if (!e.getValue().configured()) continue;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", e.getKey());
            p.put("label", e.getValue().getLabel());
            p.put("model", e.getValue().getModel());
            p.put("local", e.getValue().isLocal());   // on-prem endpoint — UI can badge "no data leaves your network"
            list.add(p);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", !list.isEmpty());
        out.put("providers", list);
        out.put("default", props.resolveDefaultId(eff));
        return out;
    }

    private AiProperties.Provider resolveProvider(String id) {
        Map<String, AiProperties.Provider> eff = props.effectiveProviders();
        if (id != null && !id.isBlank()) {
            AiProperties.Provider p = eff.get(id);
            if (p != null && p.configured()) return p;
        }
        String def = props.resolveDefaultId(eff);
        AiProperties.Provider p = def == null ? null : eff.get(def);
        return (p != null && p.configured()) ? p : null;
    }

    /** Plain LLM completion (no tools) — used by the provisioning agent to draft a plan. */
    public String complete(String system, String user, String providerId, String model) {
        return complete(system, user, providerId, model, false);
    }

    public String complete(String system, String user, String providerId, String model, boolean jsonMode) {
        AiProperties.Provider provider = resolveProvider(providerId);
        if (provider == null) throw ApiException.bad("No AI provider is configured. See AI_COPILOT_SETUP.txt.");
        List<Object> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", system));
        messages.add(Map.of("role", "user", "content", user));
        JsonNode msg = llm.chat(provider, model, messages, null, jsonMode);
        return msg.path("content").asText("");
    }

    /** Whether any AI provider is configured (the agent needs one). */
    public boolean ready() { return !props.effectiveProviders().isEmpty() && resolveProvider(null) != null; }

    /** True if the given (or default) provider is a local/on-prem endpoint — callers can run leaner on slow CPU models. */
    public boolean isLocalProvider(String providerId) {
        AiProperties.Provider p = resolveProvider(providerId);
        return p != null && p.isLocal();
    }

    // ------------------------------------------------------------------ chat

    public Map<String, Object> chat(JsonNode history, String providerId, String model) {
        AiProperties.Provider provider = resolveProvider(providerId);
        if (provider == null) return message(
                "No AI provider is reachable yet. For a fully on-prem setup, run a local model with Ollama "
                + "(`ollama serve` + `ollama pull llama3.1`) and pick **Local · Ollama** — nothing leaves your network. "
                + "Or configure OpenAI/Groq. See AI_COPILOT_SETUP.txt.");

        // Lean mode for local/CPU models: smaller prompt (trimmed grounding + a focused tool subset) and fewer
        // tool-loop hops, so a slow on-prem model responds in a fraction of the time.
        boolean lean = provider.isLocal() && props.isLeanLocalPrompt();
        List<Object> messages = baseMessages(history, lean);
        // TDM Advisor (RAG): retrieve curated playbook guidance for the user's scenario and inject it right after
        // the system prompt, so the assistant advises from vetted best practice, not just general knowledge.
        Map<String, Object> advisor = advisorSystemMessage(history, lean);
        if (advisor != null) messages.add(1, advisor);
        List<Object> toolSpecs = tools.specs(lean);
        int maxHops = lean ? Math.min(2, props.getMaxToolHops()) : props.getMaxToolHops();

        for (int hop = 0; hop < maxHops; hop++) {
            JsonNode msg = llm.chat(provider, model, messages, toolSpecs);
            JsonNode calls = msg.path("tool_calls");

            if (!calls.isArray() || calls.isEmpty())
                return message(msg.path("content").asText(""));

            // If the model wants any data-changing action, stop and ask the user to confirm.
            for (JsonNode call : calls) {
                String name = call.path("function").path("name").asText("");
                if (tools.requiresConfirmation(name)) {
                    JsonNode args = parseArgs(call);
                    String summary = msg.path("content").asText("");
                    if (summary.isBlank()) summary = "I'd like to run **" + name + "**. Confirm to proceed.";
                    audit.log("system", "AI_ACTION_PROPOSED", name + " " + args);
                    return pending(name, args, summary);
                }
            }

            // All read-only: echo the assistant tool-call message, then append each tool result.
            messages.add(json.convertValue(msg, Map.class));
            for (JsonNode call : calls) {
                String id = call.path("id").asText("");
                String name = call.path("function").path("name").asText("");
                String result;
                try { result = tools.execute(name, parseArgs(call)); }
                catch (Exception e) { result = "{\"error\":" + str(e.getMessage()) + "}"; }
                Map<String, Object> toolMsg = new LinkedHashMap<>();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", id);
                toolMsg.put("content", clip(result, 3000));
                messages.add(toolMsg);
            }
        }
        return message("I gathered some information but reached the tool-call limit before finishing. "
                + "Could you narrow the request a little?");
    }

    // ------------------------------------------------------------------ confirmed action

    public Map<String, Object> act(String name, JsonNode args, JsonNode history, String providerId, String model) {
        return act(name, args, history, providerId, model, true);
    }

    /** @param summarize when false, return the raw tool result without a second LLM call (faster — used by the agent). */
    public Map<String, Object> act(String name, JsonNode args, JsonNode history, String providerId, String model, boolean summarize) {
        if (!tools.exists(name) || !tools.requiresConfirmation(name))
            return message("That action isn't available.");
        String result;
        try {
            result = tools.execute(name, args);
            audit.log("system", "AI_ACTION_EXECUTED", name + " " + args);
        } catch (Exception e) {
            audit.log("system", "AI_ACTION_FAILED", name + ": " + e.getMessage());
            return message("The action **" + name + "** failed: " + e.getMessage());
        }
        AiProperties.Provider provider = resolveProvider(providerId);
        if (!summarize || provider == null)
            return message("Done. Result: " + clip(result, 1500));

        // Ask the model to summarize the outcome in plain language.
        List<Object> messages = baseMessages(history, provider.isLocal() && props.isLeanLocalPrompt());
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("role", "user");
        note.put("content", "The action '" + name + "' was just executed on the user's behalf. Raw result:\n"
                + clip(result, 4000) + "\n\nConfirm to the user in one short paragraph what happened and any next step.");
        messages.add(note);
        JsonNode msg = llm.chat(provider, model, messages, null);
        return message(msg.path("content").asText("Done."));
    }

    // ------------------------------------------------------------------ prompt + helpers

    /** Retrieve the most relevant TDM playbook entries for the latest user turn, as a system message (or null). */
    private Map<String, Object> advisorSystemMessage(JsonNode history, boolean lean) {
        String q = latestUserText(history);
        if (q == null || q.isBlank() || knowledge == null) return null;
        List<TdmKnowledgeService.Entry> hits = knowledge.search(q, lean ? 2 : 3);
        if (hits.isEmpty()) return null;
        int cap = lean ? 450 : 750;
        StringBuilder sb = new StringBuilder("RELEVANT TDM PLAYBOOK (authoritative ForgeTDM best-practice — base your "
                + "advice on this and name the specific ForgeTDM feature/tool to use):\n");
        for (TdmKnowledgeService.Entry e : hits)
            sb.append("• ").append(e.title()).append(" — ").append(clip(e.body(), cap)).append("\n");
        return Map.of("role", "system", "content", sb.toString());
    }

    private static String latestUserText(JsonNode history) {
        if (history == null || !history.isArray()) return null;
        String last = null;
        for (JsonNode m : history) if ("user".equals(m.path("role").asText(""))) last = m.path("content").asText("");
        return last;
    }

    private List<Object> baseMessages(JsonNode history, boolean lean) {
        List<Object> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt(lean)));
        if (history != null && history.isArray()) {
            for (JsonNode m : history) {
                String role = m.path("role").asText("user");
                if (!role.equals("user") && !role.equals("assistant")) role = "user";
                messages.add(Map.of("role", role, "content", m.path("content").asText("")));
            }
        }
        return messages;
    }

    private String systemPrompt(boolean lean) {
        if (lean) return leanSystemPrompt();
        StringBuilder sb = new StringBuilder();
        sb.append("You are ForgeTDM Copilot, an assistant inside ForgeTDM, an enterprise Test Data Management platform. ")
          .append("ForgeTDM covers the TDM lifecycle: PII discovery & classification, deterministic masking, subsetting with referential integrity, ")
          .append("synthetic data generation, data virtualization/snapshots, mainframe copybook masking, validation, and provisioning jobs.\n\n")
          .append("For greetings, small talk, or general questions you can answer from your own knowledge, REPLY DIRECTLY and do NOT call any tool. ")
          .append("Only call a tool when you genuinely need live data from this ForgeTDM instance (e.g. the user asks what data sources exist). ")
          .append("HOW YOU HELP: answer TDM questions, explain features, and help users run real work by calling the provided tools. ")
          .append("Read-only tools run automatically. Any data-changing action (run_discovery, generate_policy, submit_job, generate_synthetic, run_datascope_job) ")
          .append("will be shown to the user for explicit confirmation before it runs — so when a task needs one, call the action tool with complete arguments and clearly explain what it will do.\n\n")
          .append("GUARDRAILS: never ask for or output real sensitive values (PII). Work only with metadata, schema, IDs and tool results. ")
          .append("Prefer calling read tools (e.g. list_data_sources, list_columns, get_discovery_results, get_pii_coverage) to discover real IDs before proposing an action — never invent IDs. ")
          .append("Be concise and concrete. When proposing a masking policy or job, briefly justify your choices. ")
          .append("For 'what should I do for X' scenario questions, follow the RELEVANT TDM PLAYBOOK guidance (provided as a system note, or call search_tdm_knowledge) and name the specific ForgeTDM feature to use.\n\n");
        // RAG grounding: inject a compact, live snapshot of THIS instance's catalog (metadata only — no row data),
        // so the model answers from current state and uses real ids/names without a tool round-trip for the basics.
        sb.append("CURRENT FORGETDM STATE (live snapshot — metadata only, IDs are real, use them; call tools for detail):\n");
        sb.append("• Data sources: ").append(safeTool("list_data_sources", 1400)).append("\n");
        sb.append("• Masking policies: ").append(safeTool("list_policies", 700)).append("\n");
        sb.append("• DataScope access definitions: ").append(safeTool("list_datasets", 700)).append("\n");
        sb.append("• Masking functions available: ").append(safeTool("list_mask_functions", 700)).append("\n");
        sb.append("• Reference value lists: ").append(safeTool("list_value_lists", 500)).append("\n");
        sb.append("• Saved DataScope jobs: ").append(safeTool("list_datascope_jobs", 700)).append("\n");
        sb.append("\nIf the snapshot above is empty for something the user asks about, say so or call the matching tool.\n");
        return sb.toString();
    }

    /** Compact system prompt for slow local models: keeps the essential guardrails, trims the grounding to the
     *  smallest useful snapshot. Sent on every hop, so every character saved speeds a CPU model up. */
    private String leanSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("You are ForgeTDM Copilot inside ForgeTDM (Test Data Management: PII discovery, masking, subsetting, ")
          .append("synthetic data, provisioning). Answer TDM questions directly; call a tool only when you need live data. ")
          .append("Read tools run automatically; data-changing actions (run_discovery, generate_policy, submit_job) are ")
          .append("confirmed by the user first. Never output real PII — use only metadata/IDs from tools; never invent IDs. Be brief. ")
          .append("For scenario 'what should I do' questions, follow any RELEVANT TDM PLAYBOOK note (or call search_tdm_knowledge) and name the ForgeTDM feature.\n\n");
        sb.append("Data sources: ").append(safeTool("list_data_sources", 500)).append("\n");
        sb.append("Policies: ").append(safeTool("list_policies", 300)).append("\n");
        return sb.toString();
    }

    private String safeTool(String name, int cap) {
        try { return clip(tools.execute(name, json.createObjectNode()), cap); }
        catch (Exception e) { return "(unavailable)"; }
    }

    private Map<String, Object> message(String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "message");
        m.put("content", content == null ? "" : content);
        return m;
    }

    private Map<String, Object> pending(String name, JsonNode args, String summary) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "action");
        m.put("name", name);
        m.put("arguments", args);
        m.put("summary", summary);
        return m;
    }

    private JsonNode parseArgs(JsonNode call) {
        String raw = call.path("function").path("arguments").asText("");
        if (raw == null || raw.isBlank()) return json.createObjectNode();
        try { return json.readTree(raw); } catch (Exception e) { return json.createObjectNode(); }
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…(truncated)" : s;
    }
    private String str(String s) { try { return json.writeValueAsString(s == null ? "" : s); } catch (Exception e) { return "\"\""; } }
}
