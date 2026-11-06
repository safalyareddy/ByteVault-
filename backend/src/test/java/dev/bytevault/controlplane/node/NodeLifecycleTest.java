package dev.bytevault.controlplane.node;

import static dev.bytevault.controlplane.node.NodeLifecycle.HeartbeatAction.MARK_HEALTHY;
import static dev.bytevault.controlplane.node.NodeLifecycle.HeartbeatAction.MARK_JOINING;
import static dev.bytevault.controlplane.node.NodeLifecycle.HeartbeatAction.MARK_SUSPECT;
import static dev.bytevault.controlplane.node.NodeLifecycle.HeartbeatAction.NONE;
import static dev.bytevault.controlplane.node.NodeLifecycle.HeartbeatAction.RUN_ADMISSION_PROBE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NodeLifecycleTest {

    @Test
    void transitionTableMatchesStateMachineDoc() {
        Set<String> allowed = Set.of("JOINING>HEALTHY", "HEALTHY>SUSPECT", "HEALTHY>DRAINING", "SUSPECT>HEALTHY",
                "SUSPECT>OFFLINE", "SUSPECT>DRAINING", "OFFLINE>JOINING", "DRAINING>HEALTHY");
        for (NodeState from : NodeState.values()) {
            for (NodeState to : NodeState.values()) {
                assertThat(NodeLifecycle.isAllowed(from, to)).as(from + ">" + to)
                        .isEqualTo(allowed.contains(from + ">" + to));
            }
        }
    }

    @Test
    void invalidTransitionsAreRejected() {
        assertThatThrownBy(() -> NodeLifecycle.requireAllowed(NodeState.JOINING, NodeState.SUSPECT))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> NodeLifecycle.requireAllowed(NodeState.OFFLINE, NodeState.HEALTHY))
                .as("an OFFLINE node must re-join, never jump straight to HEALTHY")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void joiningNeedsEnoughConsecutiveUpHeartbeatsBeforeTheProbe() {
        assertThat(NodeLifecycle.onHeartbeat(NodeState.JOINING, true, 1, 2)).isEqualTo(NONE);
        assertThat(NodeLifecycle.onHeartbeat(NodeState.JOINING, true, 2, 2)).isEqualTo(RUN_ADMISSION_PROBE);
        assertThat(NodeLifecycle.onHeartbeat(NodeState.JOINING, false, 5, 2)).isEqualTo(NONE);
    }

    @Test
    void heartbeatContentDrivesHealthySuspectRecovery() {
        assertThat(NodeLifecycle.onHeartbeat(NodeState.HEALTHY, true, 9, 2)).isEqualTo(NONE);
        assertThat(NodeLifecycle.onHeartbeat(NodeState.HEALTHY, false, 0, 2)).isEqualTo(MARK_SUSPECT);
        assertThat(NodeLifecycle.onHeartbeat(NodeState.SUSPECT, true, 1, 2)).isEqualTo(MARK_HEALTHY);
        assertThat(NodeLifecycle.onHeartbeat(NodeState.SUSPECT, false, 0, 2)).isEqualTo(NONE);
        assertThat(NodeLifecycle.onHeartbeat(NodeState.OFFLINE, true, 1, 2)).isEqualTo(MARK_JOINING);
        assertThat(NodeLifecycle.onHeartbeat(NodeState.DRAINING, false, 0, 2)).as("drain is admin-controlled")
                .isEqualTo(NONE);
    }

    @Test
    void onlyLongSilentSuspectNodesGoOffline() {
        Duration offline = Duration.ofSeconds(45);
        assertThat(NodeLifecycle.shouldMarkOffline(NodeState.SUSPECT, Duration.ofSeconds(46), offline)).isTrue();
        assertThat(NodeLifecycle.shouldMarkOffline(NodeState.SUSPECT, Duration.ofSeconds(45), offline)).isFalse();
        assertThat(NodeLifecycle.shouldMarkOffline(NodeState.HEALTHY, Duration.ofHours(1), offline))
                .as("HEALTHY must pass through SUSPECT first").isFalse();
        assertThat(NodeLifecycle.shouldMarkOffline(NodeState.DRAINING, Duration.ofHours(1), offline)).isFalse();
    }

    @Test
    void onlySilentHealthyNodesBecomeSuspect() {
        Duration suspect = Duration.ofSeconds(15);
        assertThat(NodeLifecycle.shouldSuspect(NodeState.HEALTHY, Duration.ofSeconds(16), suspect)).isTrue();
        assertThat(NodeLifecycle.shouldSuspect(NodeState.HEALTHY, Duration.ofSeconds(15), suspect)).isFalse();
        assertThat(NodeLifecycle.shouldSuspect(NodeState.HEALTHY, null, suspect)).isTrue();
        assertThat(NodeLifecycle.shouldSuspect(NodeState.JOINING, Duration.ofHours(1), suspect)).isFalse();
        assertThat(NodeLifecycle.shouldSuspect(NodeState.SUSPECT, Duration.ofHours(1), suspect)).isFalse();
    }
}
