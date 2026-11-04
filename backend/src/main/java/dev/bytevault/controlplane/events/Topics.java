package dev.bytevault.controlplane.events;

import java.util.List;

/** Kafka topic names. Catalogue and key rules: docs/kafka-events.md. */
public final class Topics {

    public static final String OBJECT_EVENTS = "bytevault.object-events";
    public static final String CHUNK_EVENTS = "bytevault.chunk-events";
    public static final String NODE_EVENTS = "bytevault.node-events";
    public static final String REPLICATION_EVENTS = "bytevault.replication-events";
    public static final String REPAIR_COMMANDS = "bytevault.repair-commands";
    public static final String REPAIR_RESULTS = "bytevault.repair-results";
    public static final String STORAGE_DLQ = "bytevault.storage-dlq";

    public static final List<String> ALL = List.of(
            OBJECT_EVENTS, CHUNK_EVENTS, NODE_EVENTS, REPLICATION_EVENTS,
            REPAIR_COMMANDS, REPAIR_RESULTS, STORAGE_DLQ);

    private Topics() {}
}
