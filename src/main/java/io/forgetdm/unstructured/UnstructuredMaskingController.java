package io.forgetdm.unstructured;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/unstructured")
public class UnstructuredMaskingController {
    private final UnstructuredMaskingService service;
    public UnstructuredMaskingController(UnstructuredMaskingService service) { this.service = service; }

    @GetMapping("/capabilities") public Map<String, Object> capabilities() { return service.capabilities(); }
    @GetMapping("/profiles") public List<UnstructuredProfileEntity> profiles() { return service.listProfiles(); }
    @GetMapping("/profiles/{id}") public UnstructuredProfileEntity profile(@PathVariable Long id) { return service.getProfile(id); }
    @PostMapping("/profiles") public UnstructuredProfileEntity saveProfile(@RequestBody UnstructuredProfileEntity body) { return service.saveProfile(body); }
    @DeleteMapping("/profiles/{id}") public void deleteProfile(@PathVariable Long id) { service.deleteProfile(id); }

    public record PreviewRequest(Long profileId, String text, String seed) {}
    @PostMapping("/preview") public Map<String, Object> preview(@RequestBody PreviewRequest request) {
        return service.preview(request.profileId(), request.text(), request.seed());
    }

    @PostMapping(value = "/jobs", consumes = "multipart/form-data")
    public UnstructuredJobEntity start(@RequestPart("file") MultipartFile file,
                                       @RequestParam Long profileId,
                                       @RequestParam(required = false) String seed) {
        return service.start(profileId, file, seed);
    }
    @GetMapping("/jobs") public List<UnstructuredJobEntity> jobs() { return service.listJobs(); }
    @GetMapping("/jobs/{id}") public UnstructuredJobEntity job(@PathVariable Long id) { return service.getJob(id); }
    @PostMapping("/jobs/{id}/cancel") public UnstructuredJobEntity cancel(@PathVariable Long id) { return service.cancel(id); }
    @DeleteMapping("/jobs/{id}") public void deleteJob(@PathVariable Long id) { service.deleteJob(id); }

    @GetMapping("/jobs/{id}/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable Long id) {
        UnstructuredMaskingService.Download file = service.output(id);
        StreamingResponseBody body = output -> { try (var in = file.stream()) { in.transferTo(output); } };
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + file.filename().replace("\"", "") + "\"")
                .contentType(MediaType.parseMediaType(file.contentType())).body(body);
    }
}
