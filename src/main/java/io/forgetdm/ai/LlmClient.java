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
import java.util.List;

/**
 * Thin client for any OpenAI-compatible Chat Completions endpoint, with tool (function) calling.
 * Returns the raw assistant `message` node so the orchestrator can inspect tool_calls and content.
 */
@Component
public class LlmClient {

    private final AiProperties props;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();

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
        if (provider == null || !provider.configured())
            throw ApiException.bad("Selected AI provider is not configured (set its API key / base URL / model).");

        ObjectNode body = json.createObjectNode();
        body.put("model", (modelOverride != null && !modelOverride.isBlank()) ? modelOverride.trim() : provider.getModel());
        body.put("temperature", props.getTemperature());
        body.set("messages", json.valueToTree(messages));
        if (tools != null && !tools.isEmpty()) {
            body.set("tools", json.valueToTree(tools));
            body.put("tool_choice", "auto");
        }
        if (jsonMode) {
            ObjectNode rf = json.createObjectNode(); rf.put("type", "json_object");
            body.set("response_format", rf);
        }

        String url = trimSlash(provider.getBaseUrl()) + "/chat/completions";
        String payload;
        try { payload = json.writeValueAsString(body); }
        catch (Exception e) { throw ApiException.bad("AI request build failed: " + e.getMessage()); }

        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
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

    private static String trimSlash(String s) { return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s; }
    private static String snippet(String s) { return s == null ? "" : (s.length() > 400 ? s.substring(0, 400) + "…" : s); }

    /** Convenience: an empty tools array node (rarely needed; chat() accepts a List). */
    public ArrayNode emptyTools() { return json.createArrayNode(); }
}
