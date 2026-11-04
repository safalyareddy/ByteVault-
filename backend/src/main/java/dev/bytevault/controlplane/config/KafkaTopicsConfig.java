package dev.bytevault.controlplane.config;

import dev.bytevault.controlplane.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Declares every topic explicitly; broker auto-creation is disabled in Compose (ADR-0008). Spring's
 * {@link KafkaAdmin} creates missing topics at startup and never deletes or shrinks existing ones.
 */
@Configuration
public class KafkaTopicsConfig {

    @Bean
    public KafkaAdmin.NewTopics bytevaultTopics(BytevaultProperties props) {
        int partitions = props.kafka().topicPartitions();
        short replicas = props.kafka().topicReplicationFactor();
        return new KafkaAdmin.NewTopics(Topics.ALL.stream()
                .map(name -> TopicBuilder.name(name).partitions(partitions).replicas(replicas).build())
                .toArray(NewTopic[]::new));
    }
}
