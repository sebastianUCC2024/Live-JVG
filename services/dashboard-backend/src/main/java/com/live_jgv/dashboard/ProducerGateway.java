package com.live_jgv.dashboard;
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
@org.springframework.context.annotation.Profile("!memory")
@Component
public class ProducerGateway{
 private final RestClient client;
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
 public Map<?,?> publish(String match,Map<String,Object> event){
  try{return client.post().uri("/api/matches/{id}/events",match).contentType(MediaType.APPLICATION_JSON).body(event).retrieve().body(Map.class);}
  catch(org.springframework.web.client.RestClientResponseException e){
   boolean rejected=Set.of(400,401,403,404,405,415,422).contains(e.getStatusCode().value());
   throw new ResponseStatusException(e.getStatusCode(),rejected?"El productor rechazó la solicitud; verifica el evento."
       :"Resultado incierto del productor. Conserva la operación y no reintentes con otro ID.");
  }
  catch(org.springframework.web.client.RestClientException e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"No se pudo confirmar el envío al productor. No reintentes con otro ID.");}
 }
}
