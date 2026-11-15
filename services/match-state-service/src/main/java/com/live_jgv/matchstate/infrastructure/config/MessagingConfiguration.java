package com.live_jgv.matchstate.infrastructure.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.live_jgv.matchstate.application.service.MatchStateService;
import com.live_jgv.matchstate.application.service.ProcessAndPublishMatchEvent;
import com.live_jgv.matchstate.domain.model.MatchState;
import com.live_jgv.matchstate.domain.port.ProcessMatchEvent;
import com.live_jgv.matchstate.domain.port.ScoreUpdatePublisher;
import com.live_jgv.matchstate.entrypoint.kafka.KafkaMatchEventListener;
import com.live_jgv.matchstate.infrastructure.memory.InMemoryMatchStateStore;
import com.live_jgv.matchstate.infrastructure.rabbit.RabbitScoreUpdatePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import java.time.Duration;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MatchCatalogProperties.class)
public class MessagingConfiguration {
    private static final Logger log = LoggerFactory.getLogger(MessagingConfiguration.class);

    @Bean
    public ObjectMapper messagingObjectMapper() {
        return JsonMapper.builder().addModule(new JavaTimeModule())
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .serializationInclusion(JsonInclude.Include.ALWAYS)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .withCoercionConfig(LogicalType.Textual, coercion -> coercion
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    }

    @Bean
    public InMemoryMatchStateStore matchStateStore(MatchCatalogProperties properties) {
        var definitions = properties.getMatches().stream().filter(definition ->
                hasText(definition.matchId()) || hasText(definition.homeTeamId()) || hasText(definition.awayTeamId())).toList();
        log.warn("Using volatile match state and outbox; configuredMatches={}; restart recovery is not supported", definitions.size());
        return new InMemoryMatchStateStore(definitions.stream().map(definition -> MatchState.start(
                definition.matchId(), definition.homeTeamId(), definition.awayTeamId())).toList());
    }

    private static boolean hasText(String value) { return value != null && !value.isBlank(); }

    @Bean
    public ProcessMatchEvent processMatchEvent(InMemoryMatchStateStore store, ScoreUpdatePublisher publisher) {
        return new ProcessAndPublishMatchEvent(new MatchStateService(store), store, publisher);
    }

    @Bean
    public KafkaMatchEventListener kafkaMatchEventListener(ObjectMapper mapper, ProcessMatchEvent processor) {
        return new KafkaMatchEventListener(mapper, processor);
    }

    @Bean
    public DefaultErrorHandler matchEventsErrorHandler(@Value("${match-state.kafka.retry-interval:1s}") Duration interval) {
        if (interval.toMillis() < 1) { throw new IllegalArgumentException("Retry interval must be positive"); }
        var handler = new DefaultErrorHandler((record, failure) -> {
            // Never recover by discarding a record or committing its offset.
            throw new IllegalStateException("Kafka record must remain uncommitted", failure);
        }, new FixedBackOff(interval.toMillis(), FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.setClassifications(Map.of(Exception.class, true), true);
        handler.setAckAfterHandle(false);
        handler.setCommitRecovered(false);
        handler.setRetryListeners((record, failure, attempt) -> log.warn(
                "Kafka record retry pending; partition={}, offset={}, attempt={}, failureType={}",
                record.partition(), record.offset(), attempt, failure.getClass().getName()));
        // Framework default error logging includes record contents and exception messages.
        handler.setLogLevel(org.springframework.kafka.KafkaException.Level.TRACE);
        return handler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> matchEventsContainerFactory(
            KafkaProperties properties, DefaultErrorHandler matchEventsErrorHandler) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(properties.buildConsumerProperties()));
        factory.setConcurrency(1);
        factory.setAutoStartup(properties.getListener().isAutoStartup());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.getContainerProperties().setSyncCommits(true);
        factory.setCommonErrorHandler(matchEventsErrorHandler);
        return factory;
    }

    @Bean
    public TopicExchange liveUpdatesExchange(@Value("${match-state.rabbit.exchange:live_updates}") String exchange) {
        return new TopicExchange(exchange, true, false);
    }

    @Bean
    public ScoreUpdatePublisher scoreUpdatePublisher(RabbitTemplate template, ObjectMapper mapper,
            @Value("${match-state.rabbit.exchange:live_updates}") String exchange,
            @Value("${match-state.rabbit.routing-key:score.{matchId}}") String routingKey,
            @Value("${match-state.rabbit.confirm-timeout:5s}") Duration timeout) {
        template.setMandatory(true);
        return new RabbitScoreUpdatePublisher(template, mapper, exchange, routingKey, timeout);
    }
}
