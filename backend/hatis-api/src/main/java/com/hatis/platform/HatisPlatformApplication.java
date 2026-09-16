package com.hatis.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point of the HATIS platform control plane and data plane API.
 *
 * <p>The application is a deliberately assembled modular monolith: every bounded
 * context lives in its own Maven module with its own package namespace, its own
 * persistence adapters and its own published events. Splitting a context into a
 * separately deployable service is a packaging change, not a rewrite, because
 * contexts only ever talk to each other through application services and events.
 */
@SpringBootApplication(scanBasePackages = "com.hatis.platform")
@ConfigurationPropertiesScan("com.hatis.platform")
@EnableAsync
@EnableScheduling
public class HatisPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(HatisPlatformApplication.class, args);
    }
}
