# Application metrics
Below is the table with metrics description that collected inside application. That table doesn't include standard Spring and JVM metrics.

|Metric|Type|Unit|Description|
|------|----|----|-----------|
|extender.job.requestSize|Distribution|Bytes|Build request payload size|
|extender.job.zipSize|Distribution|Bytes|Archive with build result size|
|extender.job.cache.uploadSize|Distribution|Bytes|How much data was cached during the build|
|extender.job.cache.uploadCount|Distribution|Files|How many files were cached during the build|
|extender.job.cache.downloadSize|Distribution|Bytes|How much data was pulled from cache during the build|
|extender.job.cache.downloadCount|Distribution|Files|How many filed were pulled from cache during the build|
|extender.job.receive|Timer|Milliseconds|How long build request was receiving|
|extender.job.sdkDownload|Timer|Milliseconds||How long Defold sdk was downloading|
|extender.job.sdk|Counter|Unit|How many timer exact Defold sdk was used for building|
|extender.job.gradle.download|Timer|Milliseconds|How long Gradle was downloading dependencies|
|extender.job.cocoapods.install|Timer|Milliseconds|How long Cocoapods was installing dependencies|
|extender.job.build|Timer|Milliseconds|How long build was|
|extender.job.remoteBuild|Timer|Milliseconds|How long the remote build was|
|extender.job.zip|Timer|Milliseconds|How long result was zipping|
|extender.job.write|Timer|Milliseconds|How long response with result was sending|
|extender.job.cache.upload|Timer|MIlliseconds|How long cache uploading operation was|
|extender.job.cache.download|Timer|Milliseconds|How long cache downloading opearion was|
|extender.build.task|Counter|Unit|How many builds were handled|
|extender.service.cocoapods.get|Timer|Milliseconds|How long Cocoapods dependecies downloading was|
|extender.service.sdk.get.download|Counter|Unit|How many times Defold sdk was downloaded|
|extender.service.sdk.get.duration|Timer|Milliseconds|How long Defold sdk was downloading|
|extender.service.gradle.unpack|Timer|Milliseconds|How long Gradle was unpacking dependencies|
|extender.service.gradle.get|Timer|Milliseconds|How long Gradle dependencies step was going|