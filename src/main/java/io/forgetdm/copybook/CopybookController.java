package io.forgetdm.copybook;

import io.forgetdm.core.copybook.Copybook;
import io.forgetdm.core.copybook.CopybookParser;
import io.forgetdm.core.copybook.Field;
import io.forgetdm.core.copybook.RecordCodec;
import io.forgetdm.core.copybook.RecordValue;
import io.forgetdm.core.copybook.codec.Ebcdic;
import io.forgetdm.core.mask.MaskContext;
import io.forgetdm.core.mask.MaskFunction;
import io.forgetdm.core.mask.MaskingEngine;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Interactive endpoints for the Copybook Studio panel: parse a copybook to a byte layout, decode a
 * record (hex paste or uploaded binary EBCDIC file), and preview deterministic masking on selected
 * fields — all off-platform, no mainframe required.
 */
@RestController
@RequestMapping("/api/copybook")
public class CopybookController {

    private final MaskingEngine engine;

    public CopybookController(MaskingEngine engine) { this.engine = engine; }

    // -------------------------------------------------------------------- parse

    public record ParseReq(String copybook, String codePage) {}

    @PostMapping("/parse")
    public Map<String, Object> parse(@RequestBody ParseReq req) {
        Copybook cb = CopybookParser.parse(req.copybook());
        Field record = cb.primaryRecord();
        List<Map<String, Object>> fields = new ArrayList<>();
        for (Copybook.FlatField ff : cb.flatten(record)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", ff.path());
            m.put("level", ff.field().level());
            m.put("offset", ff.offset());
            m.put("length", ff.length());
            m.put("type", typeLabel(ff.field()));
            m.put("picture", ff.field().rawPicture() == null ? "" : ff.field().rawPicture());
            fields.add(m);
        }
        return Map.of("record", record.name(), "recordLength", record.length(), "fields", fields);
    }

    // ------------------------------------------------------------------- decode

    public record DecodeReq(String copybook, String codePage, String hex, String base64) {}

    @PostMapping("/decode")
    public Map<String, Object> decode(@RequestBody DecodeReq req) {
        Copybook cb = CopybookParser.parse(req.copybook());
        RecordCodec codec = new RecordCodec(cb.primaryRecord(), new Ebcdic(req.codePage()));
        byte[] bytes = req.base64() != null && !req.base64().isBlank()
                ? java.util.Base64.getDecoder().decode(req.base64().trim())
                : fromHex(req.hex());
        RecordValue rv = codec.decode(bytes);
        return Map.of("recordLength", cb.primaryRecord().length(),
                "byteLength", bytes.length,
                "fields", decodedRows(rv));
    }

    // ------------------------------------------------- decode an uploaded binary file

    @PostMapping(value = "/decode-file", consumes = {"multipart/form-data"})
    public Map<String, Object> decodeFile(@RequestParam("file") MultipartFile file,
                                          @RequestParam("copybook") String copybook,
                                          @RequestParam(value = "codePage", required = false) String codePage,
                                          @RequestParam(value = "maxRecords", required = false, defaultValue = "50") int maxRecords)
            throws Exception {
        Copybook cb = CopybookParser.parse(copybook);
        Field record = cb.primaryRecord();
        int len = record.length();
        if (len <= 0) throw new IllegalArgumentException("Record length resolved to 0 — check the copybook");
        RecordCodec codec = new RecordCodec(record, new Ebcdic(codePage));

        byte[] all = file.getBytes();
        int total = all.length / len;
        int show = Math.max(0, Math.min(total, maxRecords));
        List<Object> records = new ArrayList<>();
        for (int i = 0; i < show; i++) {
            byte[] rec = java.util.Arrays.copyOfRange(all, i * len, i * len + len);
            records.add(Map.of("index", i, "hex", toHex(rec), "fields", decodedRows(codec.decode(rec))));
        }
        return Map.of("recordLength", len,
                "fileBytes", all.length,
                "recordCount", total,
                "shown", show,
                "remainderBytes", all.length - total * len,
                "records", records);
    }

    // -------------------------------------------------------------- mask preview

    public record MaskSpec(String path, String function, String param1, String param2) {}
    public record MaskReq(String copybook, String codePage, String hex, List<MaskSpec> masks) {}

    @PostMapping("/mask-preview")
    public Map<String, Object> maskPreview(@RequestBody MaskReq req) {
        Copybook cb = CopybookParser.parse(req.copybook());
        RecordCodec codec = new RecordCodec(cb.primaryRecord(), new Ebcdic(req.codePage()));
        byte[] original = fromHex(req.hex());
        RecordValue rv = codec.decode(original);

        Map<String, String> changes = new LinkedHashMap<>();
        List<Map<String, Object>> perField = new ArrayList<>();
        MaskContext ctx = new MaskContext(1);
        for (MaskSpec spec : req.masks()) {
            RecordValue.DecodedField df = rv.get(spec.path());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", spec.path());
            if (df == null) { row.put("error", "no such field"); perField.add(row); continue; }
            row.put("before", df.value());

            String maskedVal;
            try {
                maskedVal = engine.mask(MaskFunction.valueOf(spec.function().toUpperCase(Locale.ROOT)),
                        df.field().name().toLowerCase(Locale.ROOT), df.value(),
                        blankToNull(spec.param1()), blankToNull(spec.param2()), ctx);
            } catch (Exception e) {
                row.put("error", "mask failed: " + e.getMessage());
                perField.add(row);
                continue;
            }

            // validate the masked value can be written back into THIS field's type/width before
            // applying it — so one bad field reports a precise error instead of failing the record
            try {
                java.util.Map<String, String> one = new java.util.HashMap<>();
                one.put(spec.path(), maskedVal);
                codec.encodeOverlay(rv, original, one);   // throws if it cannot encode
                changes.put(spec.path(), maskedVal);
                row.put("after", maskedVal);
            } catch (Exception e) {
                row.put("error", "value " + (maskedVal == null ? "(null)" : "'" + maskedVal + "'")
                        + " does not fit field type " + typeLabel(df.field()) + " — " + e.getMessage());
            }
            perField.add(row);
        }

        byte[] masked = codec.encodeOverlay(rv, original, changes);
        return Map.of("beforeHex", toHex(original), "afterHex", toHex(masked),
                "fields", perField, "bytesChanged", countDiff(original, masked));
    }

    // ----------------------------------------------------------------- helpers

    private static List<Map<String, Object>> decodedRows(RecordValue rv) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RecordValue.DecodedField f : rv.fields()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", f.path());
            m.put("offset", f.offset());
            m.put("length", f.length());
            m.put("type", typeLabel(f.field()));
            m.put("numeric", f.numeric());
            m.put("value", f.value());
            rows.add(m);
        }
        return rows;
    }

    private static String typeLabel(Field f) {
        String pic = f.rawPicture() == null ? "" : " " + f.rawPicture();
        return f.effectiveUsage() + pic;
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }

    private static int countDiff(byte[] a, byte[] b) {
        int n = 0, len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) if (a[i] != b[i]) n++;
        return n + Math.abs(a.length - b.length);
    }

    private static byte[] fromHex(String hex) {
        if (hex == null) throw new IllegalArgumentException("No record bytes supplied");
        String clean = hex.replaceAll("[^0-9A-Fa-f]", "");
        if (clean.length() % 2 != 0) throw new IllegalArgumentException("Hex must have an even number of digits");
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(clean.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    private static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format("%02X", b & 0xFF));
        return sb.toString();
    }
}
