package io.forgetdm.core.util;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Classpath-backed seedlist registry. Seedlists are name lists used for substitution masking. Pure Java: no framework needed. */
public final class SeedLists {
    private static final Map<String, List<String>> CACHE = new ConcurrentHashMap<>();

    public static List<String> get(String name) {
        return CACHE.computeIfAbsent(name, SeedLists::load);
    }

    /** Register an in-memory seedlist (e.g., uploaded by a user via the API). */
    public static void register(String name, List<String> values) {
        CACHE.put(name, List.copyOf(values));
    }

    private static List<String> load(String name) {
        String path = "/seedlists/" + name;
        try (InputStream in = SeedLists.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalArgumentException("Seedlist not found: " + name);
            List<String> out = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) { line = line.trim(); if (!line.isEmpty()) out.add(line); }
            }
            return List.copyOf(out);
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("Failed loading seedlist " + name, e); }
    }
}
