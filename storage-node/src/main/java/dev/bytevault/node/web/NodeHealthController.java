package dev.bytevault.node.web;

import dev.bytevault.node.config.NodeProperties;
import dev.bytevault.node.storage.DataDirectory;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unauthenticated node health (docs/storage-node-protocol.md). Values are measured on each call
 * from the real filesystem; nothing here is cached or synthesised.
 */
@RestController
public class NodeHealthController {

    private final NodeProperties props;
    private final DataDirectory dataDirectory;

    public NodeHealthController(NodeProperties props, DataDirectory dataDirectory) {
        this.props = props;
        this.dataDirectory = dataDirectory;
    }

    @GetMapping("/internal/v1/health")
    public ResponseEntity<NodeHealth> health() {
        boolean writable = dataDirectory.isWritable();
        DataDirectory.DiskUsage usage = dataDirectory.usage();
        NodeHealth body = new NodeHealth(props.id(), NodeProperties.PROTOCOL_VERSION, writable ? "UP" : "DOWN",
                writable, usage.totalBytes(), usage.usableBytes(), Instant.now());
        return ResponseEntity.status(writable ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    public record NodeHealth(
            String nodeId, int protocolVersion, String status, boolean dataDirWritable,
            long capacityBytes, long usableBytes, Instant checkedAt) {}
}
