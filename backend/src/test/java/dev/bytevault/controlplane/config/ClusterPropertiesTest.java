package dev.bytevault.controlplane.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ClusterPropertiesTest {

    private static final String S1 = "a".repeat(40);
    private static final String S2 = "b".repeat(40);

    private static ClusterProperties props(String nodes, String secrets) {
        ClusterProperties p = new ClusterProperties();
        p.setNodes(nodes);
        p.setNodeSecrets(secrets);
        return p;
    }

    @Test
    void parsesNodesInOrderWithSecrets() {
        var nodes = props("node-1=http://n1:9100, node-2=http://n2:9100", "node-1:" + S1 + ",node-2:" + S2).parsedNodes();
        assertThat(nodes.keySet()).containsExactly("node-1", "node-2");
        assertThat(nodes.get("node-2").endpoint().toString()).isEqualTo("http://n2:9100");
    }

    @Test
    void missingOrShortSecretFailsWithoutEchoingSecrets() {
        assertThatThrownBy(() -> props("node-1=http://n1:9100", "node-1:tooshortsecret").parsedNodes())
                .hasMessageContaining("node-1").hasMessageNotContaining("tooshortsecret");
        assertThatThrownBy(() -> props("node-1=http://n1:9100", "").parsedNodes()).hasMessageContaining("node-1");
    }

    @Test
    void malformedEntryAndBadIdsRejected() {
        assertThatThrownBy(() -> props("node-1=http://n1:9100", "node-1" + S1).parsedNodes())
                .hasMessageContaining("malformed").hasMessageNotContaining(S1);
        assertThatThrownBy(() -> props("Node_1=http://n1:9100", "Node_1:" + S1).parsedNodes())
                .hasMessageContaining("invalid node id");
        assertThatThrownBy(() -> props("node-1=file:///etc", "node-1:" + S1).parsedNodes())
                .hasMessageContaining("http");
        assertThatThrownBy(() -> props("node-1=http://a,node-1=http://b", "node-1:" + S1).parsedNodes())
                .hasMessageContaining("duplicate");
    }

    @Test
    void toStringOmitsSecrets() {
        assertThat(props("node-1=http://n1:9100", "node-1:" + S1).toString()).doesNotContain(S1);
    }
}
