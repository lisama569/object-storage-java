package com.github.lisama569.objectstoragejava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Smoke test that the full Spring context boots.
 *
 * @author Vincent
 * @version 1.0
 */
@SpringBootTest
@TestPropertySource(properties = "objectstorage.root=target/context-test-data")
public class ApplicationTests {
    @Test
    public void contentLoad() {
    }
}
