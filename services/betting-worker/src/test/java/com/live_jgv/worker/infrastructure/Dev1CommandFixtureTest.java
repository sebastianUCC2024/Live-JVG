package com.live_jgv.worker.infrastructure;

import com.live_jgv.worker.domain.TransportMessage;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

/** Snapshots from merged PR22/7788295. Compatibility evidence, not contract approval. */
class Dev1CommandFixtureTest {
    @ParameterizedTest
    @CsvSource({"valid,true","invalid,false","timestamp.invalid,false","unknown-field.invalid,false"})
    void proposedDev1FixturesAgreeWithPrivateDecoder(String fixture,boolean valid) throws Exception {
        try(var in=getClass().getResourceAsStream("/contracts/dev1-pr22/betting-command.v1."+fixture+".json")){
            assertNotNull(in);
            var message=new TransportMessage("fixture-only",null,"unapproved-test-exchange","test",in.readAllBytes(),"{}");
            var result=new JsonCommandDecoder().decode(message);
            assertEquals(valid,result.command()!=null);
            if(valid){assertNull(result.error());assertEquals("c1",result.command().commandId());assertEquals("m1",result.command().matchId());}
            else assertNotNull(result.error());
        }
    }
}
