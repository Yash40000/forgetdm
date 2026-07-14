package io.forgetdm.automation;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/self-service/v2")
public class EnterpriseSelfServiceController {
    private final EnterpriseSelfServiceService service;

    public EnterpriseSelfServiceController(EnterpriseSelfServiceService service) { this.service = service; }

    @GetMapping("/catalog")
    public List<Map<String, Object>> catalog(@RequestParam(required = false) String q,
                                             @RequestParam(required = false) String category,
                                             @RequestParam(required = false) String type) {
        return service.catalog(q, category, type);
    }
    @GetMapping("/products") public List<Map<String, Object>> products() { return service.products(); }
    @GetMapping("/candidates") public List<Map<String, Object>> candidates() { return service.candidates(); }
    @PostMapping("/products") public Map<String, Object> publish(@RequestBody EnterpriseSelfServiceService.ProductRequest request) { return service.publish(request); }
    @PostMapping("/products/{id}/enable") public Map<String, Object> enable(@PathVariable String id) { return service.setEnabled(id, true); }
    @PostMapping("/products/{id}/disable") public Map<String, Object> disable(@PathVariable String id) { return service.setEnabled(id, false); }

    @GetMapping("/orders") public List<Map<String, Object>> orders() { return service.orders(); }
    @GetMapping("/orders/{id}") public Map<String, Object> order(@PathVariable String id) { return service.get(id); }
    @PostMapping("/orders") public Map<String, Object> request(@RequestBody EnterpriseSelfServiceService.OrderRequest request) { return service.request(request); }
    @PostMapping("/orders/{id}/decision/approve") public Map<String, Object> approve(@PathVariable String id, @RequestBody EnterpriseSelfServiceService.Decision decision) { return service.decide(id, true, decision); }
    @PostMapping("/orders/{id}/decision/reject") public Map<String, Object> reject(@PathVariable String id, @RequestBody EnterpriseSelfServiceService.Decision decision) { return service.decide(id, false, decision); }
    @PostMapping("/orders/{id}/fulfill") public Map<String, Object> fulfill(@PathVariable String id) { return service.fulfill(id); }
    @PostMapping("/orders/{id}/cancel") public Map<String, Object> cancel(@PathVariable String id, @RequestBody EnterpriseSelfServiceService.Comment reason) { return service.cancel(id, reason); }
    @PostMapping("/orders/{id}/comments") public Map<String, Object> comment(@PathVariable String id, @RequestBody EnterpriseSelfServiceService.Comment comment) { return service.comment(id, comment); }
    @GetMapping("/orders/{id}/runner") public Map<String, Object> runner(@PathVariable String id) { return service.runner(id); }
    @GetMapping("/metrics") public Map<String, Object> metrics() { return service.metrics(); }
}
