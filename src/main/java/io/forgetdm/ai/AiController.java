package io.forgetdm.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST surface for the ForgeTDM AI assistant.
 *   GET  /api/ai/status  → whether the assistant is configured + model name
 *   POST /api/ai/chat    → { messages:[{role,content}] } → { type:"message", content } or { type:"action", name, arguments, summary }
 *   POST /api/ai/act     → { name, arguments, messages } → executes a user-confirmed action, returns { type:"message", content }
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiAssistantService svc;

    public AiController(AiAssistantService svc) { this.svc = svc; }

    @GetMapping("/status")
    public Map<String, Object> status() { return svc.status(); }

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody JsonNode body) {
        return svc.chat(body.path("messages"), body.path("provider").asText(null), body.path("model").asText(null));
    }

    @PostMapping("/act")
    public Map<String, Object> act(@RequestBody JsonNode body) {
        return svc.act(body.path("name").asText(""), body.path("arguments"),
                body.path("messages"), body.path("provider").asText(null), body.path("model").asText(null));
    }
}
