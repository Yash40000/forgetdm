package io.forgetdm.mainframe;

import io.forgetdm.common.ApiException;
import io.forgetdm.core.copybook.Copybook;
import io.forgetdm.core.copybook.Field;
import io.forgetdm.core.copybook.RecordCodec;
import io.forgetdm.core.copybook.RecordValue;
import io.forgetdm.core.copybook.codec.Ebcdic;
import io.forgetdm.mainframe.transport.TransportFactory;
import io.forgetdm.provision.SyntheticGenService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Mainframe file generator: generate synthetic records to a copybook's layout, encode each to
 * EBCDIC (FB/VB) via the copybook codec, then either deliver to an LPAR or hand back artifacts to
 * download — the copybook, the pre-conversion logical data (CSV), and the post-conversion binary.
 */
@Service
public class MainframeGenService {

    private final CopybookDefRepository copybooks;
    private final MainframeConnectionRepository connections;
    private final TransportFactory transports;
    private final SyntheticGenService synth;

    public MainframeGenService(CopybookDefRepository copybooks, MainframeConnectionRepository connections,
                              TransportFactory transports, SyntheticGenService synth) {
        this.copybooks = copybooks; this.connections = connections;
        this.transports = transports; this.synth = synth;
    }

    public record GenFileColumn(String field, String generator, String param1, String param2) {}
    public record GenFileReq(Long copybookId, String codePage, String recfm, Long seed, Long rowCount,
                             List<GenFileColumn> columns, String output, Long targetConnectionId, String targetName) {}

    public Map<String, Object> generateFile(GenFileReq req) {
        CopybookDefEntity def = copybooks.findById(req.copybookId() == null ? -1L : req.copybookId())
                .orElseThrow(() -> ApiException.bad("Select a copybook"));
        Copybook cb = CopybookSupport.parse(def.getSource());
        Field record = cb.primaryRecord();
        int len = record.length();
        if (len <= 0) throw ApiException.bad("Copybook record length resolved to 0");

        String codePage = firstNonBlank(req.codePage(), def.getCodePage(), "Cp037");
        String recfm = req.recfm() == null || req.recfm().isBlank() ? "FB" : req.recfm().trim().toUpperCase(Locale.ROOT);
        long rowCount = req.rowCount() == null ? 100 : Math.max(0, Math.min(req.rowCount(), 200_000));
        long seed = req.seed() == null ? 42 : req.seed();

        // build the generation columns (field -> generator), reusing the full synthetic engine
        List<SyntheticGenService.GenColumn> gcols = new ArrayList<>();
        for (GenFileColumn c : req.columns() == null ? List.<GenFileColumn>of() : req.columns())
            gcols.add(new SyntheticGenService.GenColumn(c.field(), c.generator(), c.param1(), c.param2(), false, null, null, null, null, null));
        SyntheticGenService.GenTable table = new SyntheticGenService.GenTable(record.name(), rowCount, gcols);
        List<LinkedHashMap<String, String>> rows = synth.generateRows(table, rowCount, seed);

        // encode each row into a fixed-width EBCDIC record (blank-fill, then overlay every field)
        RecordCodec codec = new RecordCodec(record, new Ebcdic(codePage));
        List<byte[]> recs = new ArrayList<>(rows.size());
        for (LinkedHashMap<String, String> row : rows) {
            byte[] blank = new byte[len];
            Arrays.fill(blank, (byte) 0x40);                 // EBCDIC space
            RecordValue rv = codec.decode(blank);
            Map<String, String> changes = new LinkedHashMap<>();
            for (RecordValue.DecodedField df : rv.fields()) {
                String v = generatedValue(row, df.path());
                if (v != null) changes.put(df.path(), v);
            }
            recs.add(codec.encodeOverlay(rv, blank, changes));
        }
        byte[] ebcdic = RecordSplitter.join(recs, recfm);

        // pre-conversion logical data as CSV
        List<String> colNames = new ArrayList<>();
        for (SyntheticGenService.GenColumn c : gcols) colNames.add(c.name());
        StringBuilder csv = new StringBuilder();
        csv.append(csvRow(colNames, n -> n)).append("\n");
        for (LinkedHashMap<String, String> row : rows) csv.append(csvRow(colNames, row::get)).append("\n");

        String base = safeName(def.getName());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recordLength", len);
        result.put("rowCount", rows.size());
        result.put("recfm", recfm);
        result.put("codePage", codePage);
        result.put("copybookName", base + ".cpy");
        result.put("copybook", def.getSource());
        result.put("preName", base + ".csv");
        result.put("preContent", csv.toString());
        result.put("postName", base + ".dat");
        result.put("postBase64", Base64.getEncoder().encodeToString(ebcdic));

        if ("TARGET".equalsIgnoreCase(req.output())) {
            if (req.targetConnectionId() == null) throw ApiException.bad("Pick a target LPAR for delivery");
            MainframeConnectionEntity conn = connections.findById(req.targetConnectionId())
                    .orElseThrow(() -> ApiException.bad("Target LPAR not found"));
            String name = (req.targetName() != null && !req.targetName().isBlank()) ? req.targetName() : (base + ".dat");
            transports.forConnection(conn).put(conn, name, ebcdic, recfm, len);
            result.put("delivered", Map.of("connection", conn.getName(), "name", name, "bytes", ebcdic.length));
        }
        return result;
    }

    /**
     * Generator columns come from the copybook registry as record-qualified paths, while the codec
     * exposes paths relative to the primary record. Match the complete relative path (not only the
     * leaf name) so nested groups remain unambiguous.
     */
    static String generatedValue(Map<String, String> row, String codecPath) {
        if (row == null || row.isEmpty() || codecPath == null) return null;
        String wanted = CopybookSupport.stripSubscripts(codecPath);
        if (row.containsKey(wanted)) return row.get(wanted);
        for (Map.Entry<String, String> entry : row.entrySet()) {
            String candidate = CopybookSupport.stripSubscripts(entry.getKey());
            if (candidate != null && (candidate.equalsIgnoreCase(wanted)
                    || candidate.toUpperCase(Locale.ROOT).endsWith("." + wanted.toUpperCase(Locale.ROOT)))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String csvRow(List<String> cols, java.util.function.Function<String, String> value) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) { if (i > 0) sb.append(','); sb.append(csv(value.apply(cols.get(i)))); }
        return sb.toString();
    }

    private static String csv(String v) {
        if (v == null) return "";
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r"))
            return "\"" + v.replace("\"", "\"\"") + "\"";
        return v;
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return "Cp037";
    }

    private static String safeName(String s) {
        String out = s == null ? "mainframe" : s.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-");
        return out.isBlank() ? "mainframe" : out;
    }
}
