package com.github.lisama569.objectstoragejava.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 本地对象存储配置。
 */
@Component
@ConfigurationProperties(prefix = "objectstorage")
public class StorageProperties {

    /** 存储根目录 */
    private String root = "data/storage";

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root;
    }
}
