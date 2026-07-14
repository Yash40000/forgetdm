package io.forgetdm.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class LlmClientTest {

    @Test
    void selectsAnInstalledPrivateModelWhenConfiguredModelIsMissing() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/models", exchange -> {
            byte[] body = "{\"data\":[{\"id\":\"llama3.2:3b\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            AiProperties.Provider provider = local("http://localhost:" + server.getAddress().getPort() + "/v1", "qwen2.5:3b");
            LlmClient client = new LlmClient(new AiProperties());

            LlmClient.ModelRuntime runtime = client.runtime(provider);

            assertThat(runtime.reachable()).isTrue();
            assertThat(runtime.autoSelected()).isTrue();
            assertThat(runtime.model()).isEqualTo("llama3.2:3b");
            assertThat(client.resolveModel(provider, null)).isEqualTo("llama3.2:3b");
            assertThat(client.resolveModel(provider, "pinned-model")).isEqualTo("pinned-model");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preservesConfiguredModelWhenPrivateRuntimeIsOffline() {
        AiProperties.Provider provider = local("http://127.0.0.1:1/v1", "bank-approved-model");
        LlmClient.ModelRuntime runtime = new LlmClient(new AiProperties()).runtime(provider);

        assertThat(runtime.reachable()).isFalse();
        assertThat(runtime.model()).isEqualTo("bank-approved-model");
    }

    private AiProperties.Provider local(String baseUrl, String model) {
        AiProperties.Provider provider = new AiProperties.Provider();
        provider.setLabel("Local test runtime");
        provider.setBaseUrl(baseUrl);
        provider.setModel(model);
        provider.setLocal(true);
        return provider;
    }
}
