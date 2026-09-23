package com.github.lisama569.objectstoragejava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Local object-storage configuration.
 *
 * @param root data directory (defaults to ./object-storage-data)
 */
@ConfigurationProperties(prefix = "objectstorage")
public record StorageProperties(String root) {

    public StorageProperties {
        if (root == null || root.isBlank()) {
            root = "object-storage-data";
        }
    }
}
