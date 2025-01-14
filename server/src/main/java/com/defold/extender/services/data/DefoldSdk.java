package com.defold.extender.services.data;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

import com.defold.extender.services.DefoldSdkService;

public class DefoldSdk implements AutoCloseable {
    private File sdkDir;
    private String sdkHash;
    private DefoldSdkService sdkService;
    private AtomicBoolean isUsed = new AtomicBoolean(true);

    public DefoldSdk(File sdkDir, String sdkHash, DefoldSdkService sdkService) {
        this.sdkDir = sdkDir;
        this.sdkHash = sdkHash;
        this.sdkService = sdkService;

        this.sdkService.acquireSdk(sdkHash);
    }

    public static DefoldSdk copyOf(DefoldSdk sdk) {
        return new DefoldSdk(sdk.sdkDir, sdk.sdkHash, sdk.sdkService);
    }

    public boolean isValid() {
        return this.sdkDir != null && this.sdkDir.exists();
    }

    public File toFile() {
        return this.sdkDir;
    }

    public String getHash() {
        return this.sdkHash;
    }

    @Override
    public String toString() {
        return String.format("Sdk %s at %s", this.sdkHash, this.sdkDir.toString());
    }

    @Override
    public void close() {
        synchronized (this.isUsed) {
            if (this.isUsed.get()) {
                this.sdkService.releaseSdk(this.sdkHash);
                this.isUsed.set(false);
            }
        }
    }
}
