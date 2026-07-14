package io.forgetdm.automation;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/self-service")
public class SelfServiceController {
    private final SelfServiceService service;
    public SelfServiceController(SelfServiceService service) { this.service = service; }

    @GetMapping("/catalog") public List<Map<String, Object>> catalog() { return service.catalog(); }
    @PutMapping("/templates/{id}") public Map<String, Object> publish(@PathVariable String id, @RequestBody SelfServiceService.PublishRequest request) { return service.publish(id, request); }
    @GetMapping("/requests") public List<Map<String, Object>> requests() { return service.list(); }
    @GetMapping("/requests/{id}") public Map<String, Object> request(@PathVariable String id) { return service.get(id); }
    @PostMapping("/requests") public Map<String, Object> create(@RequestBody SelfServiceService.RequestData request) { return service.request(request); }
    @PostMapping("/requests/{id}/decision/approve") public Map<String, Object> approve(@PathVariable String id, @RequestBody SelfServiceService.Decision decision) { return service.decide(id, true, decision); }
    @PostMapping("/requests/{id}/decision/reject") public Map<String, Object> reject(@PathVariable String id, @RequestBody SelfServiceService.Decision decision) { return service.decide(id, false, decision); }
    @PostMapping("/requests/{id}/fulfill") public Map<String, Object> fulfill(@PathVariable String id) { return service.fulfill(id); }
}
