package com.defold.extender.cache;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.StorageClass;

import java.io.File;
import java.io.InputStream;

public class S3DataCache implements DataCache {

    // ReducedRedundancy: low cost and highly available, but less redundant, storage option
    private static final StorageClass STORAGE_CLASS = StorageClass.ReducedRedundancy;

    private static final int STATUS_NOT_FOUND = 404;

    private final AmazonS3 s3Client = AmazonS3ClientBuilder.defaultClient();
    private final String bucketName;

    public S3DataCache(final String bucketName) {
        this.bucketName = bucketName;
    }

    @Override
    public InputStream get(final String key) {
        try {
            S3Object s3Object = s3Client.getObject(new GetObjectRequest(bucketName, key));
            return s3Object != null ? s3Object.getObjectContent() : null;
        } catch(AmazonS3Exception e) {
            if (e.getStatusCode() == STATUS_NOT_FOUND) {
                return null;
            } else {
                throw e;
            }
        }
    }

    @Override
    public boolean exists(String key) {
        return s3Client.doesObjectExist(bucketName, key);
    }

    @Override
    public void touch(String key) {
        // Hack to update the last modified timestamp of the object without having to upload the whole file again
        s3Client.changeObjectStorageClass(bucketName, key, STORAGE_CLASS);
    }

    @Override
    public void put(final String key, final File file) {
        PutObjectRequest request = new PutObjectRequest(bucketName, key, file);
        ObjectMetadata metadata = new ObjectMetadata();
        request.withStorageClass(STORAGE_CLASS);
        request.setMetadata(metadata);
        s3Client.putObject(request);
    }
}
