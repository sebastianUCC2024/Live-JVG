package com.live_jgv.worker.academic;

import com.live_jgv.worker.application.AtomicAuthorizationPort;
import com.live_jgv.worker.domain.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AcademicSafetyTest {
    @Test void S01_disabledAcceptanceRemainsBlockedEvenWithOpenEvidence(){
        var now=Instant.parse("2026-11-17T00:00:00Z");
        var command=new SimulatedCommand("c","o","m","sim",now);
        var evidence=new MarketEvidence("m","source","epoch",1,MarketEvidence.Status.OPEN,now,now.plusSeconds(3));
        var result=new AcceptancePolicy(false,Duration.ofSeconds(5),Duration.ofMinutes(5)).decide(command,evidence,MarketGuard.unknown("m"),now);
        assertEquals(Operation.State.BLOCKED,result.state());
    }
    @Test void S02_disabledAuthorityCannotCreateAGrant(){
        var session=AtomicAuthorizationPort.disabled().lock(new SimulatedCommand("c","o","m","sim",Instant.EPOCH));
        assertFalse(session.authoritative());assertFalse(session.recordGrant("academic action is not evidence").allowed());
    }
}
