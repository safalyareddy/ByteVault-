package dev.bytevault.protocol;

import java.time.Instant;

/** Heartbeat body sent by a node to the control plane (docs/storage-node-protocol.md). */
public record Heartbeat(
        String nodeId,
        int protocolVersion,
        String advertisedEndpoint,
        String status,
        boolean dataDirWritable,
        long capacityBytes,
        long usableBytes,
        Instant sentAt) {}
