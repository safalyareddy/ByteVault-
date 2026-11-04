package dev.bytevault.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RequestSignerTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String SHA = "a".repeat(64);
    private final Clock now = Clock.fixed(Instant.parse("2026-11-04T10:00:00Z"), ZoneOffset.UTC);
    private final RequestSigner signer = new RequestSigner(SECRET, now);

    @Test
    void signedRequestVerifies() {
        var h = signer.sign("PUT", "/internal/v1/chunks/x", "node-1", SHA);
        assertThat(signer.verify("PUT", "/internal/v1/chunks/x", "node-1", h.timestamp(), SHA, h.signature()))
                .isEqualTo(RequestSigner.Verdict.VALID);
    }

    @Test
    void anyChangedFieldInvalidatesSignature() {
        var h = signer.sign("PUT", "/internal/v1/chunks/x", "node-1", SHA);
        String p = "/internal/v1/chunks/x";
        assertThat(signer.verify("GET", p, "node-1", h.timestamp(), SHA, h.signature()))
                .isEqualTo(RequestSigner.Verdict.BAD_SIGNATURE);
        assertThat(signer.verify("PUT", p + "y", "node-1", h.timestamp(), SHA, h.signature()))
                .isEqualTo(RequestSigner.Verdict.BAD_SIGNATURE);
        assertThat(signer.verify("PUT", p, "node-2", h.timestamp(), SHA, h.signature()))
                .isEqualTo(RequestSigner.Verdict.BAD_SIGNATURE);
        assertThat(signer.verify("PUT", p, "node-1", h.timestamp(), "b".repeat(64), h.signature()))
                .isEqualTo(RequestSigner.Verdict.BAD_SIGNATURE);
    }

    @Test
    void differentSecretRejected() {
        var other = new RequestSigner("ffffffffffffffffffffffffffffffff", now);
        var h = other.sign("GET", "/p", "node-1", NodeProtocol.NO_BODY);
        assertThat(signer.verify("GET", "/p", "node-1", h.timestamp(), NodeProtocol.NO_BODY, h.signature()))
                .isEqualTo(RequestSigner.Verdict.BAD_SIGNATURE);
    }

    @Test
    void staleOrMalformedTimestampRejected() {
        var past = new RequestSigner(SECRET, Clock.offset(now, java.time.Duration.ofSeconds(-61)));
        var h = past.sign("GET", "/p", "node-1", NodeProtocol.NO_BODY);
        assertThat(signer.verify("GET", "/p", "node-1", h.timestamp(), NodeProtocol.NO_BODY, h.signature()))
                .isEqualTo(RequestSigner.Verdict.EXPIRED);
        assertThat(signer.verify("GET", "/p", "node-1", "abc", NodeProtocol.NO_BODY, "x"))
                .isEqualTo(RequestSigner.Verdict.BAD_TIMESTAMP);
        assertThat(signer.verify("GET", "/p", "node-1", null, NodeProtocol.NO_BODY, "x"))
                .isEqualTo(RequestSigner.Verdict.MISSING_HEADERS);
    }

    @Test
    void weakSecretRefused() {
        assertThatThrownBy(() -> new RequestSigner("short")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void chunkIdMustBeCanonical() {
        assertThat(NodeProtocol.parseChunkId("0b9a3c1e-6f0c-4c8e-9a51-8d5f3b0c2a11")).isNotNull();
        for (String bad : new String[] {"0B9A3C1E-6F0C-4C8E-9A51-8D5F3B0C2A11", "../../etc/passwd",
                "0b9a3c1e6f0c4c8e9a518d5f3b0c2a11", "", null}) {
            assertThatThrownBy(() -> NodeProtocol.parseChunkId(bad)).as(String.valueOf(bad))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
