package io.forgetdm.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.forgetdm.common.ApiException;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * The tools the assistant can call. Each maps to one of ForgeTDM's own REST endpoints (self-HTTP),
 * which keeps the AI layer decoupled from internal service signatures and reuses the exact contracts
 * the UI uses. Read tools run inline during the chat loop; action tools (requiresConfirmation) are
 * never executed by the model directly — the user confirms them in the UI, which calls /api/ai/act.
 */
@Component
public class AiTools {

    private final AiProperties props;
    private final TdmKnowledgeService knowledge;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public AiTools(AiProperties props, TdmKnowledgeService knowledge) {
        this.props = props;
        this.knowledge = knowledge;
    }

    private static final Set<String> ACTIONS = Set.of(
            "run_discovery", "generate_policy", "submit_job", "generate_synthetic", "run_datascope_job");

    public boolean requiresConfirmation(String name) { return ACTIONS.contains(name); }

    public boolean exists(String name) { return TOOL_NAMES.contains(name); }

    private static final Set<String> TOOL_NAMES = Set.of(
            "list_data_sources", "list_policies", "get_policy_rules", "list_mask_functions",
            "list_datasets", "list_jobs", "get_job", "list_schemas", "list_tables", "list_columns",
            "get_discovery_results", "get_pii_coverage", "list_value_lists", "list_mask_scripts",
            "list_datascope_jobs", "list_workflows", "preview_mask", "search_tdm_knowledge",
            "run_discovery", "generate_policy", "submit_job", "generate_synthetic", "run_datascope_job");

    // ------------------------------------------------------------------ tool specs (for the LLM)

    /** Core tools kept in lean mode (local/CPU models): the everyday discovery→mask→provision path. Sending
     *  fewer specs shrinks the prompt a slow model must read on every hop. */
    private static final Set<String> LEAN_TOOLS = Set.of(
            "search_tdm_knowledge", "list_data_sources", "list_schemas", "list_tables", "list_columns",
            "list_policies", "get_policy_rules", "get_discovery_results", "get_pii_coverage",
            "preview_mask", "run_discovery", "generate_policy", "submit_job");

    public List<Object> specs() { return specs(false); }

    /** @param lean when true, return only the core {@link #LEAN_TOOLS} subset. */
    public List<Object> specs(boolean lean) {
        List<Object> all = allSpecs();
        if (!lean) return all;
        List<Object> out = new ArrayList<>();
        for (Object spec : all) if (LEAN_TOOLS.contains(specName(spec))) out.add(spec);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static String specName(Object toolSpec) {
        Object fn = ((Map<String, Object>) toolSpec).get("function");
        return fn == null ? "" : String.valueOf(((Map<String, Object>) fn).get("name"));
    }

    private List<Object> allSpecs() {
        List<Object> t = new ArrayList<>();
        t.add(fn("list_data_sources", "List all registered data sources with their id, name and kind.", obj()));
        t.add(fn("list_policies", "List all masking policies (id, name).", obj()));
        t.add(fn("get_policy_rules", "List the masking rules (column, function, params) of a policy.",
                obj(p("policyId", "integer", "The masking policy id"), req("policyId"))));
        t.add(fn("list_mask_functions", "List the available masking function names.", obj()));
        t.add(fn("list_datasets", "List DataScope access definitions (id, name).", obj()));
        t.add(fn("list_jobs", "List provisioning jobs with status and rows processed.", obj()));
        t.add(fn("get_job", "Get a single provisioning job by id (status, message, rows).",
                obj(p("jobId", "integer", "The provisioning job id"), req("jobId"))));
        t.add(fn("list_schemas", "List schemas in a data source.",
                obj(p("dataSourceId", "integer", "The data source id"), req("dataSourceId"))));
        t.add(fn("list_tables", "List tables in a data source schema.",
                obj(p("dataSourceId", "integer", "The data source id"),
                    p("schema", "string", "Schema name (optional)"), req("dataSourceId"))));
        t.add(fn("list_columns", "List columns (name, type) of a table.",
                obj(p("dataSourceId", "integer", "The data source id"),
                    p("table", "string", "Table name"),
                    p("schema", "string", "Schema name (optional)"), req("dataSourceId", "table"))));
        t.add(fn("get_discovery_results", "Get PII discovery results (classified columns) for a data source.",
                obj(p("dataSourceId", "integer", "The data source id"), req("dataSourceId"))));
        t.add(fn("get_pii_coverage", "For a DataScope access definition, list approved PII columns in scope that have NO masking (gaps that would be copied in clear).",
                obj(p("datasetId", "integer", "The DataScope access definition id"),
                    p("policyId", "integer", "Optional default masking policy id to evaluate against"), req("datasetId"))));
        t.add(fn("list_value_lists", "List reference value lists (K2View/GenRocket-style domains) available for masking and generation.", obj()));
        t.add(fn("list_mask_scripts", "List saved user-defined Lua masking scripts (name, description).", obj()));
        t.add(fn("list_datascope_jobs", "List saved DataScope provisioning jobs (reusable/schedulable) with schedule and last-run status.", obj()));
        t.add(fn("list_workflows", "List mapping workflows (ordered pipelines of mapping/SQL steps).", obj()));
        t.add(fn("preview_mask", "Preview what a masking function does to a sample value (safe, no data changed). Returns original→masked.",
                obj(p("function", "string", "Masking function name, e.g. EMAIL, FULL_NAME, SSN, FORMAT_PRESERVE"),
                    p("value", "string", "A sample value to mask (use a fake example, never real PII)"),
                    p("param1", "string", "Optional function param 1"),
                    p("param2", "string", "Optional function param 2"), req("function", "value"))));
        t.add(fn("search_tdm_knowledge", "Search the ForgeTDM TDM best-practice playbook for how to approach a scenario "
                        + "(masking, subsetting, synthetic, PII discovery, compliance, referential integrity, CI/CD, virtualization, "
                        + "mainframe, governance, scale). Returns recommended approaches and which ForgeTDM feature to use. Consult this "
                        + "when advising on WHAT to do for a scenario.",
                obj(p("query", "string", "The user's scenario or question in their own words"), req("query"))));

        // ---- actions (require user confirmation in the UI) ----
        t.add(fn("run_discovery", "ACTION: scan a data source for sensitive data. Requires user confirmation.",
                obj(p("dataSourceId", "integer", "The data source id to scan"), req("dataSourceId"))));
        t.add(fn("generate_policy", "ACTION: generate a masking policy from approved discovery results. Requires confirmation.",
                obj(p("dataSourceId", "integer", "The data source id"), req("dataSourceId"))));
        t.add(fn("submit_job", "ACTION: submit a provisioning job (MASK_COPY, SUBSET_MASK or SYNTHETIC_LOAD). Requires confirmation. "
                        + "Provide spec as a JSON object (e.g. loadAction, tables, sourceSchema/targetSchema, driverTable/filter).",
                obj(p("name", "string", "A human-readable job name"),
                    p("jobType", "string", "MASK_COPY | SUBSET_MASK | SYNTHETIC_LOAD"),
                    p("sourceId", "integer", "Source data source id (optional for synthetic)"),
                    p("targetId", "integer", "Target data source id"),
                    p("policyId", "integer", "Masking policy id (optional)"),
                    p("datasetId", "integer", "DataScope access definition id (optional)"),
                    objProp("spec", "Job spec as a JSON object"),
                    req("name", "jobType"))));
        t.add(fn("generate_synthetic", "ACTION: generate synthetic data from a plan (receiver DB/CSV/JSON/SQL). Requires confirmation.",
                obj(objProp("plan", "The full synthetic generation plan object"), req("plan"))));
        t.add(fn("run_datascope_job", "ACTION: run a saved DataScope provisioning job by its id. Goes through the approval gate. Requires confirmation.",
                obj(p("savedJobId", "string", "The saved DataScope job id (from list_datascope_jobs)"), req("savedJobId"))));
        return t;
    }

    // ------------------------------------------------------------------ execution

    /** Execute a tool by name with the given arguments; returns the raw response body as a string. */
    public String execute(String name, JsonNode a) {
        if (a == null || a.isNull()) a = json.createObjectNode();
        switch (name) {
            case "list_data_sources":     return get("/api/datasources");
            case "list_policies":         return get("/api/policies");
            case "get_policy_rules":      return get("/api/policies/" + reqLong(a, "policyId") + "/rules");
            case "list_mask_functions":   return get("/api/policies/functions");
            case "list_datasets":         return get("/api/datasets");
            case "list_jobs":             return get("/api/jobs");
            case "get_job":               return get("/api/jobs/" + reqLong(a, "jobId"));
            case "list_schemas":          return get("/api/datasources/" + reqLong(a, "dataSourceId") + "/schemas");
            case "list_tables":           return get("/api/datasources/" + reqLong(a, "dataSourceId") + "/tables" + qs("schema", a.path("schema").asText(null)));
            case "list_columns":          return get("/api/datasources/" + reqLong(a, "dataSourceId") + "/tables/"
                                                  + enc(a.path("table").asText("")) + "/columns" + qs("schema", a.path("schema").asText(null)));
            case "get_discovery_results": return get("/api/discovery/results/" + reqLong(a, "dataSourceId"));
            case "get_pii_coverage":      return get("/api/datasets/" + reqLong(a, "datasetId") + "/pii-coverage"
                                                  + qs("policyId", a.path("policyId").isNumber() ? a.get("policyId").asText() : null));
            case "list_value_lists":      return get("/api/synthetic/value-lists");
            case "list_mask_scripts":     return get("/api/policies/scripts");
            case "list_datascope_jobs":   return get("/api/datascope/saved-jobs");
            case "list_workflows":        return get("/api/mappings/workflows");
            case "preview_mask":          return post("/api/policies/preview", previewBody(a));
            case "search_tdm_knowledge":  return knowledgeJson(a.path("query").asText(""));

            case "run_discovery":         return post("/api/discovery/scan/" + reqLong(a, "dataSourceId"), null);
            case "generate_policy":       return post("/api/discovery/generate-policy/" + reqLong(a, "dataSourceId"), null);
            case "submit_job":            return post("/api/jobs", jobBody(a));
            case "generate_synthetic":    return post("/api/synthetic/generate", a.has("plan") ? a.get("plan") : a);
            case "run_datascope_job":     return post("/api/datascope/saved-jobs/" + enc(reqStr(a, "savedJobId")) + "/run", null);

            default: throw ApiException.bad("Unknown tool: " + name);
        }
    }

    /** Build the ProvisionJobEntity body, normalizing a `spec` object into the `specJson` text column. */
    private JsonNode jobBody(JsonNode a) {
        ObjectNode body = json.createObjectNode();
        if (a.hasNonNull("name")) body.put("name", a.get("name").asText());
        if (a.hasNonNull("jobType")) body.put("jobType", a.get("jobType").asText());
        if (a.hasNonNull("sourceId")) body.put("sourceId", a.get("sourceId").asLong());
        if (a.hasNonNull("targetId")) body.put("targetId", a.get("targetId").asLong());
        if (a.hasNonNull("policyId")) body.put("policyId", a.get("policyId").asLong());
        if (a.hasNonNull("datasetId")) body.put("datasetId", a.get("datasetId").asLong());
        JsonNode spec = a.get("spec");
        if (spec != null && !spec.isNull()) {
            try { body.put("specJson", spec.isTextual() ? spec.asText() : json.writeValueAsString(spec)); }
            catch (Exception e) { throw ApiException.bad("Invalid job spec: " + e.getMessage()); }
        } else if (a.hasNonNull("specJson")) {
            body.put("specJson", a.get("specJson").asText());
        }
        return body;
    }

    /** Retrieve the most relevant TDM playbook entries (no HTTP — the knowledge base lives in-process). */
    private String knowledgeJson(String query) {
        try {
            List<TdmKnowledgeService.Entry> hits = knowledge.search(query, 3);
            ObjectNode root = json.createObjectNode();
            var arr = root.putArray("entries");
            for (TdmKnowledgeService.Entry e : hits) {
                ObjectNode o = arr.addObject();
                o.put("title", e.title());
                o.put("guidance", e.body());
            }
            if (hits.isEmpty()) root.put("note", "No specific playbook entry matched; answer from general TDM knowledge and the tools.");
            return json.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"entries\":[]}";
        }
    }

    /** Masking Studio preview body: {function, value, param1?, param2?}. */
    private JsonNode previewBody(JsonNode a) {
        ObjectNode body = json.createObjectNode();
        body.put("function", a.path("function").asText(""));
        body.put("value", a.path("value").asText(""));
        if (a.hasNonNull("param1")) body.put("param1", a.get("param1").asText());
        if (a.hasNonNull("param2")) body.put("param2", a.get("param2").asText());
        return body;
    }

    // ------------------------------------------------------------------ self-HTTP

    private String get(String path) {
        return send(HttpRequest.newBuilder(uri(path)).GET());
    }

    private String post(String path, JsonNode body) {
        HttpRequest.BodyPublisher pub;
        try {
            pub = body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
        } catch (Exception e) { throw ApiException.bad("Tool body build failed: " + e.getMessage()); }
        return send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json").POST(pub));
    }

    private String send(HttpRequest.Builder b) {
        // Authenticate the self-call as the current user so it passes the security filter with the caller's own
        // permissions (a tool can never do more than the user could in the UI).
        io.forgetdm.security.AccessContext.currentToken()
                .ifPresent(tok -> b.header("Authorization", "Bearer " + tok));
        HttpResponse<String> resp;
        try { resp = http.send(b.timeout(Duration.ofSeconds(60)).build(), HttpResponse.BodyHandlers.ofString()); }
        catch (Exception e) { throw ApiException.bad("Tool call failed: " + e.getMessage()); }
        String body = resp.body() == null ? "" : resp.body();
        if (resp.statusCode() / 100 != 2)
            return "{\"error\":\"HTTP " + resp.statusCode() + "\",\"detail\":" + jsonStr(trim(body)) + "}";
        return body.isBlank() ? "{\"ok\":true}" : body;
    }

    private URI uri(String path) { return URI.create(trimSlash(props.getSelfBaseUrl()) + path); }

    // ------------------------------------------------------------------ spec builders / helpers

    private Map<String, Object> fn(String name, String desc, Map<String, Object> params) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name);
        function.put("description", desc);
        function.put("parameters", params);
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", function);
        return tool;
    }

    /** Build a JSON-schema object from alternating property maps and an optional required marker. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> obj(Object... parts) {
        Map<String, Object> props = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Object part : parts) {
            if (part instanceof Map<?, ?> m) props.putAll((Map<String, Object>) m);
            else if (part instanceof String[] r) required.addAll(Arrays.asList(r));
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        if (!required.isEmpty()) schema.put("required", required);
        return schema;
    }

    private Map<String, Object> p(String name, String type, String desc) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("type", type);
        spec.put("description", desc);
        Map<String, Object> wrap = new LinkedHashMap<>();
        wrap.put(name, spec);
        return wrap;
    }

    private Map<String, Object> objProp(String name, String desc) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("type", "object");
        spec.put("description", desc);
        Map<String, Object> wrap = new LinkedHashMap<>();
        wrap.put(name, spec);
        return wrap;
    }

    private String[] req(String... names) { return names; }

    private long reqLong(JsonNode a, String field) {
        if (a == null || !a.hasNonNull(field)) throw ApiException.bad("Tool argument '" + field + "' is required");
        return a.get(field).asLong();
    }

    private String reqStr(JsonNode a, String field) {
        if (a == null || !a.hasNonNull(field) || a.get(field).asText().isBlank())
            throw ApiException.bad("Tool argument '" + field + "' is required");
        return a.get(field).asText();
    }

    private static String qs(String key, String val) {
        return (val == null || val.isBlank()) ? "" : "?" + key + "=" + enc(val);
    }
    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    private static String trimSlash(String s) { return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s; }
    private static String trim(String s) { return s.length() > 300 ? s.substring(0, 300) + "…" : s; }
    private String jsonStr(String s) { try { return json.writeValueAsString(s); } catch (Exception e) { return "\"\""; } }
}
