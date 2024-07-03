package com.defold.extender.cache;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.nio.channels.Channels;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobInfo;

public class GCPDataCache implements DataCache {

    private Storage storage;
    private String bucketName;

    public GCPDataCache(final String bucketName) {
        this.storage = StorageOptions.getDefaultInstance().getService();
        this.bucketName = bucketName;
    }

    @Override
    public InputStream get(String key) {
        Blob blob = storage.get(this.bucketName, key);
        return Channels.newInputStream(blob.reader());
    }

    @Override
    public boolean exists(String key) {
        Blob blob = storage.get(this.bucketName, key);
        return blob != null;
    }

    @Override
    public void touch(String key) {
        // no-op
    }

    @Override
    public void put(String key, File file) throws IOException {
        BlobId blobId = BlobId.of(this.bucketName, key);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId).build();
    
        // Optional: set a generation-match precondition to avoid potential race
        // conditions and data corruptions. The request returns a 412 error if the
        // preconditions are not met.
        Storage.BlobWriteOption precondition;
        if (this.storage.get(this.bucketName, key) == null) {
          // For a target object that does not yet exist, set the DoesNotExist precondition.
          // This will cause the request to fail if the object is created before the request runs.
          precondition = Storage.BlobWriteOption.doesNotExist();
        } else {
          // If the destination already exists in your bucket, instead set a generation-match
          // precondition. This will cause the request to fail if the existing object's generation
          // changes before the request runs.
          precondition =
              Storage.BlobWriteOption.generationMatch(
                  this.storage.get(this.bucketName, key).getGeneration());
        }
        this.storage.createFrom(blobInfo, new FileInputStream(file), precondition);
    }
    
}
