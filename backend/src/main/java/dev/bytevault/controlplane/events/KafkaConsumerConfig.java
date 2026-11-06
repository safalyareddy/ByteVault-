package dev.bytevault.controlplane.events;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;

/**
 * Consumer error policy (docs/kafka-events.md): bounded exponential retries with jitter, then the
 * record goes to {@code bytevault.storage-dlq} with its original key, value and diagnostic headers.
 * Invalid envelopes skip retries entirely.
 */
@Configuration
public class KafkaConsumerConfig {

    public static final String HEADER_ATTEMPTS = "bv-attempts";
    public static final String HEADER_CONSUMER_GROUP = "bv-consumer-group";
    /** 4 retries after the first attempt = 5 attempts in total. */
    static final JitteredBackOff RETRY = new JitteredBackOff(500, 2.0, 30_000, 4, 0.2);

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template, MeterRegistry meters) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(Topics.STORAGE_DLQ, record.partition()));
        recoverer.setHeadersFunction((record, ex) -> {
            Throwable cause = ex instanceof ListenerExecutionFailedException && ex.getCause() != null ? ex.getCause() : ex;
            int attempts = cause instanceof InvalidEventException ? 1 : RETRY.maxRetries() + 1;
            String group = ex instanceof ListenerExecutionFailedException lefe ? lefe.getGroupId() : null;
            meters.counter("bv_events_dead_lettered_total", "topic", record.topic(),
                    "reason", cause.getClass().getSimpleName()).increment();
            RecordHeaders headers = new RecordHeaders();
            headers.add(HEADER_ATTEMPTS, Integer.toString(attempts).getBytes(StandardCharsets.UTF_8));
            if (group != null) {
                headers.add(HEADER_CONSUMER_GROUP, group.getBytes(StandardCharsets.UTF_8));
            }
            return headers;
        });
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, RETRY);
        handler.addNotRetryableExceptions(InvalidEventException.class);
        handler.setRetryListeners((record, ex, attempt) ->
                meters.counter("bv_events_retries_total", "topic", record.topic()).increment());
        return handler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer, ConsumerFactory<Object, Object> consumerFactory,
            DefaultErrorHandler kafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setConcurrency(1);
        return factory;
    }
}
