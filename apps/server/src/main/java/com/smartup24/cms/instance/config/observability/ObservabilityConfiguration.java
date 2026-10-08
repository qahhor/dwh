package com.smartup24.cms.instance.config.observability;

import io.micrometer.tracing.Tracer;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Tracing of the application's own code paths (plan 10/10, item 7.2). Spring Boot starts the HTTP, scheduled-task
 * and HTTP-client spans; this adds the JDBC ones. Every data source bean is wrapped only when tracing records
 * anything at all (sampling probability above 0): with the default of 0 the connections stay the pool's own.
 */
@Configuration(proxyBeanMethods = false)
public class ObservabilityConfiguration {

    static final String TRACING_ENABLED = "management.tracing.enabled";
    static final String SAMPLING_PROBABILITY = "management.tracing.sampling.probability";

    @Bean
    static BeanPostProcessor tracingDataSourcePostProcessor(Environment environment, ObjectProvider<Tracer> tracer) {
        boolean recording = environment.getProperty(TRACING_ENABLED, Boolean.class, true)
                && environment.getProperty(SAMPLING_PROBABILITY, Double.class, 0.0) > 0;
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (recording && bean instanceof DataSource dataSource && !(bean instanceof TracingDataSource)) {
                    return new TracingDataSource(dataSource, new JdbcTracing(tracer, beanName));
                }
                return bean;
            }
        };
    }
}
