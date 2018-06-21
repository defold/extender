package com.defold.extender;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.ServerConnector;
import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.autoconfigure.ExportMetricWriter;
import org.springframework.boot.actuate.endpoint.MetricsEndpoint;
import org.springframework.boot.actuate.endpoint.MetricsEndpointMetricReader;
import org.springframework.boot.actuate.metrics.writer.GaugeWriter;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.embedded.ConfigurableEmbeddedServletContainer;
import org.springframework.boot.context.embedded.EmbeddedServletContainerCustomizer;
import org.springframework.boot.context.embedded.jetty.JettyEmbeddedServletContainerFactory;
import org.springframework.boot.context.embedded.jetty.JettyServerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.net.InetAddress;
import java.net.UnknownHostException;

@SpringBootApplication
public class ExtenderApplication {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExtenderApplication.class);

    private final Environment environment;
    private final int idleTimeout;

    @Autowired
    public ExtenderApplication(Environment environment, @Value("${extender.server.http.idle-timeout}") int idleTimeout) {
        this.environment = environment;
        this.idleTimeout = idleTimeout;
    }

    public static void main(String[] args) {
        SpringApplication.run(ExtenderApplication.class, args);
    }

    @Bean
    @ExportMetricWriter
    GaugeWriter influxMetricsWriter() {

        try {
            InfluxDB influxDB = InfluxDBFactory.connect("http://metrics.defold.com:8086", "root", "root");

            String dbName = "myMetricsDB"; // the name of the datastore you choose
            influxDB.createDatabase(dbName);

            InfluxDBMetricWriter.Builder builder = new InfluxDBMetricWriter.Builder(influxDB);

            String hostName;
            try {
                hostName = InetAddress.getLocalHost().getHostName();
            } catch (UnknownHostException e) {
                hostName = "Unknown";
            }

            String environmentString = "local";
            if (this.environment.getActiveProfiles().length > 0) {
                environmentString = environment.getActiveProfiles()[0];
            }

            builder
                    .withDatabaseName(dbName)
                    .withBatchActions(500)
                    .withReportingEnvironment(environmentString)
                    .withReportingHostname(hostName)
                    .withReportingService("Extender");

            return builder.build();
        } catch (Exception e) {
            LOGGER.warn("Failed to create metrics writer. No metrics will be reported.");
            return value -> { /* Just ignore metrics */ };
        }
    }

    @Bean
    public MetricsEndpointMetricReader metricsEndpointMetricReader(MetricsEndpoint metricsEndpoint) {
        return new MetricsEndpointMetricReader(metricsEndpoint);
    }

    // Spring Boot only supports a subset of Jetty configuration props, so configure idle timeout programatically
    @Bean
    public EmbeddedServletContainerCustomizer customizer() {
        return new EmbeddedServletContainerCustomizer() {

            @Override
            public void customize(ConfigurableEmbeddedServletContainer container) {
                if (container instanceof JettyEmbeddedServletContainerFactory) {
                    customizeJetty((JettyEmbeddedServletContainerFactory) container);
                }
            }

            private void customizeJetty(JettyEmbeddedServletContainerFactory jetty) {
                jetty.addServerCustomizers((JettyServerCustomizer) server -> {
                    for (Connector connector : server.getConnectors()) {
                        if (connector instanceof ServerConnector) {
                            HttpConnectionFactory connectionFactory = connector
                                    .getConnectionFactory(HttpConnectionFactory.class);
                            connectionFactory.getHttpConfiguration()
                                    .setIdleTimeout(idleTimeout);
                        }
                    }
                });
            }
        };

    }
}
