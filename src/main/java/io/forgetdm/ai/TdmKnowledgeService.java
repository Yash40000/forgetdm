package io.forgetdm.ai;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * The ForgeTDM "TDM Advisor" knowledge base: a curated playbook of Test Data Management scenarios (loaded from
 * {@code classpath:knowledge/tdm-playbook.md}) plus a lightweight TF-IDF keyword retriever. This gives the
 * assistant domain expertise via retrieval (RAG) rather than fine-tuning — deterministic, CPU-cheap (no embedding
 * model), fully on-prem, and updated just by editing the playbook file.
 */
@Service
public class TdmKnowledgeService {

    public record Entry(String title, String keywords, String body) {}

    /** A parsed entry plus its precomputed term stats for scoring. */
    private static final class Doc {
        final Entry entry;
        final Map<String, Integer> tf = new HashMap<>();   // term -> count over the whole entry
        final Set<String> boosted = new HashSet<>();       // terms in title + keywords (weighted higher)
        Doc(Entry e) { this.entry = e; }
    }

    private static final Set<String> STOP = Set.of(
            "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with", "is", "are", "be", "as", "at",
            "by", "it", "this", "that", "how", "do", "does", "can", "we", "i", "my", "you", "your", "what", "which",
            "when", "should", "would", "use", "using", "need", "want", "get", "so", "if", "not", "no", "yes", "me",
            "help", "please", "there", "their", "them", "into", "from", "but", "our", "all", "any", "one");

    private final List<Doc> docs = new ArrayList<>();

    @PostConstruct
    void load() {
        String raw = readResource("/knowledge/tdm-playbook.md");
        if (raw == null || raw.isBlank()) return;
        for (Entry e : parse(raw)) index(e);
    }

    public int size() { return docs.size(); }

    /** Highest-scoring playbook entries for a free-text scenario/question (empty when nothing is relevant). */
    public List<Entry> search(String query, int topN) {
        List<String> terms = terms(query);
        if (terms.isEmpty() || docs.isEmpty()) return List.of();

        List<Doc> ranked = new ArrayList<>();
        Map<Doc, Double> scores = new IdentityHashMap<>();
        int n = docs.size();
        for (Doc d : docs) {
            double s = 0;
            for (String t : terms) {
                int tf = d.tf.getOrDefault(t, 0);
                if (tf == 0) continue;
                int df = 0;
                for (Doc o : docs) if (o.tf.containsKey(t)) df++;
                double idf = Math.log(1.0 + (double) n / (1 + df));
                double weight = d.boosted.contains(t) ? 3.0 : 1.0;
                s += idf * weight * Math.min(3, tf);
            }
            if (s > 0) { scores.put(d, s); ranked.add(d); }
        }
        ranked.sort((x, y) -> Double.compare(scores.get(y), scores.get(x)));

        List<Entry> out = new ArrayList<>();
        for (int i = 0; i < Math.min(Math.max(0, topN), ranked.size()); i++) out.add(ranked.get(i).entry);
        return out;
    }

    // ---------------------------------------------------------------- parsing / indexing

    private void index(Entry e) {
        Doc d = new Doc(e);
        for (String t : terms(e.title() + " " + e.body())) d.tf.merge(t, 1, Integer::sum);
        for (String t : terms(e.keywords())) { d.tf.merge(t, 1, Integer::sum); d.boosted.add(t); }
        for (String t : terms(e.title())) d.boosted.add(t);
        if (!d.tf.isEmpty()) docs.add(d);
    }

    /** Split the playbook into entries on `---`; each entry is a `## Title`, an optional `keywords:` line, then body. */
    private List<Entry> parse(String raw) {
        List<Entry> entries = new ArrayList<>();
        for (String block : raw.split("(?m)^---\\s*$")) {
            String title = null, keywords = "";
            StringBuilder body = new StringBuilder();
            for (String line : block.split("\\R")) {
                String t = line.trim();
                if (title == null && t.startsWith("## ")) { title = t.substring(3).trim(); continue; }
                if (title == null) continue;   // skip the file header before the first entry
                if (t.toLowerCase(Locale.ROOT).startsWith("keywords:")) { keywords = t.substring(9).trim(); continue; }
                if (!t.isEmpty()) body.append(t).append(' ');
            }
            if (title != null && body.length() > 0) entries.add(new Entry(title, keywords, body.toString().trim()));
        }
        return entries;
    }

    private static List<String> terms(String text) {
        if (text == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String tok : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (tok.length() < 2 || STOP.contains(tok)) continue;
            out.add(tok);
        }
        return out;
    }

    private String readResource(String path) {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
