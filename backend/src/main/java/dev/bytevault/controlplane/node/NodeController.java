package dev.bytevault.controlplane.node;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Node inventory as recorded by the control plane. Values come from the latest real probe. */
@RestController
@RequestMapping("/api/v1/nodes")
public class NodeController {

    private final NodeRepository nodes;

    public NodeController(NodeRepository nodes) {
        this.nodes = nodes;
    }

    public record NodeView(String id, String endpoint, String state, int protocolVersion, long capacityBytes,
                           long usedBytes, long usableBytes, OffsetDateTime lastHeartbeatAt, Long heartbeatAgeSeconds,
                           OffsetDateTime stateChangedAt) {}

    @GetMapping
    public List<NodeView> list() {
        OffsetDateTime now = OffsetDateTime.now();
        return nodes.findAll().stream().map(n -> new NodeView(n.id(), n.endpoint(), n.state().name(),
                n.protocolVersion(), n.capacityBytes(), n.usedBytes(), n.usableBytes(), n.lastHeartbeatAt(),
                n.lastHeartbeatAt() == null ? null : Duration.between(n.lastHeartbeatAt(), now).toSeconds(),
                n.stateChangedAt())).toList();
    }
}
