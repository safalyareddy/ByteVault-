package dev.bytevault.protocol;

import java.util.UUID;
import java.util.regex.Pattern;

/** Constants of storage-node protocol v1 (docs/storage-node-protocol.md). */
public final class NodeProtocol {

    public static final int VERSION = 1;
    public static final String PREFIX = "/internal/v1";
    public static final String HEALTH_PATH = PREFIX + "/health";
    public static final String CHUNKS_PATH = PREFIX + "/chunks";

    public static final String HEADER_NODE_ID = "X-BV-Node-Id";
    public static final String HEADER_TIMESTAMP = "X-BV-Timestamp";
    public static final String HEADER_CONTENT_SHA256 = "X-BV-Content-SHA256";
    public static final String HEADER_SIGNATURE = "X-BV-Signature";
    public static final String HEADER_STORED_SHA256 = "X-BV-Stored-SHA256";

    /** Value of {@link #HEADER_CONTENT_SHA256} for requests without a body. */
    public static final String NO_BODY = "-";

    public static final Pattern NODE_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{0,31}$");
    private static final Pattern CANONICAL_UUID =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private NodeProtocol() {}

    public static String chunkPath(UUID chunkId) {
        return CHUNKS_PATH + "/" + chunkId;
    }

    /**
     * Parses a chunk ID only in canonical lowercase form, so one chunk can never be addressed by
     * two different strings (and therefore two different file paths).
     */
    public static UUID parseChunkId(String raw) {
        if (raw == null || !CANONICAL_UUID.matcher(raw).matches()) {
            throw new IllegalArgumentException("chunk id must be a canonical lowercase UUID");
        }
        return UUID.fromString(raw);
    }
}
