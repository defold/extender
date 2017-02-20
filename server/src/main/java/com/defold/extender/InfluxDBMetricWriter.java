package com.defold.extender;

import org.influxdb.InfluxDB;
import org.influxdb.dto.Point;
import org.springframework.boot.actuate.metrics.Metric;
import org.springframework.boot.actuate.metrics.writer.GaugeWriter;

import java.util.concurrent.TimeUnit;

public class InfluxDBMetricWriter implements GaugeWriter {

    private static final String DEFAULT_DATABASE_NAME = "metrics";
    private static final int DEFAULT_BATCH_ACTIONS = 500;
    private static final int DEFAULT_FLUSH_DURATION = 30;

    private final InfluxDB influxDB;
    private final String databaseName;
    private final String reportingHostname;
    private final String reportingEnvironment;

    private InfluxDBMetricWriter(Builder builder) {
        this.influxDB = builder.influxDB;
        this.databaseName = builder.databaseName;
        this.influxDB.createDatabase(this.databaseName);
        this.influxDB.enableBatch( builder.batchActions, builder.flushDuration, builder.flushDurationTimeUnit);
        this.influxDB.setLogLevel( builder.logLevel);
        this.reportingHostname = builder.reportingHostname;
        this.reportingEnvironment = builder.reportingEnvironment;
    }

    @Override
    public void set(Metric<?> value) {
        Point point = Point.measurement( value.getName())
                .time( value.getTimestamp().getTime(), TimeUnit.MILLISECONDS)
                .addField( "value", value.getValue())
                .tag("hostname", reportingHostname)
                .tag("environment", reportingEnvironment)
                .build();
        this.influxDB.write( this.databaseName, null, point);
    }

    public static class Builder {
        private final InfluxDB influxDB;
        private String databaseName = DEFAULT_DATABASE_NAME;
        private int batchActions = DEFAULT_BATCH_ACTIONS;
        private int flushDuration = DEFAULT_FLUSH_DURATION;
        private TimeUnit flushDurationTimeUnit = TimeUnit.SECONDS;
        private InfluxDB.LogLevel logLevel = InfluxDB.LogLevel.BASIC;
        private String reportingHostname;
        private String reportingEnvironment;

        Builder(InfluxDB influxDB) {
            this.influxDB = influxDB;
        }

        public Builder withFlushDuration( int flushDuration, TimeUnit flushDurationTimeUnit) {
            this.flushDuration = flushDuration;
            this.flushDurationTimeUnit = flushDurationTimeUnit;
            return this;
        }

        InfluxDBMetricWriter build() {
            return new InfluxDBMetricWriter(this);
        }

        Builder withDatabaseName(String databaseName) {
            this.databaseName = databaseName;
            return this;
        }

        Builder withBatchActions(int batchActions) {
            this.batchActions = batchActions;
            return this;
        }

        Builder withReportingHostname(String reportingHostname) {
            this.reportingHostname = reportingHostname;
            return this;
        }

        Builder withReportingEnvironment(String reportingEnvironment) {
            this.reportingEnvironment = reportingEnvironment;
            return this;
        }
    }
}
