package com.live_jgv.dashboard;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
@Component
public class ProducerGateway{
 private final RestClient client;
 public ProducerGateway(@Value("${producer.url}")String url){client=RestClient.builder().baseUrl(url).build();}
 public Map<?,?> publish(String match,Map<String,Object> event){
  try{return client.post().uri("/api/matches/{id}/events",match).contentType(MediaType.APPLICATION_JSON).body(event).retrieve().body(Map.class);}
  catch(org.springframework.web.client.RestClientResponseException e){throw new ResponseStatusException(e.getStatusCode(),"El productor rechazó la solicitud; verifica el evento o la conexión Kafka.");}
  catch(org.springframework.web.client.RestClientException e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"No se pudo confirmar el envío al productor. No reintentes con otro ID.");}
 }
}
