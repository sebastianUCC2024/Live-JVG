package com.live_jgv.matchstate.application.port.out;

import com.live_jgv.matchstate.domain.model.ScoreUpdate;
import java.util.List;
import java.util.Optional;
import com.live_jgv.matchstate.domain.model.StoredScoreUpdate;

/** Gestiona publicaciones pendientes; un fallo entre envío y marcado puede generar duplicados. */
public interface ScoreUpdateOutbox {
    /** Mantiene el bloqueo de publicación hasta finalizar la operación. */
    default void withPublicationLock(String matchId, Runnable publication) { publication.run(); }
    /** Consulta el siguiente lote en orden de aceptación, bajo el bloqueo de publicación. */
    List<ScoreUpdate> pendingForMatch(String matchId);
    void markPublished(ScoreUpdate update);
    default Optional<StoredScoreUpdate> storedPublication(ScoreUpdate update) {
        return Optional.empty();
    }
}
