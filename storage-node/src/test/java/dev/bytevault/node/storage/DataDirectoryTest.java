package dev.bytevault.node.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.bytevault.node.config.NodeProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataDirectoryTest {

    @TempDir Path temp;

    static NodeProperties props(Path dir) {
        return new NodeProperties("node-t", dir, "x".repeat(32), "http://localhost:9100", "http://localhost:8090",
                Duration.ofSeconds(5), 64L * 1024 * 1024);
    }

    @Test
    void createsLayoutWithOwnerOnlyPermissions() throws Exception {
        Path dir = temp.resolve("data");
        DataDirectory dd = new DataDirectory(props(dir));

        assertThat(dd.chunksDir()).isDirectory();
        assertThat(dd.tmpDir()).isDirectory();
        assertThat(dd.isWritable()).isTrue();
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dd.root()))).isEqualTo("rwx------");
    }

    @Test
    void reportsRealDiskUsage() {
        DataDirectory dd = new DataDirectory(props(temp));
        DataDirectory.DiskUsage usage = dd.usage();
        assertThat(usage.totalBytes()).isPositive();
        assertThat(usage.usableBytes()).isBetween(0L, usage.totalBytes());
    }

    @Test
    void rejectsSymlinkedDataDirectory() throws Exception {
        Path real = Files.createDirectory(temp.resolve("real"));
        Path link = Files.createSymbolicLink(temp.resolve("link"), real);
        assertThatThrownBy(() -> new DataDirectory(props(link)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("symlink");
    }

    @Test
    void secretIsNotPrinted() {
        assertThat(props(temp).toString()).doesNotContain("x".repeat(32));
    }
}
