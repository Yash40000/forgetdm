package io.forgetdm.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.forgetdm.common.ApiException;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thin client for any OpenAI-compatible Chat Completions endpoint, with tool (function) calling.
 * Returns the raw assistant `message` node so the orchestrator can inspect tool_calls and content.
 */
@Component
public class LlmClient {

    public record ModelRuntime(String model, String configuredModel, boolean reachable,
                               boolean autoSelected, List<String> availableModels, String detail) {}

    private record CachedRuntime(ModelRuntime runtime, Instant expiresAt) {}

    private final AiProperties props;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();
    private final Map<String, CachedRuntime> modelCache = new ConcurrentHashMap<>();

    public LlmClient(AiProperties props) { this.props = props; }

    /**
     * @param provider resolved provider (base URL, key, model) to call
     * @param messages OpenAI-format messages (each an object with at least role; content/tool_calls/tool_call_id as needed)
     * @param tools    OpenAI-format tool specs (array of {type:function, function:{...}}); may be null/empty
     * @return the assistant message node (choices[0].message)
     */
    public JsonNode chat(AiProperties.Provider provider, String modelOverride, List<Object> messages, List<Object> tools) {
        return chat(provider, modelOverride, messages, tools, false);
    }

    /** @param jsonMode when true, ask the provider to return a JSON object (response_format) — used for planning. */
    public JsonNode chat(AiProperties.Provider provider, String modelOverride, List<Object> messages, List<Object> tools, boolean jsonMode) {
        return chat(provider, modelOverride, messages, tools, jsonMode, null);
    }

    /** Schema-constrained completion for local/private runtimes that support OpenAI JSON Schema output. */
    public JsonNode chat(AiProperties.Provider provider, String modelOverride, List<Object> messages,
                         List<Object> tools, JsonNode responseSchema) {
        return chat(provider, modelOverride, messages, tools, false, responseSchema);
    }

    private JsonNode chat(AiProperties.Provider provider, String modelOverride, List<Object> messages,
                          List<Object> tools, boolean jsonMode, JsonNode responseSchema) {
        if (provider == null || !provider.configured())
            throw ApiException.bad("Selected AI provider is not configured (set its API key / base URL / model).");

        ObjectNode body = json.createObjectNode();
        body.put("model", resolveModel(provider, modelOverride));
        body.put("temperature", props.getTemperature());
        int outputLimit = provider.isLocal()
                ? Math.min(props.getMaxOutputTokens(), props.getLocalMaxOutputTokens())
                : props.getMaxOutputTokens();
        body.put("max_tokens", Math.max(128, outputLimit));
        body.set("messages", json.valueToTree(messages));
        if (tools != null && !tools.isEmpty()) {
            body.set("tools", json.valueToTree(tools));
            body.put("tool_choice", "auto");
        }
        if (responseSchema != null && !responseSchema.isNull()) {
            ObjectNode rf = json.createObjectNode();
            rf.put("type", "json_schema");
            ObjectNode schema = rf.putObject("json_schema");
            schema.put("name", "forge_test_data_intent");
            schema.put("strict", true);
            schema.set("schema", responseSchema);
            body.set("response_format", rf);
        } else if (jsonMode) {
            ObjectNode rf = json.createObjectNode(); rf.put("type", "json_object");
            body.set("response_format", rf);
        }

        String url = trimSlash(provider.getBaseUrl()) + "/chat/completions";
        String payload;
        try { payload = json.writeValueAsString(body); }
        catch (Exception e) { throw ApiException.bad("AI request build failed: " + e.getMessage()); }

        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(provider.isLocal()
                        ? Math.max(5, Math.min(props.getTimeoutSeconds(), props.getLocalTimeoutSeconds()))
                        : props.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload));
        // Local endpoints (Ollama/vLLM without auth) send no key — only add the header when one is set.
        if (provider.getApiKey() != null && !provider.getApiKey().isBlank())
            rb.header("Authorization", "Bearer " + provider.getApiKey());
        HttpRequest req = rb.build();

        HttpResponse<String> resp;
        try { resp = http.send(req, HttpResponse.BodyHandlers.ofString()); }
        catch (Exception e) { throw ApiException.bad("AI provider call failed: " + e.getMessage()); }

        if (resp.statusCode() / 100 != 2)
            throw ApiException.bad("AI provider returned " + resp.statusCode() + ": " + snippet(resp.body()));

        try {
            JsonNode root = json.readTree(resp.body());
            JsonNode msg = root.path("choices").path(0).path("message");
            if (msg.isMissingNode())
                throw ApiException.bad("AI provider response had no message: " + snippet(resp.body()));
            return msg;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.bad("AI response parse failed: " + e.getMessage());
        }
    }

    /**
     * Resolve an installed model for a private OpenAI-compatible runtime. An explicit request model
     * always wins; otherwise a missing configured model falls back to an installed local model.
     */
    public String resolveModel(AiProperties.Provider provider, String modelOverride) {
        if (modelOverride != null && !modelOverride.isBlank()) return modelOverride.trim();
        ModelRuntime runtime = runtime(provider);
        return runtime.model() == null || runtime.model().isBlank() ? provider.getModel() : runtime.model();
    }

    /** Lightweight model inventory for private-runtime health screens. Results are cached briefly. */
    public ModelRuntime runtime(AiProperties.Provider provider) {
        String configured = provider == null ? null : provider.getModel();
        if (provider == null || !provider.isLocal())
            return new ModelRuntime(configured, configured, true, false,
                    configured == null ? List.of() : List.of(configured), "Configured remote model");

        String key = trimSlash(provider.getBaseUrl());
        CachedRuntime cached = modelCache.get(key);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) return cached.runtime();

        ModelRuntime resolved;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(key + "/models"))
                    .timeout(Duration.ofSeconds(4)).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                resolved = new ModelRuntime(configured, configured, false, false, List.of(),
                        "Private runtime returned HTTP " + response.statusCode());
            } else {
                JsonNode root = json.readTree(response.body());
                List<String> models = new ArrayList<>();
                JsonNode data = root.path("data");
                if (data.isArray()) for (JsonNode item : data) {
                    String id = item.path("id").asText("").trim();
                    if (!id.isBlank()) models.add(id);
                }
                String selected = models.stream().filter(name -> name.equalsIgnoreCase(configured)).findFirst()
                        .orElse(models.isEmpty() ? configured : models.get(0));
                boolean auto = selected != null && configured != null && !selected.equalsIgnoreCase(configured);
                resolved = new ModelRuntime(selected, configured, true, auto, List.copyOf(models),
                        models.isEmpty() ? "Runtime is reachable; no models were reported" :
                                auto ? "Configured model is unavailable; selected an installed private model" :
                                        "Configured private model is installed");
            }
        } catch (Exception e) {
            resolved = new ModelRuntime(configured, configured, false, false, List.of(),
                    "Private runtime is not reachable: " + e.getClass().getSimpleName());
        }
        modelCache.put(key, new CachedRuntime(resolved, Instant.now().plusSeconds(15)));
        return resolved;
    }

    private static String trimSlash(String s) { return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s; }
    private static String snippet(String s) { return s == null ? "" : (s.length() > 400 ? s.substring(0, 400) + "…" : s); }

    /** Convenience: an empty tools array node (rarely needed; chat() accepts a List). */
    public ArrayNode emptyTools() { return json.createArrayNode(); }
}
