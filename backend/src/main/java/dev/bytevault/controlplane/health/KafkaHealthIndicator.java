package dev.bytevault.controlplane.health;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

/**
 * Reports Kafka as UP when the broker answers a describe-cluster call within a short timeout.
 * Registered as health contributor "kafka" and included in the readiness group.
 */
@Component("kafka")
public class KafkaHealthIndicator extends AbstractHealthIndicator implements DisposableBean {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final KafkaAdmin kafkaAdmin;
    private volatile AdminClient client;

    public KafkaHealthIndicator(KafkaAdmin kafkaAdmin) {
        super("Kafka health check failed");
        this.kafkaAdmin = kafkaAdmin;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) throws Exception {
        var cluster = client().describeCluster(new DescribeClusterOptions().timeoutMs((int) TIMEOUT.toMillis()));
        int brokers = cluster.nodes().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).size();
        builder.status(brokers > 0 ? "UP" : "DOWN").withDetail("brokers", brokers);
    }

    private AdminClient client() {
        AdminClient c = client;
        if (c == null) {
            synchronized (this) {
                if (client == null) {
                    client = AdminClient.create(kafkaAdmin.getConfigurationProperties());
                }
                c = client;
            }
        }
        return c;
    }

    @Override
    public void destroy() {
        if (client != null) {
            client.close(Duration.ofSeconds(2));
        }
    }
}
