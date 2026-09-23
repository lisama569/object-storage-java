package com.github.lisama569.objectstoragejava.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 存储服务装配。
 */
@Configuration
public class StorageConfig {

    @Bean
    public ObjectStorageService objectStorageService(StorageProperties properties, ObjectMapper objectMapper) {
        return new ObjectStorageServiceImpl(properties, objectMapper);
    }
}
