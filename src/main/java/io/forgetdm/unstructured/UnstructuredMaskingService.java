package io.forgetdm.unstructured;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.core.mask.MaskContext;
import io.forgetdm.core.mask.MaskFunction;
import io.forgetdm.core.mask.MaskingEngine;
import io.forgetdm.core.util.PiiPatterns;
import io.forgetdm.filevault.ManagedFileVault;
import io.forgetdm.security.AccessContext;
import jakarta.annotation.PreDestroy;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.TextNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class UnstructuredMaskingService {
    private static final int PREVIEW_CAP = 12_000;
    private final UnstructuredProfileRepository profiles;
    private final UnstructuredJobRepository jobs;
    private final ManagedFileVault vault;
    private final MaskingEngine masking;
    private final ObjectMapper json;
    private final AuditService audit;
    private final long maxUploadBytes;
    private final int maxExtractedChars;
    private final long retentionHours;
    private final ExecutorService executor = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "forgetdm-unstructured-mask"); t.setDaemon(true); return t;
    });

    public UnstructuredMaskingService(UnstructuredProfileRepository profiles, UnstructuredJobRepository jobs,
                                      ManagedFileVault vault, MaskingEngine masking, ObjectMapper json,
                                      AuditService audit,
                                      @Value("${forgetdm.file-vault.max-upload-bytes:104857600}") long maxUploadBytes,
                                      @Value("${forgetdm.file-vault.max-extracted-chars:25000000}") int maxExtractedChars,
                                      @Value("${forgetdm.file-vault.output-retention-hours:72}") long retentionHours) {
        this.profiles = profiles; this.jobs = jobs; this.vault = vault; this.masking = masking;
        this.json = json; this.audit = audit; this.maxUploadBytes = maxUploadBytes;
        this.maxExtractedChars = maxExtractedChars; this.retentionHours = retentionHours;
    }

    @PreDestroy void shutdown() { executor.shutdownNow(); }

    public List<UnstructuredProfileEntity> listProfiles() {
        ensureDefaultProfile();
        List<UnstructuredProfileEntity> out = profiles.findAll();
        out.sort(Comparator.comparing(UnstructuredProfileEntity::getName, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    public UnstructuredProfileEntity getProfile(Long id) {
        return profiles.findById(id).orElseThrow(() -> ApiException.notFound("Unstructured masking profile " + id + " not found"));
    }

    @Transactional
    public UnstructuredProfileEntity saveProfile(UnstructuredProfileEntity input) {
        if (input == null || input.getName() == null || input.getName().isBlank()) throw ApiException.bad("Profile name is required");
        List<Rule> parsed = parseRules(input.getRulesJson());
        if (parsed.isEmpty()) throw ApiException.bad("Add at least one masking rule");
        validateRules(parsed);
        UnstructuredProfileEntity target = input.getId() == null
                ? profiles.findByNameIgnoreCase(input.getName().trim()).orElseGet(UnstructuredProfileEntity::new)
                : getProfile(input.getId());
        boolean update = target.getId() != null;
        target.setName(input.getName().trim()); target.setDescription(input.getDescription());
        target.setRulesJson(canonicalRules(parsed));
        target.setStatus(normalizeStatus(input.getStatus()));
        target.setCreatedBy(target.getCreatedBy() == null ? actor() : target.getCreatedBy());
        target.setUpdatedAt(Instant.now()); if (update) target.setVersionNo(target.getVersionNo() + 1);
        UnstructuredProfileEntity saved = profiles.save(target);
        audit.record(actor(), update ? "UNSTRUCTURED_PROFILE_UPDATED" : "UNSTRUCTURED_PROFILE_CREATED", "MASKING",
                "UNSTRUCTURED_PROFILE", String.valueOf(saved.getId()), saved.getName(), "SUCCESS",
                "version=" + saved.getVersionNo() + " rules=" + parsed.size(), null);
        return saved;
    }

    public void deleteProfile(Long id) {
        UnstructuredProfileEntity profile = getProfile(id);
        profiles.delete(profile); audit.log(actor(), "UNSTRUCTURED_PROFILE_DELETED", profile.getName());
    }

    public Map<String, Object> preview(Long profileId, String text, String seed) {
        if (text == null || text.isBlank()) throw ApiException.bad("Enter sample text to preview");
        if (text.length() > PREVIEW_CAP) throw ApiException.bad("Preview text is limited to " + PREVIEW_CAP + " characters");
        UnstructuredProfileEntity profile = getProfile(profileId);
        MaskResult result = maskText(text, "preview", parseRules(profile.getRulesJson()), masking.withSeed(seed), new LinkedHashMap<>());
        return Map.of("original", text, "masked", result.text(), "findingsCount", result.count(), "findings", result.findings());
    }

    @Transactional
    public UnstructuredJobEntity start(Long profileId, MultipartFile file, String seed) {
        UnstructuredProfileEntity profile = getProfile(profileId);
        if (!"ACTIVE".equals(profile.getStatus())) throw ApiException.bad("Activate the masking profile before running it");
        if (file == null || file.isEmpty()) throw ApiException.bad("Choose a non-empty file");
        if (file.getSize() > maxUploadBytes) throw ApiException.bad("File exceeds the managed upload limit of " + maxUploadBytes + " bytes");
        ManagedFileVault.Stored stored;
        try { stored = vault.store(file.getInputStream(), file.getSize()); }
        catch (Exception e) { throw e instanceof ApiException a ? a : ApiException.bad("Upload failed: " + e.getMessage()); }
        UnstructuredJobEntity job = new UnstructuredJobEntity();
        job.setProfileId(profileId); job.setProfileVersion(profile.getVersionNo());
        job.setOriginalFilename(safeFilename(file.getOriginalFilename())); job.setBytesRead(file.getSize());
        job.setSourceSha256(stored.sha256()); job.setSourceStorageKey(stored.storageKey());
        job.setSourceKeySalt(stored.keySalt()); job.setSourceIv(stored.iv()); job.setCreatedBy(actor());
        job.setMessage("Encrypted upload accepted; waiting for an available worker");
        job.setFindingsJson("{}");
        UnstructuredJobEntity saved = jobs.save(job);
        Long id = saved.getId(); String effectiveSeed = seed == null ? "" : seed;
        audit.record(actor(), "UNSTRUCTURED_JOB_QUEUED", "MASKING", "UNSTRUCTURED_JOB", String.valueOf(id),
                saved.getOriginalFilename(), "SUCCESS", "profile=" + profile.getName() + " sha256=" + stored.sha256(), null);
        executor.submit(() -> process(id, effectiveSeed));
        return saved;
    }

    public List<UnstructuredJobEntity> listJobs() {
        List<UnstructuredJobEntity> all = jobs.findTop100ByOrderByCreatedAtDesc();
        return AccessContext.current().filter(p -> !p.hasPermission("unstructured.manage") && !p.roles().contains("AUDITOR"))
                .map(p -> all.stream().filter(job -> p.username().equalsIgnoreCase(job.getCreatedBy())).toList()).orElse(all);
    }
    public UnstructuredJobEntity getJob(Long id) {
        UnstructuredJobEntity job = jobs.findById(id).orElseThrow(() -> ApiException.notFound("Unstructured masking job " + id + " not found"));
        AccessContext.current().ifPresent(p -> {
            if (!p.username().equalsIgnoreCase(job.getCreatedBy()) && !p.hasPermission("unstructured.manage") && !p.roles().contains("AUDITOR"))
                throw ApiException.forbidden("This unstructured masking job belongs to another user");
        });
        return job;
    }

    @Transactional
    public UnstructuredJobEntity cancel(Long id) {
        UnstructuredJobEntity job = getJob(id);
        requirePayloadAccess(job);
        if (Set.of("COMPLETED", "FAILED", "CANCELED").contains(job.getStatus())) return job;
        job.setCancelRequested(true); job.setMessage("Cancellation requested; the current extraction stage will stop at its safe boundary");
        return jobs.save(job);
    }

    public Download output(Long id) {
        UnstructuredJobEntity job = getJob(id);
        requirePayloadAccess(job);
        if (!"COMPLETED".equals(job.getStatus()) || job.getOutputStorageKey() == null)
            throw ApiException.bad("This job has no completed masked output");
        return new Download(job.getOutputName(), contentType(job.getOutputName()),
                vault.open(job.getOutputStorageKey(), job.getOutputKeySalt(), job.getOutputIv()));
    }
    public record Download(String filename, String contentType, InputStream stream) {}

    @Transactional
    public void deleteJob(Long id) {
        UnstructuredJobEntity job = getJob(id);
        requirePayloadAccess(job);
        if ("RUNNING".equals(job.getStatus()) || "QUEUED".equals(job.getStatus())) throw ApiException.bad("Cancel the job before deleting it");
        vault.delete(job.getSourceStorageKey()); vault.delete(job.getOutputStorageKey()); jobs.delete(job);
        audit.log(actor(), "UNSTRUCTURED_JOB_DELETED", "job=" + id);
    }

    private void process(Long id, String seed) {
        UnstructuredJobEntity job = getJob(id);
        try {
            update(job, "RUNNING", "DETECT", 5, "Detecting content type without trusting the filename");
            UnstructuredProfileEntity profile = getProfile(job.getProfileId());
            List<Rule> rules = parseRules(profile.getRulesJson());
            String mediaType;
            try (InputStream in = source(job)) { mediaType = new Tika().detect(in, job.getOriginalFilename()); }
            job = getJob(id); job.setDetectedFormat(mediaType); jobs.save(job);
            checkCanceled(id);

            update(job, "RUNNING", "EXTRACT", 18, "Reading text through a bounded, fail-closed format adapter");
            Processed processed = processFormat(job, mediaType, rules, masking.withSeed(seed));
            checkCanceled(id);
            update(job, "RUNNING", "WRITE", 86, "Writing only masked content to encrypted managed storage");
            ManagedFileVault.OutputHandle output = vault.createOutput();
            try (output) { output.stream().write(processed.bytes()); }
            job = getJob(id); job.setOutputStorageKey(output.storageKey()); job.setOutputKeySalt(output.keySalt()); job.setOutputIv(output.iv());
            job.setOutputSha256(output.sha256()); job.setOutputName(maskedName(job.getOriginalFilename(), processed.extension()));
            job.setOutputStrategy(processed.strategy()); job.setFindingsCount(processed.findings().values().stream().mapToLong(Long::longValue).sum());
            job.setFindingsJson(json.writeValueAsString(processed.findings())); job.setCharsProcessed(processed.charsProcessed());
            job.setStatus("COMPLETED"); job.setStage("COMPLETED"); job.setProgress(100); job.setMessage("Masked output is ready; the encrypted source copy was destroyed");
            job.setFinishedAt(Instant.now()); jobs.save(job); vault.delete(job.getSourceStorageKey()); clearSource(job);
            audit.record(job.getCreatedBy(), "UNSTRUCTURED_JOB_COMPLETED", "MASKING", "UNSTRUCTURED_JOB", String.valueOf(id),
                    job.getOriginalFilename(), "SUCCESS", "findings=" + job.getFindingsCount() + " strategy=" + job.getOutputStrategy(), job.getFindingsJson());
        } catch (Canceled e) {
            job = getJob(id); job.setStatus("CANCELED"); job.setStage("CANCELED"); job.setMessage("Canceled; encrypted source and incomplete output were destroyed");
            job.setFinishedAt(Instant.now()); jobs.save(job); vault.delete(job.getSourceStorageKey()); vault.delete(job.getOutputStorageKey()); clearSource(job);
        } catch (Exception e) {
            job = getJob(id); job.setStatus("FAILED"); job.setStage("FAILED"); job.setProgress(Math.min(job.getProgress(), 99));
            job.setMessage("Masking failed closed; no unmasked output was released"); job.setErrorMessage(safeError(e)); job.setFinishedAt(Instant.now()); jobs.save(job);
            vault.delete(job.getSourceStorageKey()); vault.delete(job.getOutputStorageKey()); clearSource(job);
            audit.record(job.getCreatedBy(), "UNSTRUCTURED_JOB_FAILED", "MASKING", "UNSTRUCTURED_JOB", String.valueOf(id),
                    job.getOriginalFilename(), "FAILURE", safeError(e), null);
        }
    }

    private Processed processFormat(UnstructuredJobEntity job, String mediaType, List<Rule> rules, MaskingEngine engine) throws Exception {
        String lowerName = job.getOriginalFilename().toLowerCase(Locale.ROOT);
        Map<String, Long> findings = new LinkedHashMap<>();
        if (isJson(mediaType, lowerName)) {
            JsonNode root; try (InputStream in = source(job)) { root = json.readTree(in); }
            maskJson(root, "$", rules, engine, findings, new long[]{0});
            byte[] bytes = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
            return new Processed(bytes, "json", "NATIVE_JSON", bytes.length, findings);
        }
        if (isCsv(mediaType, lowerName)) return processCsv(job, rules, engine, findings, lowerName.endsWith(".tsv") ? '\t' : ',');
        if (isXml(mediaType, lowerName)) return processXml(job, rules, engine, findings);
        if (isHtml(mediaType, lowerName)) return processHtml(job, rules, engine, findings);
        if (mediaType.startsWith("text/") || lowerName.endsWith(".log") || lowerName.endsWith(".txt")) {
            String text; try (InputStream in = source(job)) { text = readBounded(in); }
            MaskResult result = maskText(text, "$", rules, engine, findings);
            return new Processed(result.text().getBytes(StandardCharsets.UTF_8), extension(lowerName, "txt"), "NATIVE_TEXT", text.length(), findings);
        }
        update(job, "RUNNING", "EXTRACT", 32, "Extracting text from " + mediaType + " and rebuilding a safe text-only output");
        String extracted = extractWithTika(job);
        if (extracted.isBlank()) throw ApiException.bad("No maskable text could be extracted. Image-only documents require an approved OCR service; the original file was not copied.");
        MaskResult result = maskText(extracted, "$", rules, engine, findings);
        return new Processed(result.text().getBytes(StandardCharsets.UTF_8), "txt", "SAFE_TEXT_REBUILD", extracted.length(), findings);
    }

    private Processed processCsv(UnstructuredJobEntity job, List<Rule> rules, MaskingEngine engine,
                                 Map<String, Long> findings, char delimiter) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); long chars = 0; long row = 0;
        CSVFormat format = CSVFormat.DEFAULT.builder().setDelimiter(delimiter).setHeader().setSkipHeaderRecord(true).get();
        try (InputStream in = source(job); Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
             CSVParser parser = format.parse(reader); Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
             CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.builder().setDelimiter(delimiter).setHeader(parser.getHeaderNames().toArray(String[]::new)).get())) {
            List<String> headers = parser.getHeaderNames();
            for (CSVRecord record : parser) {
                checkCanceled(job.getId()); List<String> values = new ArrayList<>(); row++;
                for (int i = 0; i < record.size(); i++) {
                    String selector = i < headers.size() ? headers.get(i) : "column_" + (i + 1);
                    String value = record.get(i); chars += value.length();
                    values.add(maskText(value, selector, rules, engine, findings).text());
                }
                printer.printRecord(values);
                if (row % 500 == 0) update(job, "RUNNING", "MASK", Math.min(80, 35 + (int) Math.log10(row + 1) * 12), "Masked " + row + " delimited records");
            }
        }
        return new Processed(out.toByteArray(), delimiter == '\t' ? "tsv" : "csv", "NATIVE_DELIMITED", chars, findings);
    }

    private Processed processXml(UnstructuredJobEntity job, List<Rule> rules, MaskingEngine engine, Map<String, Long> findings) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance(); f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false); f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        org.w3c.dom.Document doc; try (InputStream in = source(job)) { doc = f.newDocumentBuilder().parse(in); }
        long[] chars = {0}; maskXmlNode(doc.getDocumentElement(), "$", rules, engine, findings, chars);
        ByteArrayOutputStream out = new ByteArrayOutputStream(); TransformerFactory tf = TransformerFactory.newInstance();
        tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        var transformer = tf.newTransformer(); transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.transform(new DOMSource(doc), new StreamResult(out));
        return new Processed(out.toByteArray(), "xml", "NATIVE_XML", chars[0], findings);
    }

    private Processed processHtml(UnstructuredJobEntity job, List<Rule> rules, MaskingEngine engine, Map<String, Long> findings) throws Exception {
        String html; try (InputStream in = source(job)) { html = readBounded(in); }
        Document doc = Jsoup.parse(html); long[] chars = {0};
        for (org.jsoup.nodes.Element element : doc.getAllElements()) for (TextNode node : element.textNodes()) {
            if (node.parent() != null && Set.of("script", "style").contains(node.parent().normalName())) continue;
            String value = node.getWholeText(); chars[0] += value.length();
            node.text(maskText(value, node.parent() == null ? "$" : node.parent().normalName(), rules, engine, findings).text());
        }
        return new Processed(doc.outerHtml().getBytes(StandardCharsets.UTF_8), "html", "NATIVE_HTML", chars[0], findings);
    }

    private void maskJson(JsonNode node, String path, List<Rule> rules, MaskingEngine engine, Map<String, Long> findings, long[] chars) {
        if (node == null) return;
        if (node.isObject()) node.fields().forEachRemaining(e -> {
            JsonNode value = e.getValue(); String child = path + "." + e.getKey();
            if (value.isTextual()) { String text = value.asText(); chars[0] += text.length(); ((com.fasterxml.jackson.databind.node.ObjectNode) node).put(e.getKey(), maskText(text, child, rules, engine, findings).text()); }
            else maskJson(value, child, rules, engine, findings, chars);
        });
        else if (node.isArray()) for (int i = 0; i < node.size(); i++) {
            JsonNode value = node.get(i); if (value.isTextual()) { String text = value.asText(); chars[0] += text.length(); ((com.fasterxml.jackson.databind.node.ArrayNode) node).set(i, json.getNodeFactory().textNode(maskText(text, path + "[]", rules, engine, findings).text())); }
            else maskJson(value, path + "[]", rules, engine, findings, chars);
        }
    }

    private void maskXmlNode(Node node, String path, List<Rule> rules, MaskingEngine engine, Map<String, Long> findings, long[] chars) {
        if (node.getNodeType() == Node.ELEMENT_NODE) {
            Element el = (Element) node; String next = path + "." + el.getTagName();
            for (int i = 0; i < el.getAttributes().getLength(); i++) {
                Node attr = el.getAttributes().item(i); String text = attr.getNodeValue(); chars[0] += text.length();
                attr.setNodeValue(maskText(text, next + "@" + attr.getNodeName(), rules, engine, findings).text());
            }
            Node child = el.getFirstChild(); while (child != null) { Node nextChild = child.getNextSibling(); maskXmlNode(child, next, rules, engine, findings, chars); child = nextChild; }
        } else if (node.getNodeType() == Node.TEXT_NODE) {
            String text = node.getNodeValue(); chars[0] += text.length(); node.setNodeValue(maskText(text, path, rules, engine, findings).text());
        }
    }

    private MaskResult maskText(String text, String selector, List<Rule> rules, MaskingEngine engine, Map<String, Long> findings) {
        String current = text == null ? "" : text; long total = 0; long rowIndex = 0;
        for (Rule rule : rules) {
            if (!rule.enabled() || !selectorMatches(rule.selector(), selector)) continue;
            Matcher matcher = Pattern.compile(rule.pattern(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(current);
            StringBuffer out = new StringBuffer(); long count = 0;
            while (matcher.find()) {
                String value = matcher.group();
                if ("CREDIT_CARD".equals(rule.piiType()) && !PiiPatterns.looksLikeCard(value)) continue;
                String replacement = engine.mask(parseFunction(rule.function()), "unstructured." + rule.piiType().toLowerCase(Locale.ROOT),
                        value, blankToNull(rule.param1()), blankToNull(rule.param2()), new MaskContext(++rowIndex));
                matcher.appendReplacement(out, Matcher.quoteReplacement(replacement == null ? "" : replacement)); count++;
            }
            matcher.appendTail(out); current = out.toString();
            if (count > 0) findings.merge(rule.piiType(), count, Long::sum);
            total += count;
        }
        return new MaskResult(current, total, new LinkedHashMap<>(findings));
    }

    private String extractWithTika(UnstructuredJobEntity job) throws Exception {
        BodyContentHandler handler = new BodyContentHandler(maxExtractedChars);
        Metadata metadata = new Metadata(); metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, job.getOriginalFilename());
        try (InputStream in = source(job)) { new AutoDetectParser().parse(in, handler, metadata, new ParseContext()); }
        catch (org.xml.sax.SAXException e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase(Locale.ROOT).contains("write limit"))
                throw ApiException.bad("Extracted text exceeds the configured safety limit; split the document or raise the reviewed limit");
            throw e;
        }
        return handler.toString();
    }

    private InputStream source(UnstructuredJobEntity job) { return vault.open(job.getSourceStorageKey(), job.getSourceKeySalt(), job.getSourceIv()); }
    private String readBounded(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n; long total = 0;
        while ((n = in.read(buf)) >= 0) { total += n; if (total > maxExtractedChars * 4L) throw ApiException.bad("Text file exceeds the configured extraction safety limit"); out.write(buf, 0, n); }
        String text = out.toString(StandardCharsets.UTF_8); if (text.length() > maxExtractedChars) throw ApiException.bad("Text exceeds the configured extraction safety limit"); return text;
    }

    private List<Rule> parseRules(String rulesJson) {
        try {
            JsonNode root = json.readTree(rulesJson == null || rulesJson.isBlank() ? "[]" : rulesJson);
            if (!root.isArray()) throw ApiException.bad("rulesJson must be an array");
            List<Rule> out = new ArrayList<>();
            for (JsonNode r : root) out.add(new Rule(r.path("name").asText(r.path("piiType").asText("Rule")),
                    r.path("piiType").asText("CUSTOM").toUpperCase(Locale.ROOT), r.path("pattern").asText(),
                    r.path("function").asText("REDACT").toUpperCase(Locale.ROOT), r.path("param1").asText(""),
                    r.path("param2").asText(""), r.path("selector").asText(""), r.path("enabled").asBoolean(true)));
            return out;
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.bad("Profile rules are not valid JSON: " + e.getMessage()); }
    }

    private void validateRules(List<Rule> rules) {
        for (Rule rule : rules) {
            if (rule.pattern() == null || rule.pattern().isBlank()) throw ApiException.bad(rule.name() + " needs a regular expression");
            if (rule.pattern().length() > 1000) throw ApiException.bad(rule.name() + " pattern is too long");
            if (rule.pattern().matches(".*(\\+|\\*|\\{[^}]+})\\)[+*].*")) throw ApiException.bad(rule.name() + " contains a nested quantifier that could stall document processing");
            try { Pattern.compile(rule.pattern()); } catch (Exception e) { throw ApiException.bad(rule.name() + " has an invalid pattern: " + e.getMessage()); }
            parseFunction(rule.function());
        }
    }

    private String canonicalRules(List<Rule> rules) { try { return json.writeValueAsString(rules); } catch (Exception e) { throw ApiException.bad("Could not save profile rules"); } }

    private synchronized void ensureDefaultProfile() {
        if (profiles.count() > 0) return;
        UnstructuredProfileEntity profile = new UnstructuredProfileEntity(); profile.setName("Enterprise PII baseline");
        profile.setDescription("Format-aware deterministic masking for common personal, payment, banking, and network identifiers.");
        profile.setStatus("ACTIVE"); profile.setCreatedBy("system"); profile.setRulesJson(canonicalRules(defaultRules())); profiles.save(profile);
    }

    private static List<Rule> defaultRules() {
        return List.of(
                new Rule("Email address", "EMAIL", "(?<![A-Z0-9._%+-])[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}(?![A-Z0-9])", "EMAIL", "", "", "", true),
                new Rule("US Social Security number", "SSN", "(?<!\\d)\\d{3}-\\d{2}-\\d{4}(?!\\d)", "SSN", "", "", "", true),
                new Rule("Payment card", "CREDIT_CARD", "(?<!\\d)(?:\\d[ -]?){12,19}(?!\\d)", "CREDIT_CARD", "", "", "", true),
                new Rule("IBAN", "IBAN", "(?<![A-Z0-9])[A-Z]{2}\\d{2}[A-Z0-9]{10,30}(?![A-Z0-9])", "IBAN", "", "", "", true),
                new Rule("IPv4 address", "IP_ADDRESS", "(?<!\\d)(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}(?!\\d)", "IP_ADDRESS", "", "", "", true),
                new Rule("MAC address", "MAC_ADDRESS", "(?<![0-9A-F])(?:[0-9A-F]{2}[:-]){5}[0-9A-F]{2}(?![0-9A-F])", "MAC_ADDRESS", "", "", "", true),
                new Rule("Phone number", "PHONE", "(?<!\\w)(?:\\+?1[ .-]?)?\\(?[2-9]\\d{2}\\)?[ .-]?[2-9]\\d{2}[ .-]?\\d{4}(?!\\w)", "PHONE", "", "", "", true)
        );
    }

    private void update(UnstructuredJobEntity job, String status, String stage, int progress, String message) {
        UnstructuredJobEntity current = getJob(job.getId()); current.setStatus(status); current.setStage(stage);
        current.setProgress(progress); current.setMessage(message); if (current.getStartedAt() == null) current.setStartedAt(Instant.now()); jobs.save(current);
    }
    private void checkCanceled(Long id) { if (getJob(id).isCancelRequested()) throw new Canceled(); }
    private void clearSource(UnstructuredJobEntity job) { job.setSourceStorageKey(null); job.setSourceKeySalt(null); job.setSourceIv(null); jobs.save(job); }

    @Scheduled(cron = "0 17 * * * *")
    public void purgeExpiredOutputs() {
        Instant cutoff = Instant.now().minus(Math.max(1, retentionHours), ChronoUnit.HOURS);
        for (UnstructuredJobEntity job : jobs.findByFinishedAtBefore(cutoff)) if (job.getOutputStorageKey() != null) {
            vault.delete(job.getOutputStorageKey()); job.setOutputStorageKey(null); job.setOutputKeySalt(null); job.setOutputIv(null);
            job.setMessage("Masked output expired under the configured retention policy; audit evidence remains"); jobs.save(job);
        }
    }

    public Map<String, Object> capabilities() {
        return Map.of(
                "nativePreserving", List.of("TXT", "LOG", "CSV", "TSV", "JSON", "XML", "HTML"),
                "safeTextRebuild", List.of("PDF", "DOC/DOCX", "XLS/XLSX", "PPT/PPTX", "RTF", "EPUB", "email", "archives and other Tika-readable documents"),
                "blockedWithoutExtractor", List.of("image-only documents without approved OCR", "encrypted/password-protected documents", "audio/video without approved transcription"),
                "guarantee", "Unsupported content fails closed. ForgeTDM never labels an unchanged binary as masked.");
    }

    private static boolean selectorMatches(String configured, String actual) { return configured == null || configured.isBlank() || actual.toLowerCase(Locale.ROOT).contains(configured.toLowerCase(Locale.ROOT)); }
    private static MaskFunction parseFunction(String value) { try { return MaskFunction.valueOf(value.toUpperCase(Locale.ROOT)); } catch (Exception e) { throw ApiException.bad("Unknown masking function: " + value); } }
    private static String normalizeStatus(String value) { String s = value == null ? "DRAFT" : value.toUpperCase(Locale.ROOT); if (!Set.of("DRAFT", "ACTIVE", "RETIRED").contains(s)) throw ApiException.bad("Profile status must be DRAFT, ACTIVE, or RETIRED"); return s; }
    private static boolean isJson(String media, String name) { return media.contains("json") || name.endsWith(".json"); }
    private static boolean isCsv(String media, String name) { return media.contains("csv") || media.contains("tab-separated") || name.endsWith(".csv") || name.endsWith(".tsv"); }
    private static boolean isXml(String media, String name) { return (media.contains("xml") && !media.contains("html")) || name.endsWith(".xml"); }
    private static boolean isHtml(String media, String name) { return media.contains("html") || name.endsWith(".html") || name.endsWith(".htm"); }
    private static String extension(String name, String fallback) { int dot = name.lastIndexOf('.'); return dot >= 0 && dot + 1 < name.length() ? name.substring(dot + 1).replaceAll("[^a-z0-9]", "") : fallback; }
    private static String maskedName(String original, String extension) { String base = original.replaceAll("(?i)\\.[a-z0-9]{1,8}$", "").replaceAll("[^A-Za-z0-9._-]", "_"); return base + ".masked." + extension; }
    private static String contentType(String name) { String n = name == null ? "" : name.toLowerCase(Locale.ROOT); if (n.endsWith(".json")) return "application/json"; if (n.endsWith(".xml")) return "application/xml"; if (n.endsWith(".html")) return "text/html"; if (n.endsWith(".csv")) return "text/csv"; return "text/plain"; }
    private static String safeFilename(String filename) { String s = filename == null ? "document" : filename.replace('\\', '/'); s = s.substring(s.lastIndexOf('/') + 1).replaceAll("[\\r\\n\\t]", "_"); return s.isBlank() ? "document" : s.substring(0, Math.min(500, s.length())); }
    private static String blankToNull(String v) { return v == null || v.isBlank() ? null : v; }
    private static String safeError(Exception e) { String s = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); return s.length() > 4000 ? s.substring(0, 4000) : s; }
    private static String actor() { return AccessContext.current().map(p -> p.username()).orElse("system"); }
    private static void requirePayloadAccess(UnstructuredJobEntity job) {
        AccessContext.current().ifPresent(p -> {
            if (!p.username().equalsIgnoreCase(job.getCreatedBy()) && !p.hasPermission("unstructured.manage"))
                throw ApiException.forbidden("Only the job owner or an unstructured masking manager can access this output");
        });
    }

    public record Rule(String name, String piiType, String pattern, String function, String param1, String param2, String selector, boolean enabled) {}
    private record MaskResult(String text, long count, Map<String, Long> findings) {}
    private record Processed(byte[] bytes, String extension, String strategy, long charsProcessed, Map<String, Long> findings) {}
    private static final class Canceled extends RuntimeException {}
}
