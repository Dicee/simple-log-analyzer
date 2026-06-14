package com.simpleloganalyzer.agent

import com.simpleloganalyzer.agent.config.FilesConfig
import com.simpleloganalyzer.commons.logging.log
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.BufferedInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.inputStream
import kotlin.io.path.name
import kotlin.io.path.notExists

private const val CHECKPOINT_HIDDEN_DIR = "./log-nanny"

/**
 * @param byteOffset denotes the exclusive last byte offset that has been checkpointed (safely published)
 */
@Serializable
internal data class Checkpoint(val fileKey: String?, @Serializable(with = PathSerializer::class) val path: Path, val byteOffset: Long) {
    fun incOffset(inc: Long) = copy(byteOffset = byteOffset + inc)

    internal fun sameAs(file: Path, attrs: FileAttrs): Boolean {
        // fallback to path matching for filesystems that do not support fileKey()
        return if (fileKey != null) fileKey == attrs.fileKey else path == file
    }
}

// Used in tests to simulate platforms that do/don't provide a file key
internal fun interface FileAttrReader {
    fun read(path: Path): FileAttrs
}

internal data class FileAttrs(val fileKey: String?, val size: Long)

internal val DEFAULT_FILE_ATTR_READER = FileAttrReader { path ->
    val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
    FileAttrs(attrs.fileKey()?.toString(), attrs.size())
}

@ExperimentalSerializationApi
internal class Checkpointer(
    logGroups: Iterable<String>,
    private val helper: LogPollerHelper,
    private val attrReader: FileAttrReader = DEFAULT_FILE_ATTR_READER,
) {
    // serialize all checkpoint file accesses per log group to prevent any corruption — callers are expected to invoke
    // these methods from a dispatcher tolerant of blocking work (e.g. the loom dispatcher), since contention here is
    // a real blocking wait
    private val locks = logGroups.associateWith { ReentrantLock() }

    fun getCheckpoint(logGroupName: String, config: FilesConfig, sortedLogGroupFiles: List<Path>): Checkpoint = lockFor(logGroupName).withLock {
        require(sortedLogGroupFiles.isNotEmpty()) // up to the caller to ensure that this is true

        val head = sortedLogGroupFiles[0]
        val checkpoint = readCheckpointIfExists(logGroupName, config) ?: return@withLock initialCheckpoint(head)

        val matchingFile = sortedLogGroupFiles.find { checkpoint.sameAs(it, attrReader.read(it)) }
        if (matchingFile == null) {
            log.warn("Failed to find checkpointed file with file key '${checkpoint.fileKey}' and path '${checkpoint.path}'. Will start tailing from $head.")
            return@withLock initialCheckpoint(head)
        }

        if (matchingFile != sortedLogGroupFiles[0]) {
            log.warn("Checkpointed file with file key '${checkpoint.fileKey}' and path '${checkpoint.path}' isn't the earliest file. Archiving earlier ones.")
            sortedLogGroupFiles
                .takeWhile { it != matchingFile }
                .forEach { helper.archive(it, config) }
        }

        val byteSize = attrReader.read(matchingFile).size
        if (checkpoint.byteOffset >= byteSize) {
            // we accept duplicate data risk here, because we want at-least-once semantics
            log.warn(
                "File with file key '${checkpoint.fileKey}' and path '${checkpoint.path}' is smaller than when it was checkpointed " +
                "(${checkpoint.byteOffset} >= $byteSize). Resetting the offset to 0 to prevent any data loss."
            )
            return@withLock checkpoint.copy(byteOffset = 0)
        }

        return@withLock checkpoint.copy(path = matchingFile)
    }

    fun commit(logGroupName: String, config: FilesConfig, origin: Path, byteOffsetInc: Long, eof: Boolean): Checkpoint =
        lockFor(logGroupName).withLock {
            val currentCheckpoint = readCheckpointIfExists(logGroupName, config)
            val isNewOrDifferentFile = currentCheckpoint == null || !currentCheckpoint.sameAs(origin, attrReader.read(origin))
            val newCheckpoint = (if (isNewOrDifferentFile) initialCheckpoint(origin) else currentCheckpoint).incOffset(byteOffsetInc)

            writeCheckpoint(logGroupName, config, newCheckpoint)
            if (eof) helper.archive(origin, config)

            return@withLock newCheckpoint
        }

    private fun readCheckpointIfExists(logGroupName: String, config: FilesConfig): Checkpoint? {
        val checkpointFile = checkpointFile(logGroupName, config)
        return when {
            checkpointFile.notExists() -> null
            else -> Json.decodeFromStream<Checkpoint>(BufferedInputStream(checkpointFile.inputStream()))
        }
    }

    private fun writeCheckpoint(logGroupName: String, config: FilesConfig, checkpoint: Checkpoint) {
        val output = checkpointFile(logGroupName, config)
        val tmp = output.resolveSibling("${output.name}.tmp")
        Files.createDirectories(tmp.parent)

        // Protection against writing corrupted data in case we crash while writing to the checkpoint file:
        // - write with fsync to ensure the flush happens before the move
        // - atomically move once the writer is successfully flushed
        FileOutputStream(tmp.toFile()).use { fos ->
            Json.encodeToStream(checkpoint, fos)
            fos.flush()
            fos.fd.sync()
        }
        Files.move(tmp, output, REPLACE_EXISTING)
    }

    private fun lockFor(logGroupName: String) = locks.getOrElse(logGroupName) { error("Unknown log group $logGroupName") }

    private fun initialCheckpoint(head: Path): Checkpoint = Checkpoint(attrReader.read(head).fileKey, head, 0)
}

private fun checkpointFile(logGroupName: String, config: FilesConfig) = config.rootPath.resolve("$CHECKPOINT_HIDDEN_DIR/$logGroupName-checkpoint.txt")

private object PathSerializer : KSerializer<Path> {
    override val descriptor = PrimitiveSerialDescriptor("Path", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Path) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Path = Path.of(decoder.decodeString())
}