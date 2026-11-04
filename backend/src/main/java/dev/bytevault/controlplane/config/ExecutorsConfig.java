package dev.bytevault.controlplane.config;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

@Configuration
public class ExecutorsConfig {

    /**
     * Fans a spooled chunk out to its replica nodes in parallel. Bounded threads and queue; when
     * saturated the request thread does the write itself, which slows producers instead of
     * dropping work.
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService replicaTransferExecutor(ClusterProperties cluster) {
        int threads = cluster.getMaxConcurrentChunkTransfers() * 3;
        return new ThreadPoolExecutor(threads, threads, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(threads * 2),
                new CustomizableThreadFactory("replica-io-"),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }
}
