package com.github.lisama569.objectstoragejava.service;

import com.github.lisama569.objectstoragejava.exception.ChecksumMismatchException;
import com.github.lisama569.objectstoragejava.exception.StorageException;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Streaming reader for one content-addressed blob that participates in a (possibly ranged) object
 * read.
 * <p>
 * The ENTIRE blob is read and hashed — even when the requested byte window starts after the blob's
 * first byte or ends before its last byte — so a range crossing chunk boundaries still performs the
 * same per-blob SHA-256 integrity verification as a full read. Only the bytes inside the requested
 * window are emitted to the caller; the leading/trailing bytes are consumed and hashed but skipped.
 * The digest is compared with the blob's content-addressed name once all blob bytes are consumed.
 */
class VerifiedBlobInputStream extends FilterInputStream {

    private static final HexFormat HEX = HexFormat.of();
    private static final int SKIP_BUFFER = 8192;

    private final String expectedChecksum;
    private final long blobSize;
    private final MessageDigest digest;
    private final long blobStartOffset;
    private final long skipPrefix;
    private final long emitLength;
    private final long version;

    private long position;          // 0-based position consumed within this blob
    private long emitted;           // bytes emitted to the caller so far
    private boolean verified;

    private final byte[] oneByte = new byte[1];

    VerifiedBlobInputStream(InputStream in, long blobSize, String expectedChecksum,
                            long blobStartOffset, long skipPrefix, long emitLength, long version) {
        super(in);
        this.blobSize = blobSize;
        this.expectedChecksum = expectedChecksum;
        this.blobStartOffset = blobStartOffset;
        this.skipPrefix = skipPrefix;
        this.emitLength = emitLength;
        this.version = version;
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    @Override
    public int read() throws IOException {
        int n = read(oneByte, 0, 1);
        return n == -1 ? -1 : oneByte[0] & 0xff;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        long emitStart = skipPrefix;
        long emitEndExclusive = skipPrefix + emitLength;

        // Fast path: the next byte is inside the emission window.
        if (position >= emitStart && emitted < emitLength) {
            int want = (int) Math.min(len, emitLength - emitted);
            int read = rawRead(b, off, want);
            if (read == -1) {
                verifyAtEnd();
                return -1;
            }
            emitted += read;
            return read;
        }

        // Otherwise skip-and-hash bytes until we reach the window or exhaust the blob. All raw
        // reads go through rawRead(), never back into this overridden read().
        byte[] scratch = null;
        while (position < emitStart && emitted < emitLength) {
            int toSkip = (int) Math.min(SKIP_BUFFER, emitStart - position);
            if (scratch == null) {
                scratch = new byte[SKIP_BUFFER];
            }
            int read = rawRead(scratch, 0, toSkip);
            if (read == -1) {
                verifyAtEnd();
                return -1;
            }
        }
        if (emitted >= emitLength) {
            drainAndVerify();
            return -1;
        }
        // After skipping, tail-recurse once into the fast path to emit.
        return read(b, off, len);
    }

    /** Read from the underlying stream directly and feed every byte to the digest. */
    private int rawRead(byte[] b, int off, int len) throws IOException {
        int read = in.read(b, off, len);
        if (read > 0) {
            digest.update(b, off, read);
            position += read;
        }
        return read;
    }

    /** Consume (and hash) any unread trailing bytes directly from the underlying stream. */
    private void drainAndVerify() throws IOException {
        if (verified) {
            return;
        }
        byte[] buffer = new byte[SKIP_BUFFER];
        int read;
        while ((read = in.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
            position += read;
        }
        verifyAtEnd();
    }

    private void verifyAtEnd() {
        if (verified) {
            return;
        }
        verified = true;
        String actual = HEX.formatHex(digest.digest());
        if (!actual.equalsIgnoreCase(expectedChecksum)) {
            throw new ChecksumMismatchException("Integrity check failed while reading version " + version
                    + ": blob at object offset " + blobStartOffset + " (size " + blobSize
                    + ") named " + expectedChecksum + " but its bytes hash to " + actual);
        }
        if (position != blobSize) {
            throw new StorageException("Blob " + expectedChecksum + " stream ended at byte " + position
                    + " but metadata expected size " + blobSize);
        }
    }

    @Override
    public void close() throws IOException {
        // Even a client closing right after its window must let us hash and verify trailing bytes.
        try {
            drainAndVerify();
        } finally {
            super.close();
        }
    }
}
