package com.jitsimeet.api.lifecycle;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import javax.sql.DataSource;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "lifecycle.enabled", havingValue = "true")
@EnableConfigurationProperties(LifecycleProperties.class)
class LifecycleConfiguration {
    @Bean LifecycleProtocol lifecycleProtocol(LifecycleProperties properties) {
        return new LifecycleProtocol(properties, Clock.systemUTC());
    }
    @Bean LifecycleStore lifecycleStore(DataSource dataSource, LifecycleProtocol validatedProtocol) {
        return new LifecycleStore(dataSource);
    }
}
