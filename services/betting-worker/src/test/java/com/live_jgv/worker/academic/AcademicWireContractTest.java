package com.live_jgv.worker.academic;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class AcademicWireContractTest {
 @Test void goldenProducerWireIsCompatibleWithWorker() throws Exception {var json=new com.fasterxml.jackson.databind.ObjectMapper();
 try(var stream=getClass().getResourceAsStream("/contracts/academic-v1.json")){assertNotNull(stream);var fixture=json.readTree(stream);var codec=new AcademicCommandCodec();for(var node:fixture){var command=codec.decode(json.writeValueAsBytes(node));assertEquals(node,json.readTree(command.json()));assertEquals(6,node.size());}}
 }
}
