package com.github.lisama569.objectstoragejava.config;

import com.github.lisama569.objectstoragejava.service.StorageLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/** Creates the on-disk directory structure at startup and sweeps crash-leftover temp files. */
@Component
public class StorageBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StorageBootstrap.class);

    private final StorageLayout layout;

    public StorageBootstrap(StorageLayout layout) {
        this.layout = layout;
    }

    @Override
    public void run(ApplicationArguments args) {
        layout.initialize();
        int swept = sweepTempFiles(layout.root());
        log.info("Local object storage initialised at {} (swept {} orphan temp files)", layout.root(), swept);
    }

    /**
     * Remove *.tmp files left by a JVM crash in the middle of a part/blob/version write.
     * Such files were never atomically renamed into place, so deleting them cannot affect any
     * committed data or an in-progress upload's committed parts.
     */
    private int sweepTempFiles(Path root) {
        int[] removed = {0};
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".tmp") || n.endsWith(".json.tmp");
                    })
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                            removed[0]++;
                        } catch (IOException ignored) {
                            // best effort
                        }
                    });
        } catch (IOException e) {
            log.warn("Temp file sweep under {} failed: {}", root, e.getMessage());
        }
        return removed[0];
    }
}
