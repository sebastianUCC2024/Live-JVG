package com.live_jgv.dashboard.infrastructure.config;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;
import com.live_jgv.dashboard.infrastructure.amqp.DurableScoreRecovery;
import com.live_jgv.dashboard.domain.model.InvalidScoreUpdateException;
import java.util.Map;
import java.time.Duration;
/** Declara el exchange topic y bindings de marcadores; usa DLX direct, routing rejected y ACK automático. */
@org.springframework.context.annotation.Profile("!memory")
@Configuration(proxyBeanMethods=false)
@EnableRabbit
public class MessagingConfiguration{
 @Bean TopicExchange updates(@Value("${live.exchange}")String name){return new TopicExchange(name,true,false);}
 @Bean Queue scores(@Value("${live.queue}")String name){return new Queue(name,true);}
 @Bean Binding scoreBinding(Queue scores,TopicExchange updates,@Value("${live.match-id}")String match){return BindingBuilder.bind(scores).to(updates).with("score."+match);}
 @Bean Declarables demoRoundScoreBindings(Queue scores, TopicExchange updates) {
  var bindings = new java.util.ArrayList<Declarable>();
  for (int match = 1; match <= 6; match++) {
   String routingKey = "score.demo-col-v4-00" + match + ".*";
   bindings.add(BindingBuilder.bind(scores).to(updates).with(routingKey));
  }
  return new Declarables(bindings);
 }
 @Bean Declarables durableFailureTopology(@Value("${live.queue}") String queue) {
  String dlx=queue+".dlx", dlq=queue+".dlq";
  return new Declarables(new DirectExchange(dlx,true,false),QueueBuilder.durable(dlq).build(),
      new Binding(dlq,Binding.DestinationType.QUEUE,dlx,"rejected",null));
 }
 @Bean DurableScoreRecovery durableScoreRecovery(RabbitTemplate template, RabbitListenerEndpointRegistry registry,
      @Value("${live.queue}") String queue,@Value("${live.retry.confirm-timeout:5s}") Duration timeout) {
  return new DurableScoreRecovery(template,queue+".dlx",timeout,()->{
   var container=registry.getListenerContainer("durableScores");
   if(container!=null) container.stop(()->{});
  });
 }
 @Bean RetryOperationsInterceptor durableScoreRetry(DurableScoreRecovery recovery,
      @Value("${live.retry.max-attempts:3}") int attempts,
      @Value("${live.retry.interval-ms:1000}") long interval) {
  if(attempts<1||interval<1)throw new IllegalArgumentException("Positive retry settings required");
  var policy=new SimpleRetryPolicy(attempts,Map.of(
      IllegalArgumentException.class,false,InvalidScoreUpdateException.class,false),true,true);
  return RetryInterceptorBuilder.stateless().retryPolicy(policy).backOffOptions(interval,2.0,Math.multiplyExact(interval,4))
      .recoverer(recovery).build();
 }
 @Bean SimpleRabbitListenerContainerFactory durableScoresFactory(
      SimpleRabbitListenerContainerFactoryConfigurer configurer,ConnectionFactory connectionFactory,
      RetryOperationsInterceptor durableScoreRetry) {
  var factory=new SimpleRabbitListenerContainerFactory();
  configurer.configure(factory,connectionFactory);
  factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
  factory.setConcurrentConsumers(1);factory.setMaxConcurrentConsumers(1);factory.setPrefetchCount(1);
  factory.setDefaultRequeueRejected(false);factory.setAdviceChain(durableScoreRetry);
  return factory;
 }
}
