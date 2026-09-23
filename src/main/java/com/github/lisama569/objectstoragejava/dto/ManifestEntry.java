package com.github.lisama569.objectstoragejava.dto;

/** One part reference inside a completion manifest. */
public record ManifestEntry(int partNumber, String checksum) {
}
