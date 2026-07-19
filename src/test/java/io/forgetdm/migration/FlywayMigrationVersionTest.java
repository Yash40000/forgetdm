package io.forgetdm.migration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class FlywayMigrationVersionTest {

    private static final Pattern VERSIONED_MIGRATION = Pattern.compile("^V([^_]+)__.+\\.sql$");

    @Test
    void versionedMigrationsHaveUniqueVersions() throws Exception {
        URL migrationResource = getClass().getClassLoader().getResource("db/migration");
        assertNotNull(migrationResource, "Flyway migration directory is missing from the test classpath");

        Path migrationDirectory = Paths.get(migrationResource.toURI());
        Map<String, List<String>> migrationsByVersion = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.list(migrationDirectory)) {
            paths.map(path -> path.getFileName().toString())
                    .sorted()
                    .forEach(fileName -> addVersionedMigration(migrationsByVersion, fileName));
        }

        Map<String, List<String>> duplicates = new LinkedHashMap<>();
        migrationsByVersion.forEach((version, files) -> {
            if (files.size() > 1) {
                duplicates.put(version, files);
            }
        });

        assertTrue(duplicates.isEmpty(), "Duplicate Flyway migration versions: " + duplicates);
    }

    private static void addVersionedMigration(Map<String, List<String>> migrationsByVersion, String fileName) {
        Matcher matcher = VERSIONED_MIGRATION.matcher(fileName);
        if (matcher.matches()) {
            migrationsByVersion.computeIfAbsent(matcher.group(1), ignored -> new java.util.ArrayList<>())
                    .add(fileName);
        }
    }
}
