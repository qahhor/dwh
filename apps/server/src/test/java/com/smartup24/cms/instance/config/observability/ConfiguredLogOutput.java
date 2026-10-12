package com.smartup24.cms.instance.config.observability;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.Encoder;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

/** Exercises the encoders selected by the shipped configuration, without changing the JVM's logging system. */
final class ConfiguredLogOutput implements AutoCloseable {

    private final LoggerContext context = new LoggerContext();
    private final Encoder<ILoggingEvent> encoder;

    ConfiguredLogOutput(boolean dev, String destination) throws Exception {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(
                dev
                        ? new ClassPathResource[] {
                            new ClassPathResource("application.yml"), new ClassPathResource("application-dev.yml")
                        }
                        : new ClassPathResource[] {new ClassPathResource("application.yml")});
        var environment = new MockEnvironment();
        yaml.getObject().forEach((key, value) -> environment.setProperty(key.toString(), value.toString()));
        context.putObject(Environment.class.getName(), environment);
        String format = environment.getProperty("logging.structured.format." + destination, "");
        if (format.isEmpty()) {
            // Boot's own default pattern and conversion rules, including its throwable converter.
            var configurator = new JoranConfigurator();
            configurator.setContext(context);
            configurator.doConfigure(new ByteArrayInputStream("""
                    <configuration>
                      <include resource="org/springframework/boot/logging/logback/defaults.xml"/>
                      <include resource="org/springframework/boot/logging/logback/console-appender.xml"/>
                      <root level="INFO"><appender-ref ref="CONSOLE"/></root>
                    </configuration>
                    """.getBytes(StandardCharsets.UTF_8)));
            encoder = ((OutputStreamAppender<ILoggingEvent>)
                            context.getLogger("ROOT").getAppender("CONSOLE"))
                    .getEncoder();
        } else {
            var structured = new StructuredLogEncoder();
            structured.setFormat(format);
            encoder = structured;
        }
        encoder.setContext(context);
        encoder.start();
    }

    String encode(ILoggingEvent event) {
        return new String(encoder.encode(event), StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        encoder.stop();
        context.stop();
    }
}
