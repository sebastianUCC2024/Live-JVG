package com.live_jgv.dashboard.infrastructure.http;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import java.time.Duration;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.JsonParser;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Collections;
/**
 * HTTP implementation of simulation publication with bounded connection and read timeouts.
 * Preserves the upstream event identity and exposes uncertain publication through the existing
 * HTTP error contract, allowing callers to recover instead of inventing a new event on retry.
 */
@org.springframework.context.annotation.Profile("!memory")
@Component
public class ProducerGateway implements com.live_jgv.dashboard.application.port.out.SimulationPublisher{
 private final RestClient client;
 private static final java.util.List<String> RECOVERY_EVENT_FIELDS=java.util.List.of("event_id","match_id","event_type","team_id","minute","timestamp","reference_event_id");
 private final ObjectMapper recoveryMapper=new ObjectMapper()
     .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
 public ProducerGateway(String url){this(url,Duration.ofSeconds(2),Duration.ofSeconds(5));}
 @Autowired
 public ProducerGateway(@Value("${producer.url}")String url,
     @Value("${producer.connect-timeout:2s}")Duration connectTimeout,
     @Value("${producer.read-timeout:5s}")Duration readTimeout){
  var factory=new SimpleClientHttpRequestFactory();
  factory.setConnectTimeout(boundedMillis(connectTimeout));factory.setReadTimeout(boundedMillis(readTimeout));
  client=RestClient.builder().baseUrl(url).requestFactory(factory).build();
 }
 private static int boundedMillis(Duration timeout){
  try{int millis=Math.toIntExact(timeout.toMillis());if(millis<1)throw new IllegalArgumentException("Timeout must be positive");return millis;}
  catch(ArithmeticException e){throw new IllegalArgumentException("Timeout exceeds supported millisecond range",e);}
 }
 public java.util.List<String> startRound(java.util.UUID id) {
  try {
   var ids = client.post().uri("/internal/simulation/rounds/{id}", id).retrieve().body(String[].class);
   if (ids == null || ids.length == 0) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
   return java.util.List.of(ids);
  } catch (org.springframework.web.client.RestClientException failure) {
   throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "No se pudo iniciar la ronda.", failure);
  }
 }
 public Map<?,?> publish(String match,Map<String,Object> event){
  try{return client.post().uri("/api/matches/{id}/events",match).contentType(MediaType.APPLICATION_JSON).body(event).retrieve().body(Map.class);}
  catch(org.springframework.web.client.RestClientResponseException e){
   var recoverable=recovery(e,match,event);
   if(recoverable!=null)throw recoverable;
   boolean rejected=Set.of(400,401,403,404,405,415,422).contains(e.getStatusCode().value());
   throw new ResponseStatusException(e.getStatusCode(),rejected?"El productor rechazó la solicitud; verifica el evento."
       :"Resultado incierto del productor. Conserva la operación y no reintentes con otro ID.");
  }
  catch(org.springframework.web.client.RestClientException e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"No se pudo confirmar el envío al productor. No reintentes con otro ID.");}
 }
 /** Local HTTP error DTO; never forward arbitrary upstream exception bodies. */
 public static final class PublicationFailure extends ResponseStatusException {
  private final Map<String,Object> body;
  private PublicationFailure(HttpStatusCode status,Map<String,Object> body){
   super(status);this.body=Collections.unmodifiableMap(body);
  }
  public Map<String,Object> body(){return body;}
 }
 private PublicationFailure recovery(org.springframework.web.client.RestClientResponseException failure,String match,Map<String,Object> sent){
  int status=failure.getStatusCode().value();
  if(status!=500&&status!=503)return null;
  byte[] bytes=failure.getResponseBodyAsByteArray();
  if(bytes.length>65536)return null;
  try{
   var root=recoveryMapper.readTree(bytes);
   String code=status==500?"EVENT_PREPARATION_FAILED":"PUBLICATION_UNCONFIRMED";
   if(root==null||!root.isObject()||!code.equals(root.path("code").asText()))return null;
   var body=new LinkedHashMap<String,Object>();body.put("code",code);
   body.put("error",status==500?"No se pudo preparar el evento. Conserva la operación original antes de reintentar."
       :"Publicación no confirmada. Conserva el mismo evento sin cambiar su identidad.");
   var returned=root.get("event");
   if(returned!=null&&!returned.isNull()){
    if(validRecoveredEvent(returned,match,sent)){
     var safeEvent=new LinkedHashMap<String,Object>();
     for(String field:RECOVERY_EVENT_FIELDS) safeEvent.put(field,recoveryMapper.convertValue(returned.get(field),Object.class));
     body.put("event",safeEvent);
    }else body.put("error","La respuesta del productor contiene un evento incompatible. Conserva la operación original; no sustituyas su identidad.");
   }
   return new PublicationFailure(failure.getStatusCode(),body);
  }catch(java.io.IOException|IllegalArgumentException invalid){return null;}
 }
 private boolean validRecoveredEvent(JsonNode event,String match,Map<String,Object> sent){
  if(!event.isObject()||!RECOVERY_EVENT_FIELDS.stream().allMatch(event::has))return false;
  for(String field:java.util.List.of("event_id","match_id","event_type","timestamp"))
   if(!event.get(field).isTextual()||event.get(field).textValue().isBlank())return false;
  if(!match.equals(event.get("match_id").textValue())
      ||!Set.of("GOAL","VAR_CHECK","GOAL_ANNULLED","MATCH_CONTINUES").contains(event.get("event_type").textValue()))return false;
  var minute=event.get("minute");
  if(!minute.isIntegralNumber()||!minute.canConvertToInt()||minute.intValue()<0||minute.intValue()>130)return false;
  for(String field:java.util.List.of("team_id","reference_event_id")){
   var value=event.get(field);if(!value.isNull()&&(!value.isTextual()||value.textValue().isBlank()))return false;
  }
  if(event.get("event_type").asText().equals("GOAL")&&event.get("team_id").isNull())return false;
  if(event.get("event_type").asText().equals("GOAL_ANNULLED")&&event.get("reference_event_id").isNull())return false;
  var original=recoveryMapper.valueToTree(sent);
  if(original.hasNonNull("event_id")!=original.hasNonNull("timestamp"))return false;
  for(String field:java.util.List.of("event_type","team_id","reference_event_id")){
   var expected=original.get(field);
   if(expected==null? !event.get(field).isNull():!expected.equals(event.get(field)))return false;
  }
  if(!original.path("minute").isIntegralNumber()||!original.path("minute").canConvertToInt()
      ||original.path("minute").intValue()!=minute.intValue())return false;
  if(original.hasNonNull("event_id")&&!original.get("event_id").equals(event.get("event_id")))return false;
  try{
   var returnedTime=Instant.parse(event.get("timestamp").textValue());
   return !original.hasNonNull("timestamp")||(original.get("timestamp").isTextual()
       &&returnedTime.equals(Instant.parse(original.get("timestamp").textValue())));
  }catch(java.time.DateTimeException invalid){return false;}
 }
}
