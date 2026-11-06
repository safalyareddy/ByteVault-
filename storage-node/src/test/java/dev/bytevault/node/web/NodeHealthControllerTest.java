package dev.bytevault.node.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class NodeHealthControllerTest {

    @TempDir static Path dataDir;

    @DynamicPropertySource
    static void nodeProps(DynamicPropertyRegistry r) {
        // Port 1 is never listening: heartbeats fail fast instead of reaching a real control plane.
        r.add("bytevault.node.control-plane-url", () -> "http://127.0.0.1:1");
        r.add("bytevault.node.id", () -> "node-test");
        r.add("bytevault.node.data-dir", () -> dataDir.toString());
        r.add("bytevault.node.auth-secret", () -> "s".repeat(40));
    }

    @Autowired MockMvc mvc;

    @Test
    void reportsIdentityAndMeasuredCapacity() throws Exception {
        mvc.perform(get("/internal/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodeId").value("node-test"))
                .andExpect(jsonPath("$.protocolVersion").value(1))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.dataDirWritable").value(true))
                .andExpect(jsonPath("$.capacityBytes").isNumber())
                .andExpect(jsonPath("$.authSecret").doesNotExist());
    }
}
