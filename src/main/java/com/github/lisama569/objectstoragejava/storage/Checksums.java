package com.github.lisama569.objectstoragejava.storage;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 校验和工具：十六进制表示与流式计算。
 */
final class Checksums {

    /** 分块读取缓冲区大小。 */
    private static final int BUFFER_SIZE = 64 * 1024;

    private Checksums() {
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，理论上不会缺失
            throw new IllegalStateException("SHA-256 digest unavailable", e);
        }
    }

    /**
     * 消费输入流并计算 SHA-256（十六进制小写）。流会被读到结尾但不会被关闭。
     */
    static String sha256Hex(InputStream in) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = in.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String toHex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    /** 判断是否为 64 位十六进制 SHA-256。 */
    static boolean isSha256Hex(String value) {
        if (value == null || value.length() != 64) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = Character.toLowerCase(value.charAt(i));
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
