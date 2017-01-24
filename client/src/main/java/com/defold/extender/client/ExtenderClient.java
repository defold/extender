package com.defold.extender.client;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.impl.client.DefaultHttpClient;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

public class ExtenderClient {

    private final String extenderBaseUrl;
    private ExtenderClientCache cache;

    /** Creates a local build cache
     * @param extenderBaseUrl   The build server url (e.g. https://build.defold.com)
     * @param cacheDir          A directory where the cache files are located (it must exist beforehand)
     */
    public ExtenderClient(String extenderBaseUrl, File cacheDir) throws IOException {
        this.extenderBaseUrl = extenderBaseUrl;
        this.cache = new ExtenderClientCache(cacheDir);
    }

    /** Builds a new engine given a platform and an sdk version plus source files.
     * The result is a .zip file
     *
     * @param platform      E.g. "arm64-ios", "armv7-android", "x86_64-osx"
     * @param sdkVersion    Sha1 of defold version
     * @param root          Folder where to start creating relative paths from
     * @param sourceFiles   List of files that should be build on server (.cpp, .a, etc)
     * @param destination   The output where the returned zip file is copied
     * @param log           A log file
     * @throws ExtenderClientException
     */
    public void build(String platform, String sdkVersion, File root, List<File> sourceFiles, File destination, File log) throws ExtenderClientException {
        File cachedBuild = cache.isCachedBuildValid(platform, sdkVersion, sourceFiles);
        if (cachedBuild != null) {
            try {
                Files.copy(new FileInputStream(cachedBuild), destination.toPath(), REPLACE_EXISTING);
            } catch (IOException e) {
                throw new ExtenderClientException(String.format("Failed to copy %s to %s", cachedBuild.getAbsolutePath(), destination.getAbsolutePath()), e);
            }
            return;
        }

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

        // Store the new build
        cachedBuild = cache.getCachedBuildFile(platform);
        File parentDir = cachedBuild.getParentFile();

        if (!parentDir.exists()) {
            parentDir.mkdirs();
        }
        if (!parentDir.exists()) {
            throw new ExtenderClientException(String.format("Failed to create cache dir %s", parentDir.getAbsolutePath()));
        }

        try {
            Files.copy(new FileInputStream(destination), cachedBuild.toPath(), REPLACE_EXISTING);
            cache.storeCachedBuild(platform, sdkVersion, sourceFiles);
        } catch (IOException e) {
            throw new ExtenderClientException(String.format("Failed to store cached copy %s to %s", destination.getAbsolutePath(), cachedBuild.getAbsolutePath()), e);
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