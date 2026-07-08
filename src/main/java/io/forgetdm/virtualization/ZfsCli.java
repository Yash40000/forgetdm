package io.forgetdm.virtualization;

import io.forgetdm.common.ApiException;
import io.forgetdm.config.ForgeProps;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Command channel to the ZFS engine host.
 * Runs zfs/zpool/docker commands either locally (ForgeTDM deployed on the engine
 * host itself) or over SSH with key-based auth (BatchMode, no prompts).
 */
@Component
public class ZfsCli {
    private final ForgeProps.Zfs cfg;

    public ZfsCli(ForgeProps props) {
        this.cfg = props.getVirtualization().getZfs();
    }

    public ForgeProps.Zfs config() { return cfg; }

    public boolean remote() { return cfg.getHost() != null && !cfg.getHost().isBlank(); }

    /** Run a shell command on the engine host; returns trimmed output, throws on failure. */
    public String exec(int timeoutSeconds, String command) {
        // On hosts where the SSH user can't run zfs/docker directly (e.g. a corporate box where root SSH is
        // blocked but passwordless sudo works), run the WHOLE command as root via sudo. base64 avoids any quoting.
        String effective = cfg.isUseSudo() ? sudoWrap(command) : command;
        List<String> cmd = new ArrayList<>();
        if (remote()) {
            cmd.add("ssh");
            cmd.add("-o"); cmd.add("BatchMode=yes");
            cmd.add("-o"); cmd.add("StrictHostKeyChecking=accept-new");
            cmd.add("-p"); cmd.add(String.valueOf(cfg.getSshPort()));
            cmd.add(cfg.getSshUser() + "@" + cfg.getHost());
            cmd.add(effective);
        } else {
            cmd.add("bash"); cmd.add("-lc"); cmd.add(effective);
        }
        try {
            // Keep stdout and stderr SEPARATE. SSH login banners / MOTD (e.g. a corporate legal notice) are written
            // to stderr; merging them into stdout would corrupt every value ForgeTDM parses (mountpoints, etc.).
            ProcessBuilder pb = new ProcessBuilder(cmd);
            Process p = pb.start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            Thread pumpOut = new Thread(() -> { try { p.getInputStream().transferTo(out); } catch (Exception ignored) { } });
            Thread pumpErr = new Thread(() -> { try { p.getErrorStream().transferTo(err); } catch (Exception ignored) { } });
            pumpOut.start(); pumpErr.start();
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw ApiException.bad("Engine command timed out after " + timeoutSeconds + "s: " + summarize(command));
            }
            pumpOut.join(5000); pumpErr.join(5000);
            String stdout = out.toString().trim();
            if (p.exitValue() != 0) {
                String diag = err.toString().trim();
                throw ApiException.bad("Engine command failed (exit " + p.exitValue() + "): "
                        + summarize(command) + " -> " + tail(diag.isEmpty() ? stdout : diag));
            }
            return stdout;   // clean stdout only — banner noise on stderr is dropped
        } catch (ApiException e) { throw e; }
        catch (Exception e) {
            throw ApiException.bad("Engine command failed: " + summarize(command) + " -> " + e.getMessage()
                    + (remote() ? " — check SSH key auth to " + cfg.getSshUser() + "@" + cfg.getHost() : ""));
        }
    }

    public boolean available() {
        try {
            exec(15, "zfs list -H -o name " + shq(cfg.getPool()));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Run the entire command as root via passwordless sudo. base64-encode so no quoting/escaping is needed. */
    private static String sudoWrap(String command) {
        String b64 = java.util.Base64.getEncoder()
                .encodeToString(command.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return "echo " + b64 + " | base64 -d | sudo -n bash";
    }

    /** Single-quote a value for the remote shell. */
    static String shq(String s) {
        return "'" + String.valueOf(s).replace("'", "'\\''") + "'";
    }

    private static String summarize(String command) {
        return command.length() > 120 ? command.substring(0, 120) + "..." : command;
    }

    private static String tail(String s) {
        if (s == null) return "";
        List<String> keep = new ArrayList<>();
        for (String ln : s.split("\\R")) {
            String t = ln.trim();
            if (t.isEmpty()) continue;
            // Drop SSH login-banner box art (│ borders, ┌─┐ rules) so real errors stay readable.
            if (t.startsWith("|") || t.startsWith("/--") || t.startsWith("\\--") || t.matches("[-/\\\\| ]{6,}")) continue;
            keep.add(t);
        }
        if (keep.isEmpty()) for (String ln : s.split("\\R")) if (!ln.trim().isEmpty()) keep.add(ln.trim());
        int from = Math.max(0, keep.size() - 6);
        return String.join(" | ", keep.subList(from, keep.size()));
    }
}
