package com.live_jgv.dashboard;
import com.live_jgv.dashboard.infrastructure.http.ProducerGateway;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** Local HTTP stub in the test JVM; no real producer, broker or database. */
@Timeout(15)
class ProducerGatewayTimeoutTest {
    private HttpServer stub;
    private ExecutorService executor;
    private final CountDownLatch release=new CountDownLatch(1);
    private final List<String> bodies=new CopyOnWriteArrayList<>();
    private final AtomicInteger count=new AtomicInteger();
    private final Map<String,Object> event=Map.of("event_id","stable-id","timestamp","2026-11-15T00:00:00Z","event_type","GOAL");

    private ProducerGateway gateway(boolean slowBody,int status) throws Exception {
        stub=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        executor=Executors.newCachedThreadPool(r->{var thread=new Thread(r);thread.setDaemon(true);return thread;});
        stub.setExecutor(executor);
        stub.createContext("/api/matches/m/events",exchange->{
            try {
                bodies.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                int attempt=count.incrementAndGet();
                byte[] response="{\"event_id\":\"stable-id\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                if(attempt==1&&status==202&&!slowBody)release.await(3,TimeUnit.SECONDS);
                exchange.sendResponseHeaders(status,response.length);
                if(attempt==1&&status==202&&slowBody){exchange.getResponseBody().flush();release.await(3,TimeUnit.SECONDS);}
                exchange.getResponseBody().write(response);
            }catch(java.io.IOException expectedClientTimeout){/* The caller may have abandoned the response, not the operation. */}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            finally{exchange.close();}
        });
        stub.start();
        return new ProducerGateway("http://127.0.0.1:"+stub.getAddress().getPort(),Duration.ofSeconds(1),Duration.ofMillis(500));
    }
    @AfterEach void close(){release.countDown();if(stub!=null)stub.stop(0);if(executor!=null)executor.shutdownNow();}

    @Test void slowProducerTimesOutAsUncertainAndExplicitRetryKeepsTheSameBody() throws Exception {
        var gateway=gateway(false,202);
        var error=assertThrows(ResponseStatusException.class,()->gateway.publish("m",event));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,error.getStatusCode());
        assertEquals(1,count.get()); // No application-level automatic retry.
        release.countDown();
        assertEquals("stable-id",gateway.publish("m",event).get("event_id"));
        assertEquals(2,bodies.size());assertEquals(bodies.get(0),bodies.get(1));
        assertEquals("stable-id",event.get("event_id"));
    }
    @Test void responseBodyTimeoutIsAlsoUncertain() throws Exception {
        var gateway=gateway(true,202);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(ResponseStatusException.class,()->gateway.publish("m",event)).getStatusCode());
        assertEquals(1,count.get());
    }
    @Test void definiteProducerRejectionKeepsItsHttpStatus() throws Exception {
        var gateway=gateway(false,400);
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,()->gateway.publish("m",event)).getStatusCode());
        assertEquals(1,count.get());
    }
    @Test void zeroNegativeSubMillisecondAndOverflowTimeoutsFailFast(){
        for(var timeout:List.of(Duration.ZERO,Duration.ofMillis(-1),Duration.ofNanos(1),Duration.ofDays(30))) {
            assertThrows(IllegalArgumentException.class,()->new ProducerGateway("http://unused.invalid",timeout,Duration.ofSeconds(1)));
            assertThrows(IllegalArgumentException.class,()->new ProducerGateway("http://unused.invalid",Duration.ofSeconds(1),timeout));
        }
    }
}
