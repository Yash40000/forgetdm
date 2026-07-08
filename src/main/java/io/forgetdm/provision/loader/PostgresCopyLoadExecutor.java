package io.forgetdm.provision.loader;

import io.forgetdm.datasource.DataSourceEntity;

public class PostgresCopyLoadExecutor implements NativeLoadExecutor {
    @Override public String strategy() { return "POSTGRES_COPY"; }

    @Override
    public boolean supports(DataSourceEntity target) {
        String engine = NativeLoadRegistry.engineOf(target);
        return "POSTGRES".equals(engine) || "POSTGRESQL".equals(engine);
    }

    @Override
    public NativeLoadStrategy describe(DataSourceEntity target) {
        return new NativeLoadStrategy(
                "POSTGRES_COPY",
                NativeLoadRegistry.engineOf(target),
                true,
                getClass().getSimpleName(),
                "JDBC_MULTI_ROW",
                "IN_PROCESS_NATIVE_COPY",
                "",
                "PostgreSQL COPY is implemented in the synthetic streaming fast-load path; DataScope keeps JDBC fallback when masking row-by-row.");
    }
}
