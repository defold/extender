package com.defold.extender.client;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.impl.client.DefaultHttpClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ExtenderClient {

    private final String extenderBaseUrl;

    public ExtenderClient(String extenderBaseUrl) {
        this.extenderBaseUrl = extenderBaseUrl;
    }

    public void build(String platform, String sdkVersion, String root, List<String> sourceFiles, File destination, File log) throws ExtenderClientException {
        MultipartEntity entity = new MultipartEntity();

        for (String s : sourceFiles) {
            String relativePath = ExtenderClient.getRelativePath(root, s);
            FileBody bin = new FileBody(new File(s));
            entity.addPart(relativePath, bin);
        }

        String url = String.format("%s/build/%s/%s", extenderBaseUrl, platform, sdkVersion);
        HttpPost request = new HttpPost(url);

        request.setEntity(entity);

        try {
            HttpClient client = new DefaultHttpClient();
            HttpResponse response = client.execute(request);

            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                response.getEntity().writeTo(new FileOutputStream(destination));
            } else {
                response.getEntity().writeTo(new FileOutputStream(log));
                throw new ExtenderClientException("Failed to build source.");
            }
        } catch (IOException e) {
            throw new ExtenderClientException("Failed to communicate with Extender service.", e);
        }
    }

    // Helper functions

    private static final Pattern extensionPattern = Pattern.compile(new String("ext.manifest"));

    public static List<File> findExtensionFolders(File dir) {
        List<File> folders = new ArrayList<>();

        File[] files = dir.listFiles();
        for (File f : files) {
            Matcher m = extensionPattern.matcher(f.getName());
            if (m.matches()) {
                folders.add( dir );
                return folders;
            }
            if (f.isDirectory() ) {
                folders.addAll( findExtensionFolders( f ) );
            }
        }
        return folders;
    }

    public static List<File> findSourceFiles(File dir) {
        List<File> output = new ArrayList<>();
        if (!dir.exists()) {
            return output;
        }

        File[] files = dir.listFiles();
        for (File f: files) {
            if (f.isFile() ) {
                output.add(f);
            } else {
                output.addAll( findSourceFiles(f) );
            }
        }
        return output;
    }

    public static final String getRelativePath(String base, String path) {
        String relative = new File(base).toURI().relativize(new File(path).toURI()).getPath();
        return relative;
    }
}