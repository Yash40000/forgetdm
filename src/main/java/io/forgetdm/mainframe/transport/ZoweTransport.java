package io.forgetdm.mainframe.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.common.ApiException;
import io.forgetdm.mainframe.MainframeConnectionEntity;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * ZOWE / z-OSMF provider — real mainframe access over the z/OSMF data set REST interface
 * ({@code /zosmf/restfiles/ds}). Only works against a live z/OSMF; the API shape is per IBM's docs.
 *
 *   list   GET  {base}/restfiles/ds?dslevel=PATTERN
 *   fetch  GET  {base}/restfiles/ds/{dsname}        (X-IBM-Data-Type: binary)
 *   put    PUT  {base}/restfiles/ds/{dsname}        (X-IBM-Data-Type: binary)
 *
 * Target datasets must already be allocated on the mainframe with the intended DCB (RECFM/LRECL);
 * z/OSMF writes content, not allocation attributes.
 */
@Component
public class ZoweTransport implements MainframeTransport {

    private final ObjectMapper json = new ObjectMapper();

    private String baseUrl(MainframeConnectionEntity c) {
        if (c.getHost() == null || c.getHost().isBlank()) throw ApiException.bad("ZOWE connection needs a host");
        int port = c.getPort() == null ? 443 : c.getPort();
        String path = (c.getBasePath() == null || c.getBasePath().isBlank()) ? "/zosmf" : c.getBasePath();
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return "https://" + c.getHost() + ":" + port + path;
    }

    private HttpClient client(MainframeConnectionEntity c) {
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(20));
        if (c.isTrustAllCerts()) {
            // accept self-signed z/OSMF certs (dev/test). Disable hostname check globally for HttpClient.
            System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
            try {
                SSLContext sc = SSLContext.getInstance("TLS");
                sc.init(null, new TrustManager[]{ trustAll() }, new SecureRandom());
                b.sslContext(sc);
            } catch (Exception e) {
                throw ApiException.bad("Could not build trust-all SSL context: " + e.getMessage());
            }
        }
        return b.build();
    }

    private static X509TrustManager trustAll() {
        return new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] x, String a) { }
            public void checkServerTrusted(X509Certificate[] x, String a) { }
        };
    }

    private HttpRequest.Builder req(MainframeConnectionEntity c, String url) {
        String auth = Base64.getEncoder().encodeToString(
                ((c.getUsername() == null ? "" : c.getUsername()) + ":" +
                 (c.getPassword() == null ? "" : c.getPassword())).getBytes(StandardCharsets.UTF_8));
        return HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Basic " + auth)
                .header("X-CSRF-ZOSMF-HEADER", "true");
    }

    @Override
    public List<RemoteFile> list(MainframeConnectionEntity c, String pattern) {
        String dslevel = pattern == null || pattern.isBlank() ? "**" : pattern;
        String url = baseUrl(c) + "/restfiles/ds?dslevel=" + URLEncoder.encode(dslevel, StandardCharsets.UTF_8);
        HttpResponse<String> r = send(c, req(c, url).GET().build(), HttpResponse.BodyHandlers.ofString());
        ensure2xx(r.statusCode(), r.body(), "list");
        List<RemoteFile> out = new ArrayList<>();
        try {
            JsonNode items = json.readTree(r.body()).path("items");
            for (JsonNode it : items) {
                out.add(new RemoteFile(
                        it.path("dsname").asText(null),
                        it.path("recfm").asText(null),
                        it.hasNonNull("lrecl") ? it.path("lrecl").asInt() : null,
                        it.hasNonNull("size") ? it.path("size").asLong() : null,
                        it.path("dsorg").asText(null)));
            }
        } catch (Exception e) {
            throw ApiException.bad("Could not parse z/OSMF list response: " + e.getMessage());
        }
        return out;
    }

    @Override
    public RemoteFile stat(MainframeConnectionEntity c, String name) {
        for (RemoteFile f : list(c, name)) {
            if (f.name() != null && f.name().equalsIgnoreCase(name)) return f;
        }
        List<RemoteFile> any = list(c, name);
        if (!any.isEmpty()) return any.get(0);
        throw ApiException.bad("Dataset not found: " + name);
    }

    @Override
    public byte[] fetch(MainframeConnectionEntity c, String name) {
        String url = baseUrl(c) + "/restfiles/ds/" + name;
        HttpResponse<byte[]> r = send(c, req(c, url).header("X-IBM-Data-Type", "binary").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        ensure2xx(r.statusCode(), "<binary>", "fetch " + name);
        return r.body();
    }

    @Override
    public void put(MainframeConnectionEntity c, String name, byte[] data, String recfm, Integer lrecl) {
        String url = baseUrl(c) + "/restfiles/ds/" + name;
        HttpResponse<String> r = send(c, req(c, url)
                .header("X-IBM-Data-Type", "binary")
                .header("Content-Type", "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(data)).build(),
                HttpResponse.BodyHandlers.ofString());
        ensure2xx(r.statusCode(), r.body(), "put " + name);
    }

    private <T> HttpResponse<T> send(MainframeConnectionEntity c, HttpRequest req, HttpResponse.BodyHandler<T> h) {
        try {
            return client(c).send(req, h);
        } catch (Exception e) {
            throw ApiException.bad("z/OSMF request failed: " + e.getMessage());
        }
    }

    private static void ensure2xx(int status, String body, String op) {
        if (status / 100 != 2) throw ApiException.bad("z/OSMF " + op + " returned HTTP " + status + ": " + body);
    }
}
