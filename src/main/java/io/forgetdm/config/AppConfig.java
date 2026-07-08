package io.forgetdm.config;

import io.forgetdm.core.mask.MaskingEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class AppConfig {
    @Bean
    public MaskingEngine maskingEngine(ForgeProps props) {
        return new MaskingEngine(props.getMaskingSecret());
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService provisioningExecutor(ForgeProps props) {
        return Executors.newFixedThreadPool(props.getProvisioning().getWorkerThreads());
    }
}
