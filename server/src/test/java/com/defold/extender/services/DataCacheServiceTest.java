// package com.defold.extender.services;

// import com.defold.extender.ExtenderException;
// import com.defold.extender.TestUtils;
// import com.defold.extender.cache.CacheEntry;
// import com.defold.extender.cache.DataCache;
// import com.defold.extender.cache.DataCacheFactory;
// import com.defold.extender.cache.info.CacheInfoFileParser;
// import com.defold.extender.cache.info.CacheInfoFileWriter;
// import com.defold.extender.cache.info.CacheInfoWrapper;
// import com.defold.extender.cache.CacheKeyGenerator;
// import org.junit.Ignore;
// import org.junit.Test;
// import org.mockito.ArgumentCaptor;

// import java.io.*;
// import java.net.URISyntaxException;
// import java.nio.file.Files;
// import java.nio.file.StandardCopyOption;
// import java.util.Arrays;
// import java.util.List;
// import java.util.stream.Collectors;

// import static org.junit.Assert.*;
// import static org.mockito.Mockito.*;

// public class DataCacheServiceTest {

//     private static final int fileThreshold = 5;

//     private DataCacheFactory createDataCacheFactoryWithS3Store() {
//         return new DataCacheFactory(
//                 true,
//                 "S3",
//                 "",
//                 "",
//                 "defold-extender-cache-dev");
//     }

//     @Test
//     @Ignore
//     public void testUploadingToS3Cache() throws Exception {
//         final DataCacheService dataCacheService = new DataCacheService(
//                 new CacheKeyGenerator(),
//                 new CacheInfoFileParser(),
//                 new CacheInfoFileWriter(),
//                 createDataCacheFactoryWithS3Store(),
//                 true,
//                 fileThreshold);

//         File uploadDirectory = new File(ClassLoader.getSystemResource("upload").toURI());
//         dataCacheService.cacheFiles(uploadDirectory);
//     }

//     @Test
//     @Ignore
//     public void testQueryS3Cache() throws Exception {
//         final DataCacheService dataCacheService = new DataCacheService(
//                 new CacheKeyGenerator(),
//                 new CacheInfoFileParser(),
//                 new CacheInfoFileWriter(),
//                 createDataCacheFactoryWithS3Store(),
//                 true,
//                 fileThreshold);

//         final File sourceInfoFile = new File(ClassLoader.getSystemResource("upload/"+DataCacheService.FILE_CACHE_INFO_FILE).toURI());
//         final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

//         dataCacheService.queryCache(new FileInputStream(sourceInfoFile), outputStream);

//         System.out.println(">>> JSON:\n" + new String(outputStream.toByteArray()));
//     }

//     @Test
//     @Ignore
//     public void testDownloadingFromS3Cache() throws Exception {
//         final DataCacheService dataCacheService = new DataCacheService(
//                 new CacheKeyGenerator(),
//                 new CacheInfoFileParser(),
//                 new CacheInfoFileWriter(),
//                 createDataCacheFactoryWithS3Store(),
//                 true,
//                 fileThreshold);

//         final File uploadDirectory = Files.createTempDirectory("extenderTest").toFile();
//         final File sourceInfoFile = new File(ClassLoader.getSystemResource("upload/"+DataCacheService.FILE_CACHE_INFO_FILE).toURI());
//         final File targetInfoFile = new File(uploadDirectory.getPath() + "/" + DataCacheService.FILE_CACHE_INFO_FILE);

//         // Copy cache info file to upload directory
//         Files.copy(sourceInfoFile.toPath(), targetInfoFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

//         dataCacheService.getCachedFiles(uploadDirectory);

//         System.out.println(">>> Upload directory: " + uploadDirectory.getAbsolutePath());
//     }

//     @Test
//     public void testCachingFiles() throws Exception {
//         final DataCache dataCache = mock(DataCache.class);

//         final DataCacheFactory dataCacheFactory = mock(DataCacheFactory.class);
//         when(dataCacheFactory.createCache()).thenReturn(dataCache);

//         final DataCacheService dataCacheService = new DataCacheService(
//                 new CacheKeyGenerator(),
//                 new CacheInfoFileParser(),
//                 mock(CacheInfoFileWriter.class),
//                 dataCacheFactory,
//                 true,
//                 fileThreshold);

//         File uploadDirectory = new File(ClassLoader.getSystemResource("upload").toURI());
//         dataCacheService.cacheFiles(uploadDirectory);

//         // Verify that 3 files were cached
//         verify(dataCache, times(3)).put(anyString(), any(File.class));

//         // Verify that correct cache keys and file names were used
//         for (CacheEntry entry : TestUtils.CACHE_ENTRIES) {
//             ArgumentCaptor<File> argument = ArgumentCaptor.forClass(File.class);
//             verify(dataCache).put(eq(entry.getKey()), argument.capture());
//             assertTrue(argument.getValue().getPath().endsWith(entry.getPath()));
//         }
//     }

//     @Test
//     public void testDownloadNoCacheInfoFile() throws IOException, ExtenderException {
//         final DataCacheFactory dataCacheFactory = mock(DataCacheFactory.class);
//         final CacheInfoFileParser cacheInfoFileParser = mock(CacheInfoFileParser.class);

//         final DataCacheService dataCacheService = new DataCacheService(
//                 new CacheKeyGenerator(),
//                 cacheInfoFileParser,
//                 new CacheInfoFileWriter(),
//                 dataCacheFactory,
//                 true,
//                 fileThreshold);

//         final File tmpDir = Files.createTempDirectory("extenderTest").toFile();
//         tmpDir.deleteOnExit();

//         // If no cache file is present in directory root, no files should be fetched from cache
//         long numFilesDownloaded = dataCacheService.getCachedFiles(tmpDir);
//         assertEquals(0, numFilesDownloaded);

//         // Cache file parser should not be invoked
//         verify(cacheInfoFileParser, never()).parse(any(File.class));
//     }

//     @Test
//     public void testDownload() throws IOException, ExtenderException {
//         final DataCache dataCache = mock(DataCache.class);

//         for (CacheEntry entry : TestUtils.MOCK_CACHE_ENTRIES) {
//             ByteArrayInputStream inputStream = new ByteArrayInputStream(entry.getPath().getBytes());
//             when(dataCache.get(entry.getKey())).thenReturn(inputStream);
//         }

//         final DataCacheFactory dataCacheFactory = mock(DataCacheFactory.class);
//         when(dataCacheFactory.createCache()).thenReturn(dataCache);

//         final DataCacheService dataCacheService = new DataCacheService(
//                 new CacheKeyGenerator(),
//                 new CacheInfoFileParser(),
//                 mock(CacheInfoFileWriter.class),
//                 dataCacheFactory,
//                 true,
//                 fileThreshold);
//         final DataCacheService spy = spy(dataCacheService);

//         // Create an empty test directory
//         final File tmpDownloadDir = Files.createTempDirectory("extenderTest").toFile();
//         tmpDownloadDir.deleteOnExit();

//         // Write with an info file to the test directory root
//         final CacheInfoFileWriter cacheInfoFileWriter = new CacheInfoFileWriter();
//         File fileCacheInfoFile = new File(tmpDownloadDir, DataCacheService.FILE_CACHE_INFO_FILE);
//         cacheInfoFileWriter.write(DataCacheService.FILE_CACHE_INFO_VERSION, DataCacheService.FILE_CACHE_INFO_HASH_TYPE, Arrays.asList(TestUtils.MOCK_CACHE_ENTRIES), new FileOutputStream(fileCacheInfoFile));

//         int numFilesDownloaded = spy.getCachedFiles(tmpDownloadDir);

//         assertEquals(3, numFilesDownloaded);

//         List<String> collect = Files
//                 .walk(tmpDownloadDir.toPath())
//                 .filter(Files::isRegularFile)
//                 .map(path -> path.toFile().getName())
//                 .collect(Collectors.toList());

//         assertEquals(4, collect.size());
//         assertTrue(collect.contains("file1"));
//         assertTrue(collect.contains("file2"));
//         assertTrue(collect.contains("file3"));
//         assertTrue(collect.contains(DataCacheService.FILE_CACHE_INFO_FILE));
//     }

//     @Test
//     public void testQuery() throws IOException, ExtenderException, URISyntaxException {
//         CacheInfoFileParser parser = new CacheInfoFileParser();

//         DataCache dataCacheMock = mock(DataCache.class);
//         when(dataCacheMock.exists(TestUtils.CACHE_ENTRIES[0].getKey())).thenReturn(true);

//         DataCacheFactory dataCacheFactory = mock(DataCacheFactory.class);
//         when(dataCacheFactory.createCache()).thenReturn(dataCacheMock);

//         DataCacheService dataCacheService = new DataCacheService(
//                 new CacheKeyGenerator(),
//                 parser,
//                 new CacheInfoFileWriter(),
//                 dataCacheFactory,
//                 true,
//                 fileThreshold);

//         FileInputStream input = new FileInputStream(new File(ClassLoader.getSystemResource("upload/"+DataCacheService.FILE_CACHE_INFO_FILE).toURI()));
//         ByteArrayOutputStream output = new ByteArrayOutputStream();

//         dataCacheService.queryCache(input, output);

//         InputStream jsonStream = new ByteArrayInputStream(output.toByteArray());
//         CacheInfoWrapper info = parser.parse(jsonStream);
//         List<CacheEntry> entries = info.getEntries();

//         assertEquals(1, info.getVersion());
//         assertEquals("sha256", info.getHashType());
//         assertEquals(2, entries.size());

//         CacheEntry entry1 = entries.get(0);
//         assertEquals("dir/test1.txt", entry1.getPath());
//         assertEquals(TestUtils.CACHE_ENTRIES[0].getKey(), entry1.getKey());
//         assertTrue(entry1.isCached());

//         CacheEntry entry2 = entries.get(1);
//         assertEquals("dir2/test2.txt", entry2.getPath());
//         assertEquals(TestUtils.CACHE_ENTRIES[1].getKey(), entry2.getKey());
//         assertFalse(entry2.isCached());
//     }
// }

