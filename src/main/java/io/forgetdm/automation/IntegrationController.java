package io.forgetdm.automation;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/integrations")
public class IntegrationController {
    private final IntegrationWebhookService service;
    public IntegrationController(IntegrationWebhookService service) { this.service = service; }

    @GetMapping public List<Map<String, Object>> list() { return service.list(); }
    @GetMapping("/deliveries")
    public List<Map<String, Object>> deliveries(@RequestParam(required = false) String endpointId,
                                                @RequestParam(required = false) Integer limit) {
        return service.deliveries(endpointId, limit);
    }
    @PostMapping public Map<String, Object> create(@RequestBody IntegrationWebhookService.EndpointRequest request) { return service.save(null, request); }
    @PutMapping("/{id}") public Map<String, Object> update(@PathVariable String id, @RequestBody IntegrationWebhookService.EndpointRequest request) { return service.save(id, request); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable String id) { service.delete(id); }
    @PostMapping("/{id}/test") public Map<String, Object> test(@PathVariable String id) { return service.test(id); }
    @PostMapping("/deliveries/{id}/retry") public Map<String, Object> retry(@PathVariable String id) { return service.retry(id); }
}
