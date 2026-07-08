package io.forgetdm.provision.loader;

import io.forgetdm.datasource.DataSourceEntity;

public interface NativeLoadExecutor {
    String strategy();
    boolean supports(DataSourceEntity target);
    NativeLoadStrategy describe(DataSourceEntity target);
    default NativeLoadResult execute(NativeLoadRequest request) {
        NativeLoadStrategy strategy = describe(request == null ? null : request.target());
        return NativeLoadSupport.skipped(strategy.strategy(), request == null ? null : request.target(),
                "Native execution is not implemented for " + strategy.strategy(), strategy);
    }
}
