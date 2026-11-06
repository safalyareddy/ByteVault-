package dev.bytevault.protocol;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** SHA-256 helpers. Checksums travel as lowercase hex everywhere in ByteVault. */
public final class Sha256 {

    private static final Pattern HEX = Pattern.compile("^[0-9a-f]{64}$");

    private Sha256() {}

    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String hex(byte[] digest) {
        return HexFormat.of().formatHex(digest);
    }

    public static String of(byte[] data) {
        return hex(newDigest().digest(data));
    }

    public static boolean isValidHex(String value) {
        return value != null && HEX.matcher(value).matches();
    }
}
