package com.defold.extender;

import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.autoconfigure.ExportMetricWriter;
import org.springframework.boot.actuate.endpoint.MetricsEndpoint;
import org.springframework.boot.actuate.endpoint.MetricsEndpointMetricReader;
import org.springframework.boot.actuate.metrics.writer.GaugeWriter;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

@SpringBootApplication
public class ExtenderApplication {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExtenderApplication.class);
    private final Environment environment;

    @Autowired
    public ExtenderApplication(Environment environment) {
        this.environment = environment;
    }

    public static void main(String[] args) throws IOException, InterruptedException {
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
}
