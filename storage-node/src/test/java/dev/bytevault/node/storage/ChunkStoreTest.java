package dev.bytevault.node.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.bytevault.protocol.Sha256;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChunkStoreTest {

    @TempDir Path temp;
    ChunkStore store;
    DataDirectory dir;

    @BeforeEach
    void setUp() {
        dir = new DataDirectory(DataDirectoryTest.props(temp));
        store = new ChunkStore(dir, new SimpleMeterRegistry());
    }

    static byte[] random(int size, long seed) {
        byte[] b = new byte[size];
        new Random(seed).nextBytes(b);
        return b;
    }

    @Test
    void writeThenReadReturnsIdenticalBytesAndRecordedChecksum() throws Exception {
        byte[] data = random(300_000, 1);
        UUID id = UUID.randomUUID();

        var result = store.put(id, new ByteArrayInputStream(data), data.length, Sha256.of(data));

        assertThat(result.outcome()).isEqualTo(ChunkStore.PutOutcome.CREATED);
        var stored = store.find(id).orElseThrow();
        assertThat(Files.readAllBytes(stored.file())).isEqualTo(data);
        assertThat(stored.sha256()).isEqualTo(Sha256.of(data));
        String s = id.toString();
        assertThat(stored.file()).isEqualTo(dir.chunksDir().resolve(s.substring(0, 2)).resolve(s.substring(2, 4))
                .resolve(s + ".chunk"));
        assertThat(Files.list(dir.tmpDir())).as("no temp files left").isEmpty();
    }

    @Test
    void checksumMismatchStoresNothing() throws Exception {
        byte[] data = random(1000, 2);
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> store.put(id, new ByteArrayInputStream(data), data.length, "0".repeat(64)))
                .isInstanceOfSatisfying(ChunkStore.ChunkValidationException.class,
                        e -> assertThat(e.code()).isEqualTo("CHECKSUM_MISMATCH"));
        assertThat(store.find(id)).isEmpty();
        assertThat(Files.list(dir.tmpDir())).isEmpty();
    }

    @Test
    void shortOrLongBodyRejected() {
        byte[] data = random(1000, 3);
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> store.put(id, new ByteArrayInputStream(data), 1001, Sha256.of(data)))
                .hasMessageContaining("declared 1001");
        assertThatThrownBy(() -> store.put(id, new ByteArrayInputStream(data), 999, Sha256.of(data)))
                .hasMessageContaining("longer than declared");
    }

    @Test
    void identicalRetryIsIdempotentAndDifferentContentConflicts() throws Exception {
        byte[] data = random(5000, 4);
        UUID id = UUID.randomUUID();
        store.put(id, new ByteArrayInputStream(data), data.length, Sha256.of(data));

        var again = store.put(id, new ByteArrayInputStream(data), data.length, Sha256.of(data));
        assertThat(again.outcome()).isEqualTo(ChunkStore.PutOutcome.ALREADY_PRESENT);

        byte[] other = random(5000, 5);
        assertThatThrownBy(() -> store.put(id, new ByteArrayInputStream(other), other.length, Sha256.of(other)))
                .isInstanceOf(ChunkStore.ChunkConflictException.class);
        assertThat(Files.readAllBytes(store.find(id).orElseThrow().file())).isEqualTo(data);
    }

    @Test
    void concurrentIdenticalWritesProduceOneChunk() throws Exception {
        byte[] data = random(200_000, 6);
        UUID id = UUID.randomUUID();
        Callable<ChunkStore.PutResult> put = () -> store.put(id, new ByteArrayInputStream(data), data.length, Sha256.of(data));
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = pool.invokeAll(java.util.Collections.nCopies(8, put));
            long created = 0;
            for (var f : futures) {
                if (f.get().outcome() == ChunkStore.PutOutcome.CREATED) {
                    created++;
                }
            }
            assertThat(created).isEqualTo(1);
        }
        assertThat(Files.readAllBytes(store.find(id).orElseThrow().file())).isEqualTo(data);
    }

    @Test
    void deleteIsIdempotent() throws Exception {
        byte[] data = random(10, 7);
        UUID id = UUID.randomUUID();
        store.put(id, new ByteArrayInputStream(data), data.length, Sha256.of(data));
        assertThat(store.delete(id)).isTrue();
        assertThat(store.delete(id)).isFalse();
        assertThat(store.find(id)).isEmpty();
    }
}
