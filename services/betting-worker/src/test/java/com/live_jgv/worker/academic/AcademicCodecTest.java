package com.live_jgv.worker.academic;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;import java.nio.charset.StandardCharsets;
class AcademicCodecTest {
 AcademicCommandCodec codec=new AcademicCommandCodec();
 String json=new AcademicCommand(1,"cmd","match","event",AcademicCommand.CommandType.RESUME_BETS,Instant.EPOCH).json();
 @Test void bothCommandTypesRoundTrip(){for(var type:AcademicCommand.CommandType.values()){var c=new AcademicCommand(1,"cmd","match","event",type,Instant.EPOCH);assertEquals(c,codec.decode(c.json().getBytes(StandardCharsets.UTF_8)));}}
 @Test void unknownOrMissingFieldsRejected(){assertThrows(IllegalArgumentException.class,()->codec.decode(json.replace("{","{\"extra\":1,").getBytes(StandardCharsets.UTF_8)));assertThrows(IllegalArgumentException.class,()->codec.decode("{}".getBytes(StandardCharsets.UTF_8)));}
 @Test void unsupportedVersionAndDuplicateKeysRejected(){assertThrows(IllegalArgumentException.class,()->codec.decode(json.replace("\"schema_version\":1","\"schema_version\":2").getBytes(StandardCharsets.UTF_8)));assertThrows(IllegalArgumentException.class,()->codec.decode(json.replace("{","{\"match_id\":\"other\",").getBytes(StandardCharsets.UTF_8)));}
 @Test void malformedTimestampNullBodyAndOversizeRejected(){assertThrows(IllegalArgumentException.class,()->codec.decode(json.replace("1970-01-01T00:00:00Z","bad").getBytes(StandardCharsets.UTF_8)));assertThrows(IllegalArgumentException.class,()->codec.decode(null));assertThrows(IllegalArgumentException.class,()->codec.decode(new byte[65537]));}
}
