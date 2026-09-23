package com.github.lisama569.objectstoragejava.tests;

import com.github.lisama569.objectstoragejava.service.ChecksumService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ChecksumServiceTest {

    private final ChecksumService checksumService = new ChecksumService();

    @Test
    void sha256IsDeterministicAndMatchesKnownVector() {
        // Known SHA-256 of "abc"
        String expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        String first = checksumService.sha256(new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)));
        String second = checksumService.sha256(new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected, first, "checksum must match the known SHA-256 vector");
        assertEquals(first, second, "same input must always yield the same checksum");
        System.out.println("[checksum] sha256('abc') = " + first);
    }

    @Test
    void combinedChecksumIsOrderSensitiveAndDeterministic() {
        String a = checksumService.sha256(new ByteArrayInputStream("aaa".getBytes(StandardCharsets.UTF_8)));
        String b = checksumService.sha256(new ByteArrayInputStream("bbb".getBytes(StandardCharsets.UTF_8)));
        String ab1 = checksumService.combinePartChecksums(List.of(a, b));
        String ab2 = checksumService.combinePartChecksums(List.of(a, b));
        String ba = checksumService.combinePartChecksums(List.of(b, a));
        assertEquals(ab1, ab2, "same ordered part list must yield the same object checksum");
        assertNotEquals(ab1, ba, "part order is part of the object identity");
        System.out.println("[checksum] combine(a,b)=" + ab1 + " combine(b,a)=" + ba);
    }
}
