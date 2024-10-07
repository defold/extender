package com.defold.extender.remote;

import com.defold.extender.BuilderConstants;
import com.defold.extender.ExtenderException;
import com.defold.extender.metrics.MetricsWriter;
import com.defold.extender.services.GCPInstanceService;
import com.defold.extender.Timer;
import com.defold.extender.log.Markers;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.entity.mime.content.AbstractContentBody;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.nio.charset.StandardCharsets;

@Service
public class RemoteEngineBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(RemoteEngineBuilder.class);

    private GCPInstanceService instanceService;
    private File jobResultLocation;
    private long buildSleepTimeout;
    private long buildResultWaitTimeout;
    private boolean keepJobDirectory = false;

    public RemoteEngineBuilder(Optional<GCPInstanceService> instanceService,
                            @Value("${extender.job-result.location}") String jobResultLocation,
                            @Value("${extender.remote-builder.build-sleep-timeout:5000}") long buildSleepTimeout,
                            @Value("${extender.remote-builder.build-result-wait-timeout:1200000}") long buildResultWaitTimeout) {
        instanceService.ifPresent(val -> { LOGGER.info("Instance client is initialized"); this.instanceService = val; });
        this.buildSleepTimeout = buildSleepTimeout;
        this.buildResultWaitTimeout = buildResultWaitTimeout;
        this.jobResultLocation = new File(jobResultLocation);
        this.keepJobDirectory = System.getenv("DM_DEBUG_KEEP_JOB_FOLDER") != null || System.getenv("DM_DEBUG_JOB_FOLDER") != null;
    }

    private String getErrorString(HttpResponse response) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        response.getEntity().writeTo(bos);
        final byte[] bytes = bos.toByteArray();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public void build(final RemoteInstanceConfig remoteInstanceConfig,
                        final File projectDirectory,
                        final String platform,
                        final String sdkVersion,
                        final OutputStream out) throws ExtenderException {

        LOGGER.info("Building engine remotely at {}", remoteInstanceConfig.getUrl());

        final HttpEntity httpEntity;

        try {
            httpEntity = buildHttpEntity(projectDirectory);
        } catch(IllegalStateException|IOException e) {
            throw new RemoteBuildException("Failed to add files to multipart request", e);
        }


        try {
            touchInstance(remoteInstanceConfig.getInstanceId());
            final HttpResponse response = sendRequest(remoteInstanceConfig.getUrl(), platform, sdkVersion, httpEntity);

            touchInstance(remoteInstanceConfig.getInstanceId());
            LOGGER.info("Remote builder response status: {}", response.getStatusLine());

            if (isClientError(response)) {
                String error = getErrorString(response);
                LOGGER.error(Markers.COMPILATION_ERROR, "Client error when building engine remotely:\n{}", error);
                throw new ExtenderException("Client error when building engine remotely: " + error);
            } else if (isServerError(response)) {
                String error = getErrorString(response);
                LOGGER.error(Markers.SERVER_ERROR, "Server error when building engine remotely:\n{}", error);
                throw new RemoteBuildException("Server error when building engine remotely: " + getStatusReason(response) + ": " + error);
            } else {
                response.getEntity().writeTo(out);
            }
        } catch (IOException e) {
            throw new RemoteBuildException("Failed to communicate with remote builder", e);
        }
    }

    @Async
    public void buildAsync(final RemoteInstanceConfig remoteInstanceConfig,
                        final File projectDirectory,
                        final String platform,
                        final String sdkVersion,
                        File jobDirectory, File buildDirectory, MetricsWriter metricsWriter) throws FileNotFoundException, IOException {

        LOGGER.info("Building engine remotely at {}", remoteInstanceConfig.getUrl());
        String jobName = jobDirectory.getName();
        Thread.currentThread().setName(String.format("async-build-%s", jobName));
        File resultDir = new File(jobResultLocation.getAbsolutePath(), jobName);
        resultDir.mkdir();

        final HttpEntity httpEntity;
        Timer buildTimer = new Timer();
        buildTimer.start();

        try {
            httpEntity = buildHttpEntity(projectDirectory);
        } catch(IllegalStateException|IOException e) {
            throw new RemoteBuildException("Failed to add files to multipart request", e);
        }

        try {
            final String serverUrl = String.format("%s/build_async/%s/%s", remoteInstanceConfig.getUrl(), platform, sdkVersion);
            final HttpPost request = new HttpPost(serverUrl);
            request.setEntity(httpEntity);
    
            final HttpClient client  = HttpClientBuilder.create().build();
    
            touchInstance(remoteInstanceConfig.getInstanceId());
            HttpResponse response = client.execute(request);
            // copied from ExtenderClient. Think about code deduplication.
            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                String jobId = EntityUtils.toString(response.getEntity());
                LOGGER.info(String.format("Remote async build posted. Wait job id: %s", jobId));
                long currentTime = System.currentTimeMillis();
                Integer jobStatus = 0;
                Thread.sleep(buildSleepTimeout);
                while (System.currentTimeMillis() - currentTime < buildResultWaitTimeout) {
                    touchInstance(remoteInstanceConfig.getInstanceId());
                    HttpGet statusRequest = new HttpGet(String.format("%s/job_status?jobId=%s", remoteInstanceConfig.getUrl(), jobId));
                    response = client.execute(statusRequest);
                    jobStatus = Integer.valueOf(EntityUtils.toString(response.getEntity()));
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
                }
                touchInstance(remoteInstanceConfig.getInstanceId());
                HttpGet resultRequest = new HttpGet(String.format("%s/job_result?jobId=%s", remoteInstanceConfig.getUrl(), jobId));
                response = client.execute(resultRequest);
                LOGGER.info(String.format("Job %s result got.", jobId));
                if (jobStatus == BuilderConstants.JobStatus.SUCCESS.ordinal()) {
                    // Write zip file to result directory
                    File tmpResult = new File(resultDir, BuilderConstants.BUILD_RESULT_FILENAME + ".tmp");
                    OutputStream os = new FileOutputStream(tmpResult);
                    IOUtils.copy(response.getEntity().getContent(), os);
                    os.close();
                    File targetResult = new File(resultDir, BuilderConstants.BUILD_RESULT_FILENAME);
                    Files.move(tmpResult.toPath(), targetResult.toPath(), StandardCopyOption.ATOMIC_MOVE);
                } else {
                    String errorLog = EntityUtils.toString(response.getEntity());
                    LOGGER.error(Markers.COMPILATION_ERROR, String.format("Failed to build source.\n%s", errorLog));
                    File errorFile = new File(resultDir, BuilderConstants.BUILD_ERROR_FILENAME);
                    PrintWriter writer = new PrintWriter(errorFile);
                    writer.write(errorLog);
                    writer.close();
                }
            } else {
                String errorLog = EntityUtils.toString(response.getEntity());
                LOGGER.error(Markers.COMPILATION_ERROR,  String.format("Failed to build source.\n%s", errorLog));
                File errorFile = new File(resultDir, BuilderConstants.BUILD_ERROR_FILENAME);
                PrintWriter writer = new PrintWriter(errorFile);
                writer.write(errorLog);
                writer.close();        
            }
            metricsWriter.measureRemoteEngineBuild(buildTimer.start(), platform);
        } catch (Exception e) {
            File errorFile = new File(resultDir, BuilderConstants.BUILD_ERROR_FILENAME);
            PrintWriter writer = new PrintWriter(errorFile);
            writer.write("Failed to communicate with Extender service.");
            e.printStackTrace(writer);
            writer.close();
        } finally {
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

    HttpResponse sendRequest(final String remoteBuilderUrl, String platform, String sdkVersion, HttpEntity httpEntity) throws IOException {
        final String serverUrl = String.format("%s/build/%s/%s", remoteBuilderUrl, platform, sdkVersion);
        final HttpPost request = new HttpPost(serverUrl);
        request.setEntity(httpEntity);

        final HttpClient client  = HttpClientBuilder.create().build();
        return client.execute(request);
    }

    HttpEntity buildHttpEntity(final File projectDirectory) throws IOException {
        final MultipartEntityBuilder builder = MultipartEntityBuilder.create()
                .setContentType(ContentType.MULTIPART_FORM_DATA);

        Files.walk(projectDirectory.toPath())
                .filter(Files::isRegularFile)
                .forEach(path -> {
                    String relativePath = path.toFile().getAbsolutePath().substring(projectDirectory.getAbsolutePath().length() + 1);
                    AbstractContentBody body = new FileBody(path.toFile(), ContentType.DEFAULT_BINARY, relativePath);
                    builder.addPart(relativePath, body);
                });

        return builder.build();
    }

    private boolean isClientError(final HttpResponse response) {
        final int statusCode = response.getStatusLine().getStatusCode();
        return HttpStatus.SC_BAD_REQUEST <= statusCode && statusCode < HttpStatus.SC_INTERNAL_SERVER_ERROR;
    }

    private boolean isServerError(final HttpResponse response) {
        final int statusCode = response.getStatusLine().getStatusCode();
        return statusCode >= HttpStatus.SC_INTERNAL_SERVER_ERROR;
    }

    private String getStatusReason(final HttpResponse response) {
        return response.getStatusLine().getReasonPhrase();
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
