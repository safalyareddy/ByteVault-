package dev.bytevault.node.storage;

import dev.bytevault.node.config.NodeProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The node-owned data directory. Layout: {@code chunks/} for committed chunk files and {@code tmp/}
 * for in-progress writes. Created with owner-only permissions at startup; startup fails if the
 * directory is unusable rather than accepting writes it cannot persist.
 */
@Component
public class DataDirectory {

    private static final Logger log = LoggerFactory.getLogger(DataDirectory.class);

    private final Path root;
    private final Path chunks;
    private final Path tmp;

    public DataDirectory(NodeProperties props) {
        try {
            Path configured = props.dataDir().toAbsolutePath().normalize();
            Files.createDirectories(configured);
            if (Files.isSymbolicLink(configured)) {
                throw new IllegalStateException("Data directory must not be a symlink: " + configured);
            }
            this.root = configured.toRealPath(LinkOption.NOFOLLOW_LINKS);
            this.chunks = Files.createDirectories(root.resolve("chunks"));
            this.tmp = Files.createDirectories(root.resolve("tmp"));
            restrictPermissions(root);
            restrictPermissions(chunks);
            restrictPermissions(tmp);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot prepare data directory " + props.dataDir(), e);
        }
        if (!isWritable()) {
            throw new IllegalStateException("Data directory is not writable: " + root);
        }
        log.info("Node {} using data directory {}", props.id(), root);
    }

    public Path root() {
        return root;
    }

    public Path chunksDir() {
        return chunks;
    }

    public Path tmpDir() {
        return tmp;
    }

    /** Probes writability by creating and deleting a file in tmp/, not by trusting permission bits. */
    public boolean isWritable() {
        try {
            Path probe = Files.createTempFile(tmp, "probe-", ".tmp");
            Files.delete(probe);
            return true;
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    public DiskUsage usage() {
        try {
            FileStore store = Files.getFileStore(root);
            return new DiskUsage(store.getTotalSpace(), store.getUsableSpace());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Total and usable bytes of the filesystem holding the data directory, as reported by the OS. */
    public record DiskUsage(long totalBytes, long usableBytes) {}

    private static void restrictPermissions(Path dir) throws IOException {
        if (Files.getFileStore(dir).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        }
    }
}
