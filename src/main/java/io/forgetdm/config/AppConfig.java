package io.forgetdm.config;

import io.forgetdm.core.mask.MaskingEngine;
import io.forgetdm.vault.MaskingSecretResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class AppConfig {
    @Bean
    public MaskingEngine maskingEngine(MaskingSecretResolver secretResolver) {
        // Masking key/salt is resolved from HashiCorp Vault when configured, else the local property.
        return new MaskingEngine(secretResolver.resolve().secret());
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService provisioningExecutor(ForgeProps props) {
        return Executors.newFixedThreadPool(props.getProvisioning().getWorkerThreads());
    }
}
