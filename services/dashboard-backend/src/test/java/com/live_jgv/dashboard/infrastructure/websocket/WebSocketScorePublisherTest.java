package com.live_jgv.dashboard.infrastructure.websocket;

import com.live_jgv.dashboard.application.service.ProcessScoreUpdateService;
import com.live_jgv.dashboard.domain.model.*;
import com.live_jgv.dashboard.domain.port.out.MatchSnapshotPublisher;
import com.live_jgv.dashboard.infrastructure.amqp.*;
import com.live_jgv.dashboard.infrastructure.memory.InMemoryDashboardProjection;
import com.live_jgv.dashboard.infrastructure.web.MatchSnapshotResponse;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.*;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WebSocketScorePublisherTest {
    private final ScoreUpdate update = new ScoreUpdate("m1", 1, 0, "e1", EventType.GOAL,
            Instant.parse("2026-11-15T00:12:12.079218900Z"), VarStatus.CLEAR, null);

    @Test
    void mapsSnapshotAndPublishesToCorrectMatch() {
        var template = mock(SimpMessagingTemplate.class);
        new WebSocketScorePublisher(template).publish(new MatchSnapshot("stream", 4, update));
        var dto = ArgumentCaptor.forClass(MatchSnapshotResponse.class);
        verify(template).convertAndSend(eq("/topic/matches/m1/score"), dto.capture());
        assertEquals("stream", dto.getValue().streamId());
        assertEquals(4, dto.getValue().revision());
        assertEquals(1, dto.getValue().homeScore());
        assertEquals("2026-11-15T00:12:12.079218900Z", dto.getValue().updatedAt());
        assertEquals("/topic/matches/a%2Fb/score", WebSocketScorePublisher.destination("a/b"));
    }

    @Test
    void invalidRabbitPayloadNeverReachesWebSocket() {
        var template = mock(SimpMessagingTemplate.class);
        var projection = new InMemoryDashboardProjection(10, new WebSocketScorePublisher(template));
        var listener = new ScoreUpdateListener(new ScoreUpdateMessageDecoder(), new ProcessScoreUpdateService(projection));
        assertThrows(InvalidScoreUpdateMessageException.class, () ->
                listener.onMessage(new Message("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), new MessageProperties())));
        verifyNoInteractions(template);
        assertTrue(projection.findSnapshot("m1").isEmpty());
    }

    @Test
    void failedPublicationCanBeRetriedAndDuplicatesDoNotPublishAgain() {
        var output = mock(MatchSnapshotPublisher.class);
        doThrow(new IllegalStateException("temporarily unavailable")).doNothing().when(output).publish(any());
        var projection = new InMemoryDashboardProjection(10, output);
        var service = new ProcessScoreUpdateService(projection);
        assertThrows(IllegalStateException.class, () -> service.process(update));
        assertTrue(projection.findSnapshot("m1").isEmpty());
        service.process(update);
        service.process(update);
        var attempts = ArgumentCaptor.forClass(MatchSnapshot.class);
        verify(output, times(2)).publish(attempts.capture());
        assertEquals(attempts.getAllValues().get(0), attempts.getAllValues().get(1));
        assertEquals(1, projection.findSnapshot("m1").orElseThrow().revision());
    }
}
