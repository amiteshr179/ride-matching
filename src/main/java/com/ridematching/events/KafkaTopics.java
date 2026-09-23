package com.ridematching.events;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopics {

    @Bean
    NewTopic tripEventsTopic() {
        return TopicBuilder.name(TripEventPublisher.TOPIC).partitions(3).replicas(1).build();
    }
}
