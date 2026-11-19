package com.live_jgv.worker.application;

import com.live_jgv.worker.domain.*;

/** Mantiene la fila de origen bloqueada dentro de la transacción del worker. */
public interface AtomicAuthorizationPort {
    record Grant(boolean allowed,String reason) { }
    interface Session {
        boolean authoritative();
        MarketEvidence evidence();
        String refusal();
        Grant recordGrant(String canonicalPayload);
    }
    Session lock(SimulatedCommand command);
    default void verifyInstallation() { }
    static AtomicAuthorizationPort disabled(){
        return command->new Session(){
            public boolean authoritative(){return false;}
            public MarketEvidence evidence(){return MarketEvidence.unavailable(command.matchId());}
            public String refusal(){return "ATOMIC_AUTHORIZATION_REQUIRED";}
            public Grant recordGrant(String canonical){return new Grant(false,refusal());}
        };
    }
}
