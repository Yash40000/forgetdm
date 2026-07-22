package io.forgetdm.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.filevault.ManagedFileVault;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.OwnershipGuard;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
public class MappingFileService {
    private static final Set<String> SUPPORTED = Set.of("CSV", "TSV", "JSON", "JSONL", "XML");
    private final MappingFileAssetRepository repo;
    private final ManagedFileVault vault;
    private final ObjectMapper json;
    private final AuditService audit;
    private final OwnershipGuard ownership;
    private final long maxUploadBytes;

    public MappingFileService(MappingFileAssetRepository repo, ManagedFileVault vault, ObjectMapper json,
                              AuditService audit, OwnershipGuard ownership,
                              @Value("${forgetdm.file-vault.max-upload-bytes:104857600}") long maxUploadBytes) {
        this.repo = repo; this.vault = vault; this.json = json; this.audit = audit; this.ownership = ownership;
        this.maxUploadBytes = maxUploadBytes;
    }

    public List<MappingFileAssetEntity> list() {
        return repo.findAllByOrderByCreatedAtDesc().stream()
                .filter(this::canSee)
                .toList();
    }

    public MappingFileAssetEntity get(Long id) {
        MappingFileAssetEntity asset = repo.findById(id)
                .orElseThrow(() -> ApiException.notFound("Mapping file asset " + id + " not found"));
        assertCanSee(asset);
        return asset;
    }

    public MappingFileAssetEntity upload(MultipartFile file, String name, String requestedFormat,
                                         String delimiter, boolean header) {
        if (file == null || file.isEmpty()) throw ApiException.bad("Choose a non-empty mapping file");
        if (file.getSize() > maxUploadBytes)
            throw ApiException.bad("File exceeds the managed upload limit of " + maxUploadBytes + " bytes");
        String original = safeFilename(file.getOriginalFilename());
        String format = detectFormat(original, requestedFormat);
        if (!SUPPORTED.contains(format))
            throw ApiException.bad("Mapping assets support CSV, TSV, JSON, JSONL, and XML. Use Unstructured Masking for documents or other files.");
        String actualDelimiter = delimiter == null || delimiter.isEmpty()
                ? ("TSV".equals(format) ? "\t" : ",") : delimiter.substring(0, 1);
        ManagedFileVault.Stored stored;
        try { stored = vault.store(file.getInputStream(), file.getSize()); }
        catch (Exception e) { throw e instanceof ApiException a ? a : ApiException.bad("Upload failed: " + e.getMessage()); }

        MappingFileAssetEntity asset = new MappingFileAssetEntity();
        asset.setName(name == null || name.isBlank() ? original : name.trim());
        asset.setFormat(format);
        asset.setOriginalFilename(original);
        asset.setContentType(file.getContentType());
        asset.setSizeBytes(stored.clearSize());
        asset.setSha256(stored.sha256());
        asset.setStorageKey(stored.storageKey());
        asset.setKeySalt(stored.keySalt());
        asset.setPayloadIv(stored.iv());
        try { asset.setOptionsJson(json.writeValueAsString(Map.of("delimiter", actualDelimiter, "header", header))); }
        catch (Exception e) { asset.setOptionsJson("{}"); }
        asset.setCreatedBy(actor());
        asset.setOwnerUserId(ownership.defaultOwnerUserId());
        asset.setOwnerUsername(ownership.defaultOwnerUsername());
        asset.setOwnerGroupId(ownership.defaultOwnerGroupId());
        asset.setVisibility(ownership.defaultVisibility());
        try {
            List<Map<String, Object>> rows = readRows(asset, 200);
            asset.setSchemaJson(json.writeValueAsString(inferSchema(rows)));
        } catch (Exception e) {
            vault.delete(stored.storageKey());
            throw e instanceof ApiException a ? a : ApiException.bad("Could not inspect mapping file: " + e.getMessage());
        }
        MappingFileAssetEntity saved = repo.save(asset);
        audit.record(actor(), "MAPPING_FILE_UPLOADED", "MAPPING", "MAPPING_FILE", String.valueOf(saved.getId()),
                saved.getName(), "SUCCESS", "format=" + format + " bytes=" + saved.getSizeBytes(), null);
        return saved;
    }

    public Map<String, Object> preview(Long id, int requestedLimit) {
        MappingFileAssetEntity asset = get(id);
        int limit = Math.max(1, Math.min(requestedLimit, 1000));
        List<Map<String, Object>> rows = readRows(asset, limit + 1);
        boolean truncated = rows.size() > limit;
        if (truncated) rows = new ArrayList<>(rows.subList(0, limit));
        List<String> columns = schemaNames(asset.getSchemaJson());
        if (columns.isEmpty() && !rows.isEmpty()) columns = new ArrayList<>(rows.get(0).keySet());
        return Map.of("asset", asset, "columns", columns, "rows", rows,
                "rowCount", rows.size(), "truncated", truncated);
    }

    public List<Map<String, Object>> readRows(MappingFileAssetEntity asset, int maxRows) {
        assertCanSee(asset);
        try (InputStream in = vault.open(asset.getStorageKey(), asset.getKeySalt(), asset.getPayloadIv())) {
            return switch (asset.getFormat()) {
                case "CSV", "TSV" -> readDelimited(in, asset, maxRows);
                case "JSON" -> readJson(in, maxRows);
                case "JSONL" -> readJsonLines(in, maxRows);
                case "XML" -> readXml(in, maxRows);
                default -> throw ApiException.bad("Unsupported mapping file format: " + asset.getFormat());
            };
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.bad("Could not read mapping file: " + e.getMessage()); }
    }

    @FunctionalInterface
    public interface RowHandler { void accept(LinkedHashMap<String, Object> row) throws Exception; }

    /** Stream delimited and JSONL assets without retaining rows in heap. Structured JSON/XML remain bounded. */
    public long streamRows(MappingFileAssetEntity asset, long maxRows, RowHandler handler) {
        assertCanSee(asset);
        try (InputStream in = vault.open(asset.getStorageKey(), asset.getKeySalt(), asset.getPayloadIv())) {
            if ("CSV".equals(asset.getFormat()) || "TSV".equals(asset.getFormat()))
                return streamDelimited(in, asset, maxRows, handler);
            if ("JSONL".equals(asset.getFormat())) return streamJsonLines(in, maxRows, handler);
            int bounded = maxRows > 0 ? (int) Math.min(maxRows, 200_000) : 200_001;
            List<Map<String, Object>> rows = readRows(asset, bounded);
            if (maxRows <= 0 && rows.size() > 200_000)
                throw ApiException.bad("Large JSON/XML mapping assets require a row limit or conversion to streaming JSONL/CSV");
            long count = 0;
            for (Map<String, Object> row : rows) { handler.accept(new LinkedHashMap<>(row)); count++; }
            return count;
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.bad("Could not stream mapping file: " + e.getMessage()); }
    }

    private long streamDelimited(InputStream in, MappingFileAssetEntity asset, long maxRows, RowHandler handler) throws Exception {
        JsonNode options = json.readTree(asset.getOptionsJson());
        char delimiter = options.path("delimiter").asText("CSV".equals(asset.getFormat()) ? "," : "\t").charAt(0);
        boolean header = options.path("header").asBoolean(true);
        CSVFormat.Builder builder = CSVFormat.DEFAULT.builder().setDelimiter(delimiter).setIgnoreEmptyLines(true).setTrim(false);
        if (header) builder.setHeader().setSkipHeaderRecord(true);
        long count = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)); CSVParser parser = builder.get().parse(reader)) {
            List<String> headers = header ? parser.getHeaderNames() : List.of();
            for (CSVRecord record : parser) {
                if (maxRows > 0 && count >= maxRows) break;
                LinkedHashMap<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < record.size(); i++) row.put(i < headers.size() && !headers.get(i).isBlank() ? headers.get(i) : "column_" + (i + 1), record.get(i));
                handler.accept(row); count++;
            }
        }
        return count;
    }

    private long streamJsonLines(InputStream in, long maxRows, RowHandler handler) throws Exception {
        long count = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (maxRows > 0 && count >= maxRows) break;
                if (line.isBlank()) continue;
                handler.accept(new LinkedHashMap<>(flattenJson(json.readTree(line)))); count++;
            }
        }
        return count;
    }

    public void delete(Long id) {
        MappingFileAssetEntity asset = get(id);
        vault.delete(asset.getStorageKey());
        repo.delete(asset);
        audit.log(actor(), "MAPPING_FILE_DELETED", asset.getName() + " id=" + id);
    }

    private boolean canSee(MappingFileAssetEntity asset) {
        return ownership.canSee(asset.getOwnerUserId(), asset.getOwnerGroupId(), asset.getVisibility());
    }

    private void assertCanSee(MappingFileAssetEntity asset) {
        if (asset == null) throw ApiException.notFound("Mapping file asset not found");
        ownership.assertCanSee("mapping file asset", asset.getId(), asset.getOwnerUserId(),
                asset.getOwnerGroupId(), asset.getVisibility());
    }

    private List<Map<String, Object>> readDelimited(InputStream in, MappingFileAssetEntity asset, int maxRows) throws Exception {
        JsonNode options = json.readTree(asset.getOptionsJson());
        char delimiter = options.path("delimiter").asText("CSV".equals(asset.getFormat()) ? "," : "\t").charAt(0);
        boolean header = options.path("header").asBoolean(true);
        CSVFormat.Builder builder = CSVFormat.DEFAULT.builder().setDelimiter(delimiter)
                .setIgnoreEmptyLines(true).setTrim(false);
        if (header) builder.setHeader().setSkipHeaderRecord(true);
        List<Map<String, Object>> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
             CSVParser parser = builder.get().parse(reader)) {
            List<String> headers = header ? parser.getHeaderNames() : List.of();
            for (CSVRecord record : parser) {
                if (rows.size() >= maxRows) break;
                LinkedHashMap<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < record.size(); i++) {
                    String column = i < headers.size() && !headers.get(i).isBlank() ? headers.get(i) : "column_" + (i + 1);
                    row.put(column, record.get(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private List<Map<String, Object>> readJson(InputStream in, int maxRows) throws Exception {
        JsonNode root = json.readTree(in);
        List<Map<String, Object>> rows = new ArrayList<>();
        if (root == null) return rows;
        if (root.isArray()) {
            for (JsonNode node : root) { if (rows.size() >= maxRows) break; rows.add(flattenJson(node)); }
        } else rows.add(flattenJson(root));
        return rows;
    }

    private List<Map<String, Object>> readJsonLines(InputStream in, int maxRows) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && rows.size() < maxRows) {
                if (!line.isBlank()) rows.add(flattenJson(json.readTree(line)));
            }
        }
        return rows;
    }

    private List<Map<String, Object>> readXml(InputStream in, int maxRows) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Document doc = f.newDocumentBuilder().parse(in);
        Element root = doc.getDocumentElement();
        List<Element> records = childElements(root);
        if (records.isEmpty()) records = List.of(root);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Element record : records) {
            if (rows.size() >= maxRows) break;
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            for (Element child : childElements(record)) row.put(child.getTagName(), child.getTextContent());
            if (row.isEmpty()) row.put(record.getTagName(), record.getTextContent());
            rows.add(row);
        }
        return rows;
    }

    private Map<String, Object> flattenJson(JsonNode node) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        if (node != null && node.isObject()) node.fields().forEachRemaining(e ->
                row.put(e.getKey(), e.getValue().isValueNode() ? json.convertValue(e.getValue(), Object.class) : e.getValue().toString()));
        else row.put("value", node == null || node.isNull() ? null : json.convertValue(node, Object.class));
        return row;
    }

    private List<Map<String, String>> inferSchema(List<Map<String, Object>> rows) {
        LinkedHashMap<String, String> types = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) for (Map.Entry<String, Object> e : row.entrySet())
            types.merge(e.getKey(), inferType(e.getValue()), MappingFileService::mergeType);
        return types.entrySet().stream().map(e -> Map.of("name", e.getKey(), "type", e.getValue())).toList();
    }

    private List<String> schemaNames(String schemaJson) {
        try {
            List<String> names = new ArrayList<>();
            for (JsonNode n : json.readTree(schemaJson)) names.add(n.path("name").asText());
            return names;
        } catch (Exception e) { return List.of(); }
    }

    private static String inferType(Object value) {
        if (value == null) return "UNKNOWN";
        if (value instanceof Number) return "NUMBER";
        if (value instanceof Boolean) return "BOOLEAN";
        String s = String.valueOf(value).trim();
        if (s.matches("[-+]?\\d+(\\.\\d+)?")) return "NUMBER";
        if ("true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s)) return "BOOLEAN";
        try { Instant.parse(s); return "TIMESTAMP"; } catch (DateTimeParseException ignore) { }
        if (s.matches("\\d{4}-\\d{2}-\\d{2}")) return "DATE";
        return "STRING";
    }

    private static String mergeType(String left, String right) {
        if (left.equals(right)) return left;
        if ("UNKNOWN".equals(left)) return right;
        if ("UNKNOWN".equals(right)) return left;
        return "STRING";
    }

    private static List<Element> childElements(Element parent) {
        List<Element> out = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) if (nodes.item(i).getNodeType() == Node.ELEMENT_NODE)
            out.add((Element) nodes.item(i));
        return out;
    }

    private static String detectFormat(String filename, String requested) {
        if (requested != null && !requested.isBlank() && !"AUTO".equalsIgnoreCase(requested))
            return requested.trim().toUpperCase(Locale.ROOT);
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".tsv")) return "TSV";
        if (lower.endsWith(".jsonl") || lower.endsWith(".ndjson")) return "JSONL";
        if (lower.endsWith(".json")) return "JSON";
        if (lower.endsWith(".xml")) return "XML";
        return "CSV";
    }

    private static String safeFilename(String filename) {
        String value = filename == null ? "mapping-file" : filename.replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1).replaceAll("[\\r\\n\\t]", "_");
        return value.isBlank() ? "mapping-file" : value.substring(0, Math.min(value.length(), 500));
    }

    private static String actor() { return AccessContext.current().map(p -> p.username()).orElse("system"); }
}
