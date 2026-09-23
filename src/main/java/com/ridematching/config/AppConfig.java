package com.ridematching.config;

import com.ridematching.geo.EtaCalculator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(RideProperties.class)
public class AppConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    EtaCalculator etaCalculator(RideProperties props) {
        return new EtaCalculator(props.eta().avgSpeedKmh(), props.eta().detourFactor());
    }
}
