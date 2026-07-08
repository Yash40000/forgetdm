package io.forgetdm.virtualization;

import io.forgetdm.common.ApiException;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Key-auth SSH command runner for target environments. */
final class SshExec {
    private SshExec() {}

    static String exec(String host, String user, int port, int timeoutSeconds, String command) {
        List<String> cmd = List.of("ssh", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=accept-new",
                "-p", String.valueOf(port), user + "@" + host, command);
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
                throw ApiException.bad("Command on " + host + " timed out after " + timeoutSeconds + "s");
            }
            pump.join(5000);
            String out = buf.toString().trim();
            if (p.exitValue() != 0) {
                throw ApiException.bad("Command on " + host + " failed (exit " + p.exitValue() + "): "
                        + (out.length() > 400 ? out.substring(out.length() - 400) : out));
            }
            return out;
        } catch (ApiException e) { throw e; }
        catch (Exception e) {
            throw ApiException.bad("SSH to " + user + "@" + host + " failed: " + e.getMessage());
        }
    }
}
