package com.defold.extender.remote;

import com.defold.extender.BuilderConstants;
import com.defold.extender.ExtenderConst;
import com.defold.extender.ExtenderException;
import com.defold.extender.ExtenderUtil;
import com.defold.extender.metrics.MetricsWriter;
import com.defold.extender.services.DataCacheService;
import com.defold.extender.services.GCPInstanceService;
import com.defold.extender.tracing.ExtenderTracerInterceptor;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

import com.defold.extender.Timer;
import com.defold.extender.log.Markers;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpStatus;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.nio.charset.Charset;

@Service
public class RemoteEngineBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(RemoteEngineBuilder.class);

    private GCPInstanceService instanceService;
    private File jobResultLocation;
    private long buildSleepTimeout;
    private long buildResultWaitTimeout;
    private boolean keepJobDirectory = false;
    protected final CloseableHttpClient httpClient;

    public RemoteEngineBuilder(Optional<GCPInstanceService> instanceService,
                            @Value("${extender.job-result.location}") String jobResultLocation,
                            @Value("${extender.remote-builder.build-sleep-timeout:5000}") long buildSleepTimeout,
                            @Value("${extender.remote-builder.build-result-wait-timeout:1200000}") long buildResultWaitTimeout,
                            @Value("${extender.remote-builder.connect-timeout:30000}") int connectTimeout,
                            @Value("${extender.remote-builder.connection-request-timeout:30000}") int connectionRequestTimeout,
                            @Value("${extender.remote-builder.socket-timeout:600000}") int socketTimeout,
                            @Value("${extender.remote-builder.max-connections:${extender.tasks.executor.pool-size:35}}") int maxConnections,
                            @Autowired Tracer tracer,
                            @Autowired Propagator propogator) {
        instanceService.ifPresent(val -> { LOGGER.info("Instance client is initialized"); this.instanceService = val; });
        this.buildSleepTimeout = buildSleepTimeout;
        this.buildResultWaitTimeout = buildResultWaitTimeout;
        this.jobResultLocation = new File(jobResultLocation);
        this.keepJobDirectory = System.getenv("DM_DEBUG_KEEP_JOB_FOLDER") != null || System.getenv("DM_DEBUG_JOB_FOLDER") != null;

        RequestConfig requestConfig = RequestConfig.custom()
            .setConnectTimeout(connectTimeout)
            .setConnectionRequestTimeout(connectionRequestTimeout)
            .setSocketTimeout(socketTimeout)
            .build();

        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(maxConnections);
        connectionManager.setDefaultMaxPerRoute(maxConnections);

        this.httpClient  = HttpClientBuilder
            .create()
            .setConnectionManager(connectionManager)
            .setDefaultRequestConfig(requestConfig)
            .evictExpiredConnections()
            .evictIdleConnections(socketTimeout, TimeUnit.MILLISECONDS)
            .addInterceptorLast(new ExtenderTracerInterceptor(tracer, propogator))
            .build();
    }

    @Async(value="extenderTaskExecutor")
    public void buildAsync(final RemoteInstanceConfig remoteInstanceConfig,
                        final File projectDirectory,
                        final String platform,
                        final String sdkVersion,
                        File jobDirectory, MetricsWriter metricsWriter) throws FileNotFoundException, IOException {

        LOGGER.info("Building engine remotely at {}", remoteInstanceConfig.getUrl());
        String jobName = jobDirectory.getName();
        Thread.currentThread().setName(String.format("async-build-%s", jobName));
        File resultDir = new File(jobResultLocation.getAbsolutePath(), jobName);
        resultDir.mkdirs();

        final HttpEntity httpEntity;
        Timer buildTimer = new Timer();
        buildTimer.start();

        File tmpDirectory = new File(jobDirectory, "tmp");
        tmpDirectory.mkdir();

        File tmpUploadArchive = new File(tmpDirectory, String.format("__remote_upload_%s.zip", ExtenderUtil.generateRandomFileName()));
        tmpUploadArchive.createNewFile();
        try {
            httpEntity = buildHttpEntity(projectDirectory, tmpUploadArchive);
        } catch(IllegalStateException | IOException | ExtenderException e) {
            tmpUploadArchive.delete();
            throw new RemoteBuildException("Failed to add files to multipart request", e);
        }

        try {
            final String serverUrl = String.format("%s/build_async/%s/%s", remoteInstanceConfig.getUrl(), platform, sdkVersion);
            final HttpPost request = new HttpPost(serverUrl);
            request.setEntity(httpEntity);
    
            touchInstance(remoteInstanceConfig.getInstanceId());
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                // copied from ExtenderClient. Think about code deduplication.
                if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                    LOGGER.error(Markers.COMPILATION_ERROR,  "Failed to build source.");
                    File errorFile = new File(resultDir, BuilderConstants.BUILD_ERROR_FILENAME);
                    try (PrintWriter writer = new PrintWriter(errorFile)) {
                        IOUtils.copy(response.getEntity().getContent(), writer, Charset.defaultCharset());
                    }
                    return;
                }

                String jobId = EntityUtils.toString(response.getEntity());
                LOGGER.info(String.format("Remote async build posted. Wait job id: %s", jobId));
                long currentTime = System.currentTimeMillis();
                Integer jobStatus = 0;
                Thread.sleep(buildSleepTimeout);
                while (System.currentTimeMillis() - currentTime < buildResultWaitTimeout) {
                    touchInstance(remoteInstanceConfig.getInstanceId());
                    HttpGet statusRequest = new HttpGet(String.format("%s/job_status?jobId=%s", remoteInstanceConfig.getUrl(), jobId));
                    try (CloseableHttpResponse statusResponse = httpClient.execute(statusRequest)) {
                        jobStatus = Integer.valueOf(EntityUtils.toString(statusResponse.getEntity()));
                    }
                    if (jobStatus != 0) {
                        LOGGER.info(String.format("Job %s status is %d", jobId, jobStatus));
                        break;
                    }
                    Thread.sleep(buildSleepTimeout);
                }
                if (jobStatus == 0) {
                    File errorFile = new File(resultDir, BuilderConstants.BUILD_ERROR_FILENAME);
                    PrintWriter writer = new PrintWriter(errorFile);
                    writer.write(String.format("Job %s result cannot be defined during %d", jobId, buildResultWaitTimeout));
                    writer.close();
                    return;
                }
                touchInstance(remoteInstanceConfig.getInstanceId());
                HttpGet resultRequest = new HttpGet(String.format("%s/job_result?jobId=%s", remoteInstanceConfig.getUrl(), jobId));
                try (CloseableHttpResponse resultResponse = httpClient.execute(resultRequest)) {
                    LOGGER.info(String.format("Job %s result got.", jobId));
                    if (jobStatus == BuilderConstants.JobStatus.SUCCESS.ordinal()) {
                        // Write zip file to result directory
                        File tmpResult = new File(resultDir, BuilderConstants.BUILD_RESULT_FILENAME + ".tmp");
                        try (OutputStream os = new FileOutputStream(tmpResult)) {
                            IOUtils.copy(resultResponse.getEntity().getContent(), os);
                        }
                        File targetResult = new File(resultDir, BuilderConstants.BUILD_RESULT_FILENAME);
                        Files.move(tmpResult.toPath(), targetResult.toPath(), StandardCopyOption.ATOMIC_MOVE);
                    } else {
                        LOGGER.error(Markers.COMPILATION_ERROR, "Failed to build source.");
                        File errorFile = new File(resultDir, BuilderConstants.BUILD_ERROR_FILENAME);
                        try (PrintWriter writer = new PrintWriter(errorFile)) {
                            IOUtils.copy(resultResponse.getEntity().getContent(), writer, Charset.defaultCharset());
                        }
                    }
                }
            }
        } catch (Exception e) {
            File errorFile = new File(resultDir, BuilderConstants.BUILD_ERROR_FILENAME);
            PrintWriter writer = new PrintWriter(errorFile);
            writer.write("Failed to communicate with Extender service.");
            e.printStackTrace(writer);
            writer.close();
        } finally {
            tmpUploadArchive.delete();
            metricsWriter.measureRemoteEngineBuild(buildTimer.start(), platform);
            // Delete temporary upload directory
            if (!keepJobDirectory) {
                LOGGER.info("Deleting job directory");
                if (!FileUtils.deleteQuietly(jobDirectory)) {
                    LOGGER.warn("Failed to delete job directory");
                }
            }
            else {
                LOGGER.info("Keeping job directory due to debug flags");
            }
        }
    }

    HttpEntity buildHttpEntity(final File projectDirectory, File tmpUploadArchive) throws IOException, ExtenderException {
        MultipartEntityBuilder entityBuilder = MultipartEntityBuilder.create();
        entityBuilder.setStrictMode();

        try (OutputStream fileOut = Files.newOutputStream(tmpUploadArchive.toPath()); ZipOutputStream zipStream = new ZipOutputStream(fileOut)) {
            Path projectDirectoryPath = projectDirectory.toPath();
            Files.walk(projectDirectoryPath)
                .filter(Files::isRegularFile)
                .filter(path -> !path.getFileName().toString().equals(ExtenderConst.SOURCE_CODE_ARCHIVE_MAGIC_NAME))
                .filter(path -> !path.getFileName().toString().equals(DataCacheService.FILE_CACHE_INFO_FILE))
                .forEach(res -> {
                    Path relativePath = projectDirectoryPath.relativize(res);
                    try (InputStream in = Files.newInputStream(res)) {
                        ZipEntry entry = new ZipEntry(relativePath.toString());
                        zipStream.putNextEntry(entry);
                        byte[] buffer = new byte[8192]; // stream in chunks
                        int len;
                        while ((len = in.read(buffer)) != -1) {
                            zipStream.write(buffer, 0, len);
                        }
                        zipStream.closeEntry();
                    } catch (IOException e) {
                        LOGGER.warn("Exception during add entry to source code archive", e);
                    }
            });
        } catch (IOException exc) {
            throw new ExtenderException(exc, "Failed to create source code archive");
        }

        entityBuilder.addPart(
            ExtenderConst.SOURCE_CODE_ARCHIVE_MAGIC_NAME,
            new FileBody(tmpUploadArchive, ContentType.DEFAULT_BINARY, ExtenderConst.SOURCE_CODE_ARCHIVE_MAGIC_NAME)
        );

        return entityBuilder.build();
    }

    private void touchInstance(String instanceId) {
        if (instanceService != null) {
            try {
                instanceService.touchInstance(instanceId);
            } catch(Exception exc) {
                LOGGER.error(Markers.INSTANCE_MANAGER_ERROR, String.format("Exception during touch instance '%s'", instanceId), exc);
            }
        }
    }
}
