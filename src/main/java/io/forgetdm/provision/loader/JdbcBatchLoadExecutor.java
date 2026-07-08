package io.forgetdm.provision.loader;

import io.forgetdm.datasource.DataSourceEntity;

public class JdbcBatchLoadExecutor implements NativeLoadExecutor {
    @Override public String strategy() { return "JDBC_MULTI_ROW"; }
    @Override public boolean supports(DataSourceEntity target) { return true; }

    @Override
    public NativeLoadStrategy describe(DataSourceEntity target) {
        return new NativeLoadStrategy(
                "JDBC_MULTI_ROW",
                NativeLoadRegistry.engineOf(target),
                true,
                getClass().getSimpleName(),
                "JDBC_BATCH",
                "IN_PROCESS_JDBC",
                "",
                "Portable JDBC multi-row/batch loader used when no native client is configured.");
    }
}
