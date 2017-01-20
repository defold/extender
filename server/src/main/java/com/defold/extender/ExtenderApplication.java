package com.defold.extender;

import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.autoconfigure.ExportMetricWriter;
import org.springframework.boot.actuate.endpoint.MetricsEndpoint;
import org.springframework.boot.actuate.endpoint.MetricsEndpointMetricReader;
import org.springframework.boot.actuate.metrics.writer.GaugeWriter;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

@SpringBootApplication
public class ExtenderApplication {
    public static void main(String[] args) throws IOException, InterruptedException {
        SpringApplication.run(ExtenderApplication.class, args);
    }

    @Bean
    @ExportMetricWriter
    GaugeWriter influxMetricsWriter() {

        InfluxDB influxDB = InfluxDBFactory.connect("http://metrics.defold.com:8086", "root", "root");

        String dbName = "myMetricsDB";	// the name of the datastore you choose
        influxDB.createDatabase(dbName);



        InfluxDBMetricWriter.Builder builder = new InfluxDBMetricWriter.Builder(influxDB);

        String hostName;
        try {
            hostName = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            hostName = "Unknown";
        }

        builder
                .withDatabaseName(dbName)
                .withBatchActions(500)
                .withReportingEnvironment("local")
                .withReportingHostname(hostName);

        return builder.build();
    }

    @Bean
    public MetricsEndpointMetricReader metricsEndpointMetricReader(MetricsEndpoint metricsEndpoint) {
        return new MetricsEndpointMetricReader(metricsEndpoint);
    }
}

