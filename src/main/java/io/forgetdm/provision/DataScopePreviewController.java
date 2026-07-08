package io.forgetdm.provision;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Column Map masked-value preview. Mapped under /api/datasets so the AccessControlFilter applies
 * datascope.manage (POST) — the same permission column-map editing already requires. Lives in the
 * provision package for access to the job engine's salt convention.
 */
@RestController
@RequestMapping("/api/datasets")
public class DataScopePreviewController {

    private final DataScopeMaskPreviewService preview;

    public DataScopePreviewController(DataScopeMaskPreviewService preview) {
        this.preview = preview;
    }

    @PostMapping("/{id}/preview-mask")
    public Map<String, Object> previewMask(@PathVariable Long id,
                                           @RequestBody DataScopeMaskPreviewService.PreviewRequest body) {
        return preview.preview(id, body);
    }
}
