package com.live_jgv.matchstate.domain.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VarReviewsTest {
    @Test void partialAndTotalClosurePreserveIndependentReviews() {
        var initial = VarReviews.empty();
        var two = initial.open("v1").open("v2");
        var partial = two.close("v1");
        assertEquals(VarStatus.CLEAR, initial.status());
        assertEquals(VarStatus.IN_PROGRESS, partial.status());
        assertTrue(two.reviews().get("v1"));
        assertEquals(VarStatus.CLEAR, partial.close("v2").status());
        assertThrows(UnsupportedOperationException.class, () -> partial.reviews().clear());
    }

    @Test void duplicatesAndLateClosureCannotAffectAnotherReview() {
        var closed = VarReviews.empty().open("v1").close("v1");
        assertSame(closed, closed.open("v1"));
        var next = closed.open("v2");
        assertSame(next, next.close("v1"));
        assertEquals(VarStatus.IN_PROGRESS, next.status());
        assertThrows(IllegalArgumentException.class, () -> next.close("unknown"));
        assertThrows(IllegalArgumentException.class, () -> next.close(null));
        assertEquals(2, next.reviews().size());
    }

    @Test void replayRebuildsExactlyTheSameLedger() {
        var expected = VarReviews.empty().open("v1").open("v2").close("v1");
        var replay = VarReviews.empty().open("v1").open("v2").close("v1").open("v1");
        assertEquals(expected.reviews(), replay.reviews());
        assertEquals(expected.status(), replay.status());
    }
}
