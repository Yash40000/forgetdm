package io.forgetdm.mainframe.transport;

import io.forgetdm.common.ApiException;
import io.forgetdm.mainframe.MainframeConnectionEntity;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * LOCAL provider — treats a directory as an LPAR. Each "dataset" is a file; RECFM/LRECL are kept in
 * a sidecar {@code <name>.attr} so stat() can report them the way z/OSMF would. Lets the whole
 * pipeline run end-to-end with no mainframe.
 */
@Component
public class LocalTransport implements MainframeTransport {

    private Path base(MainframeConnectionEntity conn) {
        if (conn.getBaseDir() == null || conn.getBaseDir().isBlank())
            throw ApiException.bad("LOCAL connection '" + conn.getName() + "' has no base directory");
        return Path.of(conn.getBaseDir());
    }

    private Path resolve(MainframeConnectionEntity conn, String name) {
        if (name == null || name.isBlank()) throw ApiException.bad("File name required");
        if (name.contains("..") || name.contains("/") || name.contains("\\"))
            throw ApiException.bad("Illegal file name: " + name);
        return base(conn).resolve(name);
    }

    @Override
    public List<RemoteFile> list(MainframeConnectionEntity conn, String pattern) {
        Path dir = base(conn);
        if (!Files.isDirectory(dir)) throw ApiException.bad("Base directory does not exist: " + dir);
        Pattern rx = globToRegex(pattern);
        List<RemoteFile> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(Files::isRegularFile)
                 .filter(p -> !p.getFileName().toString().endsWith(".attr"))
                 .filter(p -> rx == null || rx.matcher(p.getFileName().toString()).matches())
                 .sorted()
                 .forEach(p -> out.add(stat(conn, p.getFileName().toString())));
        } catch (IOException e) {
            throw ApiException.bad("List failed: " + e.getMessage());
        }
        return out;
    }

    @Override
    public RemoteFile stat(MainframeConnectionEntity conn, String name) {
        Path f = resolve(conn, name);
        if (!Files.exists(f)) throw ApiException.bad("File not found: " + name);
        Properties attr = readAttr(f);
        Long size;
        try { size = Files.size(f); } catch (IOException e) { size = null; }
        Integer lrecl = attr.getProperty("lrecl") != null ? Integer.valueOf(attr.getProperty("lrecl")) : null;
        return new RemoteFile(name, attr.getProperty("recfm", "FB"), lrecl, size, attr.getProperty("dsorg", "PS"));
    }

    @Override
    public byte[] fetch(MainframeConnectionEntity conn, String name) {
        try {
            return Files.readAllBytes(resolve(conn, name));
        } catch (IOException e) {
            throw ApiException.bad("Fetch failed for " + name + ": " + e.getMessage());
        }
    }

    @Override
    public void put(MainframeConnectionEntity conn, String name, byte[] data, String recfm, Integer lrecl) {
        Path f = resolve(conn, name);
        try {
            Files.createDirectories(f.getParent());
            Files.write(f, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Properties attr = new Properties();
            if (recfm != null) attr.setProperty("recfm", recfm);
            if (lrecl != null) attr.setProperty("lrecl", String.valueOf(lrecl));
            try (var out = Files.newOutputStream(Path.of(f + ".attr"))) {
                attr.store(out, "ForgeTDM mainframe file attributes");
            }
        } catch (IOException e) {
            throw ApiException.bad("Write failed for " + name + ": " + e.getMessage());
        }
    }

    private Properties readAttr(Path f) {
        Properties p = new Properties();
        Path attr = Path.of(f + ".attr");
        if (Files.exists(attr)) {
            try (var in = Files.newInputStream(attr)) { p.load(in); } catch (IOException ignored) { }
        }
        return p;
    }

    private static Pattern globToRegex(String pattern) {
        if (pattern == null || pattern.isBlank() || pattern.equals("*") || pattern.equals("**")) return null;
        StringBuilder sb = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            switch (c) {
                case '*': sb.append(".*"); break;
                case '?': sb.append('.'); break;
                case '.': sb.append("\\."); break;
                default:  sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
    }
}
