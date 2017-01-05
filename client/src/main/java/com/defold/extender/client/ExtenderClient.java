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

    public void build(String platform, String sdkVersion, File root, List<File> sourceFiles, File destination, File log) throws ExtenderClientException {
        MultipartEntity entity = new MultipartEntity();

        for (File s : sourceFiles) {
            String relativePath = ExtenderClient.getRelativePath(root, s);
            FileBody bin = new FileBody(s);
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


    /* Scans a directory and returns true if there are extensions available
    */
    public static boolean hasExtensions(File dir) {
        File[] files = dir.listFiles();
        if (!dir.exists()) {
            return false;
        }
        for (File f : files) {
            Matcher m = extensionPattern.matcher(f.getName());
            if (m.matches()) {
                return true;
            }

            if (f.isDirectory()) {
                if( hasExtensions(f) ) {
                    return true;
                }
            }
        }
        return false;
    }

    /* Gets all the extension source files (headers, c++, libs etc...) from a (project) root directory
    */
    public static List<File> getExtensionSource(File root, String platform) throws IOException {
        List<File> source = new ArrayList<>();
        List<File> extensions = ExtenderClient.listExtensionFolders(root);

        for (File f : extensions) {

            source.add( new File(f.getAbsolutePath() + File.separator + ExtenderClient.extensionFilename) );
            source.addAll( ExtenderClient.listFilesRecursive( new File(f.getAbsolutePath() + File.separator + "include") ) );
            source.addAll( ExtenderClient.listFilesRecursive( new File(f.getAbsolutePath() + File.separator + "src") ) );
            source.addAll( ExtenderClient.listFilesRecursive( new File(f.getAbsolutePath() + File.separator + "lib" + File.separator + platform) ) );

            String[] platformParts = platform.split("-");
            if (platformParts.length == 2 ) {
                source.addAll( ExtenderClient.listFilesRecursive( new File(f.getAbsolutePath() + File.separator + "lib" + File.separator + platformParts[1]) ) );
            }
        }
        return source;
    }    

    private static final String extensionFilename = "ext.manifest";
    private static final Pattern extensionPattern = Pattern.compile(extensionFilename);

    private static List<File> listExtensionFolders(File dir) throws IOException {
        if (!dir.isDirectory()) {
            throw new IOException("Path is not a directory: " + dir.getAbsolutePath());
        }

        List<File> folders = new ArrayList<>();

        File[] files = dir.listFiles();
        for (File f : files) {
            Matcher m = extensionPattern.matcher(f.getName());
            if (m.matches()) {
                folders.add( dir );
                return folders;
            }
            if (f.isDirectory()) {
                folders.addAll( listExtensionFolders( f ) );
            }
        }
        return folders;
    }

    private static List<File> listFilesRecursive(File dir) {
        List<File> output = new ArrayList<>();
        if (!dir.isDirectory()) {
            return output; // the extensions doesn't have to have all folders that we look for
        }

        File[] files = dir.listFiles();
        for (File f: files) {
            if (f.isFile() ) {
                output.add(f);
            } else {
                output.addAll( listFilesRecursive(f) );
            }
        }
        return output;
    }

    private static final String getRelativePath(File base, File path) {
        return base.toURI().relativize(path.toURI()).getPath();
    }
}