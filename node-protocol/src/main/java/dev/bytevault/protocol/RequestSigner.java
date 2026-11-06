package dev.bytevault.protocol;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 request signing for the node protocol (ADR-0002). The canonical string covers
 * method, path, node ID, timestamp and the declared body checksum; the receiver separately checks
 * that the body matches that checksum.
 */
public final class RequestSigner {

    public static final Duration MAX_SKEW = Duration.ofSeconds(60);
    public static final int MIN_SECRET_LENGTH = 32;

    private final SecretKeySpec key;
    private final Clock clock;

    public RequestSigner(String secret, Clock clock) {
        if (secret == null || secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalArgumentException("node secret must be at least " + MIN_SECRET_LENGTH + " characters");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.clock = clock;
    }

    public RequestSigner(String secret) {
        this(secret, Clock.systemUTC());
    }

    /** Headers to attach to an outgoing request. */
    public record SignedHeaders(String nodeId, String timestamp, String contentSha256, String signature) {}

    public SignedHeaders sign(String method, String path, String nodeId, String contentSha256) {
        String timestamp = Long.toString(clock.millis());
        return new SignedHeaders(nodeId, timestamp, contentSha256, signature(method, path, nodeId, timestamp, contentSha256));
    }

    public enum Verdict { VALID, MISSING_HEADERS, BAD_TIMESTAMP, EXPIRED, BAD_SIGNATURE }

    /**
     * Verifies an incoming request. Callers must not reveal which check failed to the client.
     */
    public Verdict verify(String method, String path, String nodeId, String timestamp, String contentSha256,
                          String signature) {
        if (nodeId == null || timestamp == null || contentSha256 == null || signature == null) {
            return Verdict.MISSING_HEADERS;
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return Verdict.BAD_TIMESTAMP;
        }
        if (Math.abs(clock.millis() - ts) > MAX_SKEW.toMillis()) {
            return Verdict.EXPIRED;
        }
        byte[] expected = signature(method, path, nodeId, timestamp, contentSha256).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = signature.getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual) ? Verdict.VALID : Verdict.BAD_SIGNATURE;
    }

    static String canonical(String method, String path, String nodeId, String timestamp, String contentSha256) {
        return method + "\n" + path + "\n" + nodeId + "\n" + timestamp + "\n" + contentSha256;
    }

    private String signature(String method, String path, String nodeId, String timestamp, String contentSha256) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            byte[] raw = mac.doFinal(canonical(method, path, nodeId, timestamp, contentSha256)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
