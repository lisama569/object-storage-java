package com.github.lisama569.objectstoragejava.service;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** SHA-256 checksum helpers. */
@Service
public class ChecksumService {

    private static final int BUFFER_SIZE = 8192;
    private static final HexFormat HEX = HexFormat.of();

    /** Compute the lowercase hex SHA-256 digest of the given stream. The stream is consumed fully. */
    public String sha256(InputStream in) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HEX.formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        } catch (IOException e) {
            throw new com.github.lisama569.objectstoragejava.exception.StorageException(
                    "Failed to compute checksum", e);
        }
    }

    /**
     * Compute the object-level checksum from the ordered list of part checksums.
     * <p>
     * Every part checksum is a 64-character lowercase hex string, so concatenating them in
     * part order is unambiguous; the chained digest is SHA-256 over that concatenation.
     */
    public String combinePartChecksums(List<String> partChecksumsHex) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String partChecksum : partChecksumsHex) {
                digest.update(partChecksum.getBytes(StandardCharsets.US_ASCII));
            }
            return HEX.formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Convert raw digest bytes to lowercase hex. */
    public String toHex(byte[] digestBytes) {
        return HEX.formatHex(digestBytes);
    }

    /**
     * Fresh {@link MessageDigest} for callers that hash a concatenation of streams themselves
     * (e.g. the chunking-independent assembled-object digest).
     */
    public MessageDigest newSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Verify that the given stream hashes to {@code expectedHex}; the stream is consumed fully.
     *
     * @throws com.github.lisama569.objectstoragejava.exception.ChecksumMismatchException
     *         when the computed digest differs (case-insensitive comparison)
     */
    public void verifyHex(InputStream in, String expectedHex) {
        String actual = sha256(in);
        if (!actual.equalsIgnoreCase(expectedHex)) {
            throw new com.github.lisama569.objectstoragejava.exception.ChecksumMismatchException(
                    "Content verification failed: expected " + expectedHex + " but computed " + actual);
        }
    }
}
