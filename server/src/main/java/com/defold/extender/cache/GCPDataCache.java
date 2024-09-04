package com.defold.extender.cache;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.nio.channels.Channels;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.google.cloud.storage.StorageOptions;
import com.google.cloud.storage.BlobId;
import com.defold.extender.AsyncBuilder;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobInfo;

public class GCPDataCache implements DataCache {

private static final Logger LOGGER = LoggerFactory.getLogger(GCPDataCache.class);

    private Storage storage;
    private String bucketName;
    private String prefix;

    public GCPDataCache(final String bucketName, final String prefix) {
        this.storage = StorageOptions.getDefaultInstance().getService();
        this.bucketName = bucketName;
        this.prefix = prefix;
    }

    @Override
    public InputStream get(String key) {
        Blob blob = storage.get(this.bucketName, getBlobKey(key));
        return Channels.newInputStream(blob.reader());
    }

    @Override
    public boolean exists(String key) {
        Blob blob = storage.get(this.bucketName, getBlobKey(key));
        return blob != null;
    }

    @Override
    public void touch(String key) {
        // no-op
    }

    @Override
    public void put(String key, File file) throws IOException {
        String blobKey = getBlobKey(key);
        BlobId blobId = BlobId.of(this.bucketName, blobKey);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId).build();
    
        // Optional: set a generation-match precondition to avoid potential race
        // conditions and data corruptions. The request returns a 412 error if the
        // preconditions are not met.
        Storage.BlobWriteOption precondition;
        if (this.storage.get(this.bucketName, blobKey) == null) {
          // For a target object that does not yet exist, set the DoesNotExist precondition.
          // This will cause the request to fail if the object is created before the request runs.
          precondition = Storage.BlobWriteOption.doesNotExist();
        } else {
          // If the destination already exists in your bucket, instead set a generation-match
          // precondition. This will cause the request to fail if the existing object's generation
          // changes before the request runs.
          precondition =
              Storage.BlobWriteOption.generationMatch(
                  this.storage.get(this.bucketName, blobKey).getGeneration());
        }
        try {
            this.storage.createFrom(blobInfo, new FileInputStream(file), precondition);
        } catch (StorageException storageExc) {
            // in case if some concurrent requests uploads the same thing at the same time precondition can fail
            // we can catch exception and continue working without any issue because file was uploaded by another job
            LOGGER.info(String.format("Failed upload cache object with key %s", blobKey), storageExc);
        }
    }
    
    private String getBlobKey(final String key) {
        if (prefix == null || prefix.isEmpty()) {
            return key;
        } else {
            return String.format("%s/%s", prefix, key);
        }
    }
}
