package com.github.lisama569.objectstoragejava.dto;

import java.io.IOException;
import java.io.InputStream;

/**
 * One read opened against a single immutable version snapshot: the metadata and the
 * stream are guaranteed to belong to the same version. Closing it closes the stream and
 * releases the blob-protection lease held for the read.
 */
public record OpenedObject(StoredObject metadata, InputStream stream, ByteRange range)
        implements AutoCloseable {

    public boolean ranged() {
        return range != null;
    }

    @Override
    public void close() throws IOException {
        stream.close();
    }
}
