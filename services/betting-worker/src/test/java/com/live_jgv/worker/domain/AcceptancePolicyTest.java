package com.live_jgv.worker.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class AcceptancePolicyTest {
    private final Instant now=Instant.parse("2026-11-15T00:00:00Z");
    private final SimulatedCommand command=new SimulatedCommand("c","o","m","sim-user",now);
    private final AcceptancePolicy policy=new AcceptancePolicy(true,Duration.ofSeconds(5),Duration.ofMinutes(5));
    private MarketEvidence proof(long revision,MarketEvidence.Status status){return new MarketEvidence("m","authority","epoch-1",revision,status,now.minusSeconds(1),now.plusSeconds(3));}
    @Test void freshOpenFromApprovedPortCanBeAccepted(){
        var decision=policy.decide(command,proof(1,MarketEvidence.Status.OPEN),MarketGuard.unknown("m"),now);
        assertEquals(Operation.State.ACCEPTED,decision.state());assertEquals(1,decision.guard().evidence().revision());
    }
    @ParameterizedTest @EnumSource(value=MarketEvidence.Status.class,names={"SUSPENDED","UNKNOWN"})
    void blockedMarketNeverAccepts(MarketEvidence.Status status){assertEquals(Operation.State.BLOCKED,policy.decide(command,proof(2,status),MarketGuard.unknown("m"),now).state());}
    @Test void absentStateNeverMeansOpen(){assertEquals(Operation.State.BLOCKED,policy.decide(command,MarketEvidence.unavailable("m"),MarketGuard.unknown("m"),now).state());}
    @Test void lowerOpenCannotReopenAfterMoreRecentSuspension(){
        var suspended=new MarketGuard(proof(10,MarketEvidence.Status.SUSPENDED),false,1);
        var result=policy.decide(command,proof(9,MarketEvidence.Status.OPEN),suspended,now);
        assertEquals(Operation.State.BLOCKED,result.state());assertEquals(suspended,result.guard());assertEquals("STALE_MARKET_REVISION",result.reason());
    }
    @Test void higherTrustedFreshOpenCanReplaceOlderSuspension(){
        var previous=new MarketGuard(proof(1,MarketEvidence.Status.SUSPENDED),false,1);
        assertEquals(Operation.State.ACCEPTED,policy.decide(command,proof(2,MarketEvidence.Status.OPEN),previous,now).state());
    }
    @Test void duplicateEvidenceCannotRenewOriginalExpiry(){
        var proof=proof(3,MarketEvidence.Status.OPEN);var guard=new MarketGuard(proof,false,2);
        var result=policy.decide(new SimulatedCommand("c","o","m","u",now.plusSeconds(4)),proof,guard,now.plusSeconds(4));
        assertEquals(Operation.State.BLOCKED,result.state());assertEquals(guard,result.guard());
    }
    @Test void conflictingSameRevisionFreezesMarketRatherThanReopening(){
        var stored=new MarketGuard(proof(1,MarketEvidence.Status.SUSPENDED),false,1);
        var result=policy.decide(command,proof(1,MarketEvidence.Status.OPEN),stored,now);
        assertTrue(result.guard().frozen());assertEquals(Operation.State.BLOCKED,result.state());
        assertEquals(Operation.State.BLOCKED,policy.decide(command,proof(2,MarketEvidence.Status.OPEN),result.guard(),now).state());
    }
    @Test void authorityOrEpochChangeRequiresReconciliation(){
        var stored=new MarketGuard(proof(4,MarketEvidence.Status.SUSPENDED),false,1);
        var other=new MarketEvidence("m","authority","epoch-2",5,MarketEvidence.Status.OPEN,now,now.plusSeconds(2));
        assertTrue(policy.decide(command,other,stored,now).guard().frozen());
    }
    @Test void excessiveAgeFutureProofAndOverlongLeaseCannotAccept(){
        for(var proof:java.util.List.of(
            new MarketEvidence("m","a","e",1,MarketEvidence.Status.OPEN,now.minusSeconds(6),now.plusSeconds(1)),
            new MarketEvidence("m","a","e",1,MarketEvidence.Status.OPEN,now.plusSeconds(1),now.plusSeconds(2)),
            new MarketEvidence("m","a","e",1,MarketEvidence.Status.OPEN,now,now.plusSeconds(6))))
            assertEquals(Operation.State.BLOCKED,policy.decide(command,proof,MarketGuard.unknown("m"),now).state());
    }
    @Test void expiredProofCannotAcceptEvenWithHigherVersion(){
        var expired=new MarketEvidence("m","a","e",1,MarketEvidence.Status.OPEN,now.minusSeconds(3),now);
        assertEquals(Operation.State.BLOCKED,policy.decide(command,expired,MarketGuard.unknown("m"),now).state());
    }
    @Test void disabledAcceptanceNeverAcceptsFreshOpen(){
        var disabled=new AcceptancePolicy(false,Duration.ofSeconds(5),Duration.ofMinutes(5));
        assertEquals(Operation.State.BLOCKED,disabled.decide(command,proof(1,MarketEvidence.Status.OPEN),MarketGuard.unknown("m"),now).state());
    }
    @Test void proofForAnotherMatchFailsOperationWithoutAcceptance(){
        var wrong=new MarketEvidence("other","a","e",1,MarketEvidence.Status.OPEN,now,now.plusSeconds(1));
        assertEquals(Operation.State.FAILED,policy.decide(command,wrong,MarketGuard.unknown("m"),now).state());
    }
    @Test void oldOrFutureCommandIsRejected(){
        for(var time:java.util.List.of(now.minusSeconds(301),now.plusSeconds(1))){
            var c=new SimulatedCommand("c","o","m","u",time);
            assertEquals(Operation.State.REJECTED,policy.decide(c,proof(1,MarketEvidence.Status.OPEN),MarketGuard.unknown("m"),now).state());
        }
    }
}
