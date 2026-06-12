*Entirely written by Claude following our research conversation, iterating on a design. It's merely a sketch of how it will work, but allows setting the main ideas in place.*
# Log agent pipeline — coroutine design notes

Working notes on the coroutine layout for log-nanny: a flow-per-file pipeline that fans into a shared channel drained by N concurrent uploaders, with dispatchers chosen per stage and `Dispatchers.IO` backed by virtual threads.

## The code

```kotlin
val loomIo: CoroutineDispatcher =
    Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()

fun tailFile(path: Path): Flow<String> = flow {
    openAndSeek(path).use { reader ->
        while (currentCoroutineContext().isActive) {
            val line = reader.readLine() ?: run { delay(100); null } ?: continue
            emit(line)
        }
    }
}.flowOn(loomIo)

// One per watched file.
suspend fun runFile(path: Path, uploadCh: SendChannel<Batch>) = coroutineScope {
    tailFile(path)
        .map { parse(it) }
        .chunked(maxBatchSize)
        .flowOn(Dispatchers.Default)
        .collect { batch -> uploadCh.send(batch) }
}

// Shared fan-in channel, drained by N uploaders.
val batches = Channel<Batch>(capacity = 8)

repeat(4) {
    launch {
        for (batch in batches) {
            val compressed = withContext(Dispatchers.Default) { gzip(batch) }
            httpClient.put(compressed)
            withContext(loomIo) { checkpoint.persist(batch.lastOffset) }
        }
    }
}
```

## How it works

**`loomIo`** is our IO dispatcher, backed by a virtual-thread executor (JDK 21+). It replaces `Dispatchers.IO` everywhere we'd otherwise use it. Blocking calls inside it (`readLine`, `fsync`) park a virtual thread rather than pinning a platform thread, so we can have many concurrent blocking operations without thread-pool starvation. Created once at startup; `close()` on shutdown.

**`tailFile`** is a cold `Flow<String>`: nothing happens until someone collects. The `flow { }` block does blocking IO, and `.flowOn(loomIo)` says "run the upstream of this flow on the IO dispatcher." The consumer's dispatcher is unaffected.

**`runFile`** is the per-file pipeline:
- `map { parse(it) }` — pure CPU work.
- `chunked(maxBatchSize)` — size-based batching only. Time-bounded flush (`log.maxPutDelaySeconds` from the ADR) is **not** handled here; needs a custom `select`-based stage.
- `.flowOn(Dispatchers.Default)` — shifts the parse + chunk steps onto the CPU pool. Because `flowOn` only affects upstream, the terminal `collect` (which does `uploadCh.send`) runs on whatever dispatcher called `runFile`.

**The fan-in channel** (`batches`) decouples the N file pipelines from the M uploaders. Each `runFile` is a producer; each uploader coroutine is a consumer. Backpressure is automatic: if uploaders fall behind, the channel fills, `send` suspends, and the upstream flow stops pulling from the file. Capacity (8) caps how much in-flight data sits in memory.

**Uploaders** hop dispatchers explicitly:
- `gzip` runs on `Default` (CPU-bound).
- `httpClient.put` is suspending and non-blocking under the hood (Ktor / JDK `HttpClient`), so the dispatcher barely matters.
- `checkpoint.persist` does a blocking fsync, so `withContext(loomIo)`.

## Open item

`chunked` is size-only. The ADR also requires a max-delay flush, so the batching stage needs to be replaced with a hand-rolled operator (or a dedicated coroutine reading from a `Channel<LogEvent>` and racing `onReceive` against `onTimeout` in a `select`) before this is production-shaped.

## Reference
- https://kt.academy/article/dispatcher-loom