package com.live_jgv.notification;
import com.live_jgv.notification.infrastructure.json.LiveAlertCodec;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.net.URLClassLoader;
import java.time.Instant;
import java.util.Optional;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;
class ProducerLiveAlertCompatibilityTest {
    @Test @SuppressWarnings({"unchecked","rawtypes"})
    void decoderAcceptsTheRealMatchStateRecordWithoutChangingScoreUpdate() throws Exception {
        Path source=Path.of("..","match-state-service","src","main","java","com","live_jgv","matchstate","domain","model");
        Path output=Path.of("target","live-alert-producer-classes");Files.createDirectories(output);
        var compiler=ToolProvider.getSystemJavaCompiler();assertNotNull(compiler,"JDK required");
        assertEquals(0,compiler.run(null,null,null,"-d",output.toString(),source.resolve("EventType.java").toString(),source.resolve("VarStatus.java").toString(),source.resolve("ScoreUpdate.java").toString(),source.resolve("LiveAlertV1.java").toString()));
        try(var loader=new URLClassLoader(new java.net.URL[]{output.toUri().toURL()},ClassLoader.getPlatformClassLoader())) {
            var type=loader.loadClass("com.live_jgv.matchstate.domain.model.EventType");
            var status=loader.loadClass("com.live_jgv.matchstate.domain.model.VarStatus");
            var score=loader.loadClass("com.live_jgv.matchstate.domain.model.ScoreUpdate");
            var alert=loader.loadClass("com.live_jgv.matchstate.domain.model.LiveAlertV1");
            var mapper=new ObjectMapper().registerModule(new JavaTimeModule()).setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            var wireImages=mapper.createArrayNode();
            for(String event:new String[]{"GOAL","VAR_CHECK","GOAL_ANNULLED"}) {
                var snapshot=score.getConstructors()[0].newInstance("match",1,0,"source",Enum.valueOf((Class)type,event),Instant.parse("2026-11-17T10:00:00.123456789Z"),Enum.valueOf((Class)status,"IN_PROGRESS"),event.equals("GOAL_ANNULLED")?"goal":null);
                var wire=((Optional<?>)alert.getMethod("create",score).invoke(null,snapshot)).orElseThrow();
                JsonNode image=mapper.valueToTree(wire);wireImages.add(image);
                var decoded=new LiveAlertCodec().decode(mapper.writeValueAsBytes(wire));
                assertEquals(event,decoded.eventType().name());assertEquals("source",decoded.sourceEventId());
                assertEquals(1,decoded.snapshot().homeScore());assertEquals(decoded.timestamp(),decoded.snapshot().updatedAt());
                if(event.equals("GOAL_ANNULLED")) assertEquals("goal",decoded.referenceEventId());
                assertEquals(8,mapper.valueToTree(snapshot).size());
            }
            Files.writeString(Path.of("target","live-alert-wire-fixtures.json"),mapper.writeValueAsString(wireImages));
        }
    }
}
