package io.forgetdm.datasource;

import io.forgetdm.provision.loader.NativeLoadRegistry;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/datasources")
public class DataSourceController {
    private final DataSourceService svc;
    private final NativeLoadRegistry nativeLoaders;
    public DataSourceController(DataSourceService svc, NativeLoadRegistry nativeLoaders) {
        this.svc = svc;
        this.nativeLoaders = nativeLoaders;
    }

    @GetMapping public List<DataSourceEntity> list() { return svc.list(); }
    @PostMapping public DataSourceEntity create(@RequestBody DataSourceEntity ds) { return svc.create(ds); }
    @PutMapping("/{id}") public DataSourceEntity update(@PathVariable Long id, @RequestBody DataSourceEntity ds) { return svc.update(id, ds); }
    @GetMapping("/{id}") public DataSourceEntity get(@PathVariable Long id) { return svc.get(id); }
    @DeleteMapping("/{id}") public void delete(@PathVariable Long id) { svc.delete(id); }
    @PostMapping("/{id}/test") public Map<String, Object> test(@PathVariable Long id) { return svc.testConnection(id); }
    @PostMapping("/test-connection") public Map<String, Object> testTransient(@RequestBody DataSourceEntity ds) { return svc.testTransient(ds); }
    @GetMapping("/native-loaders") public List<Map<String, Object>> nativeLoaders() { return nativeLoaders.status(); }
    @GetMapping("/{id}/schemas") public List<Map<String, Object>> schemas(@PathVariable Long id) { return svc.schemas(id); }
    @GetMapping("/{id}/tables")
    public List<Map<String, Object>> tables(@PathVariable Long id, @RequestParam(required = false) String schema) {
        return svc.tables(id, schema);
    }
    @GetMapping("/{id}/tables/{table}/columns")
    public List<Map<String, Object>> columns(@PathVariable Long id, @PathVariable String table,
                                             @RequestParam(required = false) String schema) {
        return svc.columns(id, schema, table);
    }
    @GetMapping("/{id}/tables/{table}/fks")
    public List<Map<String, Object>> fks(@PathVariable Long id, @PathVariable String table,
                                         @RequestParam(required = false) String schema) {
        return svc.foreignKeys(id, schema, table);
    }
}
