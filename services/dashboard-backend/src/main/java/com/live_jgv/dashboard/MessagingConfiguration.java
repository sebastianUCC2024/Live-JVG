package com.live_jgv.dashboard;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
@org.springframework.context.annotation.Profile("!memory")
@Configuration(proxyBeanMethods=false)
@EnableRabbit
public class MessagingConfiguration{
 @Bean TopicExchange updates(@Value("${live.exchange}")String name){return new TopicExchange(name,true,false);}
 @Bean Queue scores(@Value("${live.queue}")String name){return new Queue(name,true);}
 @Bean Binding scoreBinding(Queue scores,TopicExchange updates,@Value("${live.match-id}")String match){return BindingBuilder.bind(scores).to(updates).with("score."+match);}
}
