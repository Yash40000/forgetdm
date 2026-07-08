package io.forgetdm.ri;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Tool-level Referential Integrity & Keys registry. PK and relationship definitions are reusable across
 * features (masking, synthetic generation, subsetting, validation) and scoped per user / group / global.
 * Secured by ri.read (GET) and ri.manage (writes) in AccessControlFilter.
 */
@RestController
@RequestMapping("/api/ri")
public class RiController {

    private final RiRegistryService ri;

    public RiController(RiRegistryService ri) {
        this.ri = ri;
    }

    @GetMapping("/keys")
    public List<Map<String, Object>> keys(@RequestParam(required = false) Long dataSourceId,
                                          @RequestParam(required = false) String schema) {
        return ri.listKeys(dataSourceId, schema);
    }

    @PostMapping("/keys")
    public Map<String, Object> createKey(@RequestBody RiRegistryService.KeyRequest req) {
        return ri.createKey(req);
    }

    @PutMapping("/keys/{id}")
    public Map<String, Object> updateKey(@PathVariable long id, @RequestBody RiRegistryService.KeyRequest req) {
        return ri.updateKey(id, req);
    }

    @DeleteMapping("/keys/{id}")
    public void deleteKey(@PathVariable long id) {
        ri.deleteKey(id);
    }

    @GetMapping("/relationships")
    public List<Map<String, Object>> relationships(@RequestParam(required = false) Long dataSourceId,
                                                   @RequestParam(required = false) String schema) {
        return ri.listRelationships(dataSourceId, schema);
    }

    @PostMapping("/relationships")
    public Map<String, Object> createRelationship(@RequestBody RiRegistryService.RelationshipRequest req) {
        return ri.createRelationship(req);
    }

    @PutMapping("/relationships/{id}")
    public Map<String, Object> updateRelationship(@PathVariable long id, @RequestBody RiRegistryService.RelationshipRequest req) {
        return ri.updateRelationship(id, req);
    }

    @DeleteMapping("/relationships/{id}")
    public void deleteRelationship(@PathVariable long id) {
        ri.deleteRelationship(id);
    }

    /** Groups the current user belongs to (for GROUP-scoped sharing in the UI). */
    @GetMapping("/my-groups")
    public List<Map<String, Object>> myGroups() {
        return ri.myGroups();
    }

    /** Effective PKs + relationships for the current user against a target (precedence applied). */
    @GetMapping("/resolve")
    public Map<String, Object> resolve(@RequestParam Long dataSourceId,
                                       @RequestParam(required = false) String schema) {
        return ri.resolve(dataSourceId, schema);
    }
}
