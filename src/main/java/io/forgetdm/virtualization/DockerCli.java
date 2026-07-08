package io.forgetdm.virtualization;

import io.forgetdm.common.ApiException;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper around the host's docker CLI. This is the deliberate step outside the
 * JVM: Docker's overlay2 layer store acts as the copy-on-write storage pool for the
 * CONTAINER virtualization provider.
 */
@Component
public class DockerCli {

    /** Run a docker command; returns trimmed stdout+stderr, throws ApiException on failure. */
    public String run(int timeoutSeconds, String... args) {
        List<String> cmd = new ArrayList<>(args.length + 1);
        cmd.add("docker");
        cmd.addAll(List.of(args));
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            Thread pump = new Thread(() -> {
                try { p.getInputStream().transferTo(buf); } catch (Exception ignored) { }
            });
            pump.start();
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw ApiException.bad("docker " + args[0] + " timed out after " + timeoutSeconds + "s");
            }
            pump.join(5000);
            String out = buf.toString().trim();
            if (p.exitValue() != 0) {
                throw ApiException.bad("docker " + args[0] + " failed (exit " + p.exitValue() + "): " + tail(out));
            }
            return out;
        } catch (ApiException e) { throw e; }
        catch (Exception e) {
            throw ApiException.bad("docker " + (args.length > 0 ? args[0] : "") + " failed: " + e.getMessage()
                    + " — is Docker Desktop running and 'docker' on PATH?");
        }
    }

    public boolean available() {
        try {
            run(10, "version", "--format", "{{.Server.Version}}");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String serverVersion() {
        try {
            return run(10, "version", "--format", "{{.Server.Version}}");
        } catch (Exception e) {
            return null;
        }
    }

    private static String tail(String s) {
        if (s == null) return "";
        String[] lines = s.split("\\R");
        int from = Math.max(0, lines.length - 6);
        return String.join(" | ", List.of(lines).subList(from, lines.length));
    }
}
