package io.forgetdm.provision.loader;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record NativeLoadResult(String strategy,
                               String engine,
                               boolean nativeUsed,
                               boolean success,
                               int exitCode,
                               String status,
                               String message,
                               List<String> command,
                               List<String> redactedCommand,
                               List<Path> supportFiles,
                               String stdout,
                               String stderr,
                               Instant startedAt,
                               Instant finishedAt,
                               Map<String, Object> details) {
}
