# Live build progress

The extender streams live build progress over Server-Sent Events (SSE) so clients can show
what a build is doing (downloading the SDK, resolving dependencies, compiling file N of M,
linking, packaging) instead of a silent wait.

Progress is **advisory**: `/job_status` polling remains the source of truth for build
completion, and terminal progress events are only emitted after the result files
(`build.zip`/`error.txt`) are in place. Everything is backward compatible — old clients
never call the endpoint, and new clients fall back to polling when the server has no
progress support.

## Endpoint

```
GET /job_progress?jobId=<jobId>
Accept: text/event-stream
```

* Live job: streams `progress` events. A snapshot of the current state is sent immediately
  on subscribe (early events always precede the first subscriber, because the build is
  dispatched before the jobId is returned).
* Reconnects can pass the standard `Last-Event-ID` header; missed events are replayed from
  a per-job ring buffer when possible, otherwise a state snapshot is sent.
* Job already finished (result files on disk): a single terminal event, then the stream closes.
* Unknown job, or `extender.progress.enabled: false`: `404`.
* Comment lines (`:ka`) are heartbeats sent every `heartbeat-interval` to keep idle
  connections alive through proxies.

Event payload (JSON, `id:` field = `seq`):

```json
{
  "jobId": "job1234567890",
  "seq": 17,
  "ts": 1720512345678,
  "stage": "COMPILING",
  "detail": "extension1: compiling source files",
  "percent": 55,
  "extension": "extension1",
  "currentFile": 12,
  "totalFiles": 34,
  "terminal": false
}
```

Stages, in pipeline order: `RECEIVED`, `QUEUED`, `SDK`, `DEPENDENCIES`, `MANIFESTS`,
`PLATFORM`, `COMPILING`, `LINKING`, `PACKAGING`, and the terminals `SUCCESS`/`ERROR`.
`REMOTE_BUILDING` is a coarse stage used by a frontend when its remote builder runs an
older server without progress support. `percent` is a 0-100 estimate and never decreases.
`extension`/`currentFile`/`totalFiles` are only present while compiling.

## Frontend / remote builder setups

A frontend instance relays the remote builder's progress stream to its own subscribers
under the frontend's jobId, so external clients only ever talk to the frontend. If the
remote builder runs an older server version the frontend degrades to coarse
`REMOTE_BUILDING` ticks.

If the server sits behind a buffering reverse proxy, response buffering must be disabled
for `/job_progress` (e.g. nginx `proxy_buffering off` or the `X-Accel-Buffering: no`
response header), otherwise events arrive in bursts or not at all. The built-in heartbeats
keep idle timeouts (Jetty and load balancers) from closing quiet streams.

## Server configuration (`application.yml`)

```yaml
extender:
    progress:
        enabled: true               # false restores the old behavior exactly
        sse-timeout: 1800000        # SseEmitter timeout, ms
        heartbeat-interval: 15000   # keepalive comment cadence, ms
        event-buffer-size: 256      # per-job replay buffer for Last-Event-ID
        max-subscribers-per-job: 8
        registry-ttl: 1200000       # sweep for jobs that died without a terminal event
        cleanup-period: 20000
```

## Client API

`ExtenderClient` gained an overload that reports progress while the existing polling flow
runs unchanged:

```java
extenderClient.build(platform, sdkVersion, sourceResources, destination, log,
    (stage, detail, percent, currentFile, totalFiles) -> {
        // called on a background thread; currentFile/totalFiles are -1 outside COMPILING
        System.out.printf("[%3d%%] %s %s%n", percent, stage, detail);
    });
```

System properties:

* `com.defold.extender.client.progress-enabled` (default `true`) — set `false` to never
  open the progress stream.
* `com.defold.extender.client.progress-reconnect-attempts` (default `5`) — reconnect
  attempts for a dropped stream.

## Trying it with curl

```sh
JOB=$(curl -s -X POST -F "file=@upload.zip" http://localhost:9000/build_async/x86_64-linux/<sdk-sha1>)
curl -sN "http://localhost:9000/job_progress?jobId=$JOB"
# reconnect mid-build and replay everything after event 10:
curl -sN -H "Last-Event-ID: 10" "http://localhost:9000/job_progress?jobId=$JOB"
```
