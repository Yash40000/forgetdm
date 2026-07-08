package io.forgetdm.provision.loader;

import io.forgetdm.datasource.DataSourceEntity;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record NativeLoadRequest(DataSourceEntity target,
                                String schema,
                                String table,
                                List<String> columns,
                                Path dataFile,
                                String delimiter,
                                boolean header,
                                String loadAction,
                                Map<String, String> options) {
}
