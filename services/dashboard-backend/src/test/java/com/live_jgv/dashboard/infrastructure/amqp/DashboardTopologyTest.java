package com.live_jgv.dashboard.infrastructure.amqp;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import static org.junit.jupiter.api.Assertions.*;

class DashboardTopologyTest {
    @Test
    void declaresDurableTopicQueueAndSeparateDeadLetterRoute() {
        var topology = new DashboardRabbitConfiguration().dashboardTopology(
                "live_updates", "dashboard.score-updates", "score.#", "dashboard.score-updates.dlx",
                "dashboard.score-updates.dlq", "rejected");
        var queues = topology.getDeclarablesByType(Queue.class);
        assertEquals(2, queues.size());
        assertTrue(queues.stream().allMatch(q -> q.isDurable() && !q.isExclusive() && !q.isAutoDelete()));
        assertEquals("dashboard.score-updates.dlx", queues.get(0).getArguments().get("x-dead-letter-exchange"));
        assertEquals("rejected", queues.get(0).getArguments().get("x-dead-letter-routing-key"));
        assertNull(queues.get(1).getArguments().get("x-dead-letter-exchange"));
        var exchange = topology.getDeclarablesByType(TopicExchange.class).get(0);
        assertEquals("live_updates", exchange.getName());
        assertTrue(exchange.isDurable());
        var bindings = topology.getDeclarablesByType(Binding.class);
        assertEquals("score.#", bindings.get(0).getRoutingKey());
        assertEquals("dashboard.score-updates", bindings.get(0).getDestination());
        assertEquals("rejected", bindings.get(1).getRoutingKey());
        assertEquals("dashboard.score-updates.dlq", bindings.get(1).getDestination());
    }
}
