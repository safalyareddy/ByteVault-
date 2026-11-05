package dev.bytevault.controlplane.events;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DlqRecorderTest {

    @Test
    void keepsOnlyTheRootCauseOfListenerFailures() {
        assertThat(DlqRecorder.rootReason("Listener method 'public void a.B.onEvent(x)' threw exception; "
                + "unparseable envelope: JsonParseException")).isEqualTo("unparseable envelope: JsonParseException");
        assertThat(DlqRecorder.rootReason("plain message")).isEqualTo("plain message");
        assertThat(DlqRecorder.rootReason(null)).isNull();
    }
}
