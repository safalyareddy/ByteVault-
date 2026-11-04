package dev.bytevault.controlplane.config;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * KafkaAdmin creates topics once at startup and, with fail-fast off, only logs if the broker is
 * unreachable. The control plane must start while Kafka is down (the outbox absorbs the outage), so
 * this retries topic creation until it succeeds once.
 */
@Component
public class TopicInitializer {

    private static final Logger log = LoggerFactory.getLogger(TopicInitializer.class);

    private final KafkaAdmin kafkaAdmin;
    private final AtomicBoolean done = new AtomicBoolean(false);

    public TopicInitializer(KafkaAdmin kafkaAdmin) {
        this.kafkaAdmin = kafkaAdmin;
    }

    @Scheduled(initialDelayString = "${bytevault.kafka.topic-retry-interval:30s}",
            fixedDelayString = "${bytevault.kafka.topic-retry-interval:30s}")
    void ensureTopics() {
        if (done.get()) {
            return;
        }
        // initialize() returns false (and logs) when topics could not be created or verified.
        if (kafkaAdmin.initialize()) {
            done.set(true);
            log.info("Kafka topics verified");
        } else {
            log.warn("Kafka topics not yet verified; will retry");
        }
    }

    public boolean topicsVerified() {
        return done.get();
    }
}
