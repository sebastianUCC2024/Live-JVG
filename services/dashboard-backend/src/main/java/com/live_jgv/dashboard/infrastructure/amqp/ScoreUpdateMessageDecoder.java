package com.live_jgv.dashboard.infrastructure.amqp;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.live_jgv.dashboard.domain.model.*;
import java.io.IOException;
import java.time.Instant;

/** Decodifica el mensaje sin modificar el ObjectMapper de Spring MVC. */
public final class ScoreUpdateMessageDecoder {
    private final ObjectMapper mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .withCoercionConfig(LogicalType.Textual, config -> config
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .withCoercionConfig(LogicalType.DateTime, config -> config
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail))
            .build();

    public ScoreUpdate decode(byte[] body) {
        if (body == null || body.length == 0) {
            throw new InvalidScoreUpdateMessageException();
        }
        try {
            var tree = mapper.readTree(body);
            if (tree == null || !tree.isObject() || !tree.path("updated_at").isTextual()) {
                throw new InvalidScoreUpdateMessageException();
            }
            var message = mapper.treeToValue(tree, ScoreUpdateMessage.class);
            if (message == null || message.homeScore() == null || message.awayScore() == null) {
                throw new InvalidScoreUpdateMessageException();
            }
            return new ScoreUpdate(message.matchId(), message.homeScore(), message.awayScore(),
                    message.eventId(), message.eventType(), message.updatedAt(),
                    message.varStatus(), message.referenceEventId());
        } catch (IOException | IllegalArgumentException failure) {
            throw new InvalidScoreUpdateMessageException();
        }
    }

    private record ScoreUpdateMessage(String matchId, Integer homeScore, Integer awayScore,
            String eventId, EventType eventType, Instant updatedAt, VarStatus varStatus,
            String referenceEventId) { }
}
