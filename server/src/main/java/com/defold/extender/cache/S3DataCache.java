package com.defold.extender.cache;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.PutObjectResult;
import com.amazonaws.services.s3.model.S3Object;

import java.io.File;
import java.io.InputStream;

public class S3DataCache implements DataCache {

    private final AmazonS3 s3Client = AmazonS3ClientBuilder.defaultClient();
    private final String bucketName;

    public S3DataCache(final String bucketName) {
        this.bucketName = bucketName;
    }

    @Override
    public InputStream get(final String key) {
        S3Object s3Object = s3Client.getObject(new GetObjectRequest(bucketName, key));
        return s3Object.getObjectContent();
    }

    @Override
    public boolean exists(String key) {
        return s3Client.doesObjectExist(bucketName, key);
    }

    @Override
    public void put(final String key, final File file) {
        PutObjectRequest request = new PutObjectRequest(bucketName, key, file);
        ObjectMetadata metadata = new ObjectMetadata();
        //metadata.setContentType("plain/text");
        //metadata.addUserMetadata("x-amz-meta-title", "someTitle");
        request.setMetadata(metadata);
        s3Client.putObject(request);
    }
}
