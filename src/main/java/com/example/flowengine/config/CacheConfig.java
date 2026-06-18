package com.example.flowengine.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CacheConfig {

    @Bean
    public CacheManager cacheManager(
            @Value("${spring.cache.caffeine.spec:maximumSize=1000,expireAfterWrite=5m}") String caffeineSpec) {
        CaffeineCacheManager manager = new CaffeineCacheManager(
                "flowsAll",
                "flowsByModuleName",
                "flowDetails",
                "stepsByFlow",
                "stepsById",
                "environmentsById",
                "environmentsByModule",
                "decryptedEnvironmentVariables"
        );
        manager.setCacheSpecification(caffeineSpec);
        manager.setAllowNullValues(false);
        return manager;
    }
}
