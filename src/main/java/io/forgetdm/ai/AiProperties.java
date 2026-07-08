package io.forgetdm.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration for the ForgeTDM AI assistant. Supports multiple selectable providers, each an
 * OpenAI-compatible /chat/completions endpoint (OpenAI, Groq, Azure OpenAI, OpenRouter, vLLM,
 * local Ollama/LM Studio…). The user picks one in the chat UI.
 *
 * Configure providers under forgetdm.ai.providers.<id>.* ; legacy single-provider env vars
 * (FORGETDM_AI_BASE_URL/API_KEY/MODEL) still work and synthesize a "default" provider.
 */
@Component
@ConfigurationProperties(prefix = "forgetdm.ai")
public class AiProperties {

    /** A single OpenAI-compatible provider profile. */
    public static class Provider {
        private String label;
        private String baseUrl;
        private String apiKey = "";
        private String model;
        /** Self-hosted / on-prem endpoint (Ollama, vLLM, LM Studio…): no API key required, nothing leaves the network. */
        private boolean local = false;

        public boolean configured() {
            // A local endpoint needs only a base URL + model (no key); a remote one also needs an API key.
            return notBlank(baseUrl) && notBlank(model) && (local || notBlank(apiKey));
        }
        private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

        public String getLabel() { return label; }
        public void setLabel(String v) { label = v; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String v) { baseUrl = v; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String v) { apiKey = v; }
        public String getModel() { return model; }
        public void setModel(String v) { model = v; }
        public boolean isLocal() { return local; }
        public void setLocal(boolean v) { local = v; }
    }

    // ---- legacy single-provider fields (back-compat; become the "default" provider) ----
    private boolean enabled = false;
    private String baseUrl = "https://api.openai.com/v1";
    private String apiKey = "";
    private String model = "gpt-4o-mini";

    // ---- shared settings ----
    private double temperature = 0.2;
    private int maxToolHops = 4;
    /** For local/on-prem providers, use a slimmer prompt + fewer tool hops (much faster on CPU boxes). */
    private boolean leanLocalPrompt = true;
    private String selfBaseUrl = "http://localhost:8088";
    private int timeoutSeconds = 180;  // local models can be slow to cold-load; allow generous time
    private String defaultProvider = "openai";

    // ---- built-in self-hosted presets (on-prem; no key). Env: FORGETDM_AI_OLLAMA_BASE_URL / _MODEL / _ENABLED, etc. ----
    private boolean ollamaEnabled = true;                              // advertise the on-prem path out of the box
    private String ollamaBaseUrl = "http://localhost:11434/v1";        // Ollama's OpenAI-compatible endpoint
    private String ollamaModel = "qwen2.5:3b";                          // override in the UI if you pulled a different model
    private boolean vllmEnabled = false;                              // needs an explicit served model name
    private String vllmBaseUrl = "http://localhost:8000/v1";
    private String vllmModel = "";

    private final Map<String, Provider> providers = new LinkedHashMap<>();

    private static Provider localProvider(String label, String baseUrl, String model) {
        Provider p = new Provider();
        p.setLabel(label); p.setBaseUrl(baseUrl); p.setModel(model); p.setLocal(true);
        return p;
    }

    /** Effective provider map: configured providers, or a synthesized "default" from legacy fields. */
    public Map<String, Provider> effectiveProviders() {
        Map<String, Provider> out = new LinkedHashMap<>();
        for (Map.Entry<String, Provider> e : providers.entrySet()) {
            Provider p = e.getValue();
            if (p == null) continue;
            if (p.getLabel() == null || p.getLabel().isBlank()) p.setLabel(e.getKey());
            out.put(e.getKey(), p);
        }
        if (out.isEmpty() && (enabled || (apiKey != null && !apiKey.isBlank()))) {
            Provider def = new Provider();
            def.setLabel("Default");
            def.setBaseUrl(baseUrl);
            def.setApiKey(apiKey);
            def.setModel(model);
            out.put("default", def);
        }
        // Built-in on-prem presets — always selectable so the local path is discoverable. An explicit provider
        // configured under the same id (forgetdm.ai.providers.ollama.*) wins.
        if (ollamaEnabled && !out.containsKey("ollama"))
            out.put("ollama", localProvider("Local · Ollama", ollamaBaseUrl, ollamaModel));
        if (vllmEnabled && !out.containsKey("vllm"))
            out.put("vllm", localProvider("Local · vLLM", vllmBaseUrl, vllmModel));
        return out;
    }

    /** The provider to use when the request doesn't name one. */
    public String resolveDefaultId(Map<String, Provider> effective) {
        if (effective.containsKey(defaultProvider) && effective.get(defaultProvider).configured()) return defaultProvider;
        for (Map.Entry<String, Provider> e : effective.entrySet())
            if (e.getValue().configured()) return e.getKey();
        return effective.isEmpty() ? null : effective.keySet().iterator().next();
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String v) { baseUrl = v; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String v) { apiKey = v; }
    public String getModel() { return model; }
    public void setModel(String v) { model = v; }
    public double getTemperature() { return temperature; }
    public void setTemperature(double v) { temperature = v; }
    public int getMaxToolHops() { return maxToolHops; }
    public void setMaxToolHops(int v) { maxToolHops = v; }
    public boolean isLeanLocalPrompt() { return leanLocalPrompt; }
    public void setLeanLocalPrompt(boolean v) { leanLocalPrompt = v; }
    public String getSelfBaseUrl() { return selfBaseUrl; }
    public void setSelfBaseUrl(String v) { selfBaseUrl = v; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int v) { timeoutSeconds = v; }
    public String getDefaultProvider() { return defaultProvider; }
    public void setDefaultProvider(String v) { defaultProvider = v; }
    public boolean isOllamaEnabled() { return ollamaEnabled; }
    public void setOllamaEnabled(boolean v) { ollamaEnabled = v; }
    public String getOllamaBaseUrl() { return ollamaBaseUrl; }
    public void setOllamaBaseUrl(String v) { ollamaBaseUrl = v; }
    public String getOllamaModel() { return ollamaModel; }
    public void setOllamaModel(String v) { ollamaModel = v; }
    public boolean isVllmEnabled() { return vllmEnabled; }
    public void setVllmEnabled(boolean v) { vllmEnabled = v; }
    public String getVllmBaseUrl() { return vllmBaseUrl; }
    public void setVllmBaseUrl(String v) { vllmBaseUrl = v; }
    public String getVllmModel() { return vllmModel; }
    public void setVllmModel(String v) { vllmModel = v; }
    public Map<String, Provider> getProviders() { return providers; }
}
