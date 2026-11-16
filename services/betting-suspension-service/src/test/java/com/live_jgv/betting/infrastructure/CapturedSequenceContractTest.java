package com.live_jgv.betting.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.live_jgv.betting.domain.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class CapturedSequenceContractTest {
    @Test void producerGoalVarAnnulContinueAndAwayGoalNeverImplicitlyCloseAReview() throws Exception {
        try(var in=getClass().getResourceAsStream("/fixtures/producer-events.jsonl")) {
            assertNotNull(in);var lines=new String(in.readAllBytes(),StandardCharsets.UTF_8).lines().toList();
            assertEquals(5,lines.size());var mapper=new ObjectMapper();MarketState market=null;
            for(int i=0;i<lines.size();i++) {
                var json=mapper.readTree(lines.get(i));String match=json.path("match_id").asText();
                var raw=new InputRecord("contract/1","match_events",0,i,0,match.getBytes(StandardCharsets.UTF_8),lines.get(i).getBytes(StandardCharsets.UTF_8),"[]");
                var input=new JsonEventDecoder().decode(raw);assertNull(input.reason());
                if(market==null)market=MarketState.unknown(match);
                market=market.apply(input.event(),Instant.EPOCH.plusSeconds(i));assertFalse(market.betsAllowed());
            }
            assertNotNull(market);assertEquals(MarketState.Status.SUSPENDED,market.status());
            assertEquals(1,market.pendingReviews().size());assertFalse(market.inconsistent());
        }
    }
}
