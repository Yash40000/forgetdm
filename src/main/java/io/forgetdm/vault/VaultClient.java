package io.forgetdm.vault;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.common.ApiException;
import io.forgetdm.config.ForgeProps;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Minimal HashiCorp Vault HTTP client — reads a secret from the KV engine (v1 or v2) and reports
 * server health. Deliberately dependency-free (JDK HttpClient) so it works in any deployment.
 * The Vault token is never logged or returned.
 */
@Component
public class VaultClient {

    private final ForgeProps.Vault cfg;
    private final ObjectMapper json;
    private final HttpClient http;

    public VaultClient(ForgeProps props, ObjectMapper json) {
        this.cfg = props.getVault();
        this.json = json;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1000, cfg.getTimeoutMs())))
                .build();
    }

    public boolean isEnabled() { return cfg.isEnabled(); }

    /** Read the configured masking-secret field from the configured KV path. */
    public String readMaskingSecret() {
        return readKv(cfg.getField());
    }

    /** Read a single field from the configured KV secret path. */
    public String readKv(String field) {
        String base = trimTrailingSlash(cfg.getAddress());
        String url = cfg.getKvVersion() == 2
                ? base + "/v1/" + cfg.getKvMount() + "/data/" + cfg.getPath()
                : base + "/v1/" + cfg.getKvMount() + "/" + cfg.getPath();
        JsonNode root = get(url);
        // v2: { data: { data: { <field>: ... } } }   v1: { data: { <field>: ... } }
        JsonNode data = root.path("data");
        if (cfg.getKvVersion() == 2) data = data.path("data");
        JsonNode value = data.path(field);
        if (value.isMissingNode() || value.isNull()) {
            throw ApiException.bad("Vault secret '" + cfg.getPath() + "' has no field '" + field + "'.");
        }
        return value.asText();
    }

    /** Server health — never throws for an unsealed/standby node; returns a status snapshot. */
    public Health health() {
        String url = trimTrailingSlash(cfg.getAddress()) + "/v1/sys/health?standbyok=true&perfstandbyok=true";
        try {
            HttpResponse<String> resp = send(HttpRequest.newBuilder(URI.create(url)).GET());
            JsonNode body = json.readTree(resp.body() == null || resp.body().isBlank() ? "{}" : resp.body());
            // Vault returns 200/429/472/473/501/503 depending on state, all with a JSON body.
            boolean reachable = resp.statusCode() > 0;
            return new Health(reachable, body.path("sealed").asBoolean(false),
                    body.path("initialized").asBoolean(false), body.path("version").asText(null), null);
        } catch (Exception e) {
            return new Health(false, false, false, null, rootMessage(e));
        }
    }

    // ------------------------------------------------------------------ internals

    private JsonNode get(String url) {
        try {
            HttpResponse<String> resp = send(HttpRequest.newBuilder(URI.create(url)).GET());
            if (resp.statusCode() == 403) throw ApiException.forbidden("Vault denied access (check token/policy).");
            if (resp.statusCode() == 404) throw ApiException.notFound("Vault path not found: " + url);
            if (resp.statusCode() / 100 != 2) {
                throw ApiException.bad("Vault returned HTTP " + resp.statusCode() + " for " + url);
            }
            return json.readTree(resp.body() == null ? "{}" : resp.body());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.bad("Vault request failed: " + rootMessage(e));
        }
    }

    private HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        b.timeout(Duration.ofMillis(Math.max(1000, cfg.getTimeoutMs())));
        if (cfg.getToken() != null && !cfg.getToken().isBlank()) b.header("X-Vault-Token", cfg.getToken());
        if (cfg.getNamespace() != null && !cfg.getNamespace().isBlank()) b.header("X-Vault-Namespace", cfg.getNamespace());
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String trimTrailingSlash(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String rootMessage(Throwable e) {
        Throwable r = e;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        return r.getMessage() == null ? r.toString() : r.getMessage();
    }

    public record Health(boolean reachable, boolean sealed, boolean initialized, String version, String error) {}
}
