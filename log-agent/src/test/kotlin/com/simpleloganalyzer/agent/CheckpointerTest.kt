package com.simpleloganalyzer.agent

import com.simpleloganalyzer.agent.config.FilesConfig
import io.mockk.Called
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

@OptIn(ExperimentalSerializationApi::class)
class CheckpointerTest {
    @TempDir private lateinit var tempDir: Path

    private val helper = mockk<LogPollerHelper>(relaxUnitFun = true)

    private lateinit var attrs: FakeAttrReader
    private lateinit var checkpointer: Checkpointer

    private fun setUp(mode: FileKeyMode) {
        attrs = FakeAttrReader(mode)
        checkpointer = Checkpointer(listOf(GROUP), helper, attrs)
    }

    /**
     * Tests run twice: once on a filesystem that exposes a [java.nio.file.attribute.BasicFileAttributes.fileKey] (Unix-style inodes),
     * and once on a filesystem that does not (e.g. some Windows filesystems), which forces path-based fallback.
     */
    enum class FileKeyMode { WITH_FILE_KEY, WITHOUT_FILE_KEY }

    @ParameterizedTest
    @EnumSource(FileKeyMode::class)
    fun testGetCheckpoint_noPreviousCheckpoint_returnsFreshCheckpointOnHead(mode: FileKeyMode) {
        setUp(mode)
        val a = createLogFile("app.log", size = 100)
        val b = createLogFile("app.log.1", size = 200)

        val checkpoint = checkpointer.getCheckpoint(GROUP, filesConfig(), listOf(a, b))

        assertThat(checkpoint).isEqualTo(Checkpoint(fileKey = attrs.fileKeyOf(a), path = a, byteOffset = 0))
        verify { helper wasNot Called }
    }

    @ParameterizedTest
    @EnumSource(FileKeyMode::class)
    fun testGetCheckpoint_checkpointedFileNoLongerExists_returnsFreshCheckpointOnHead(mode: FileKeyMode) {
        setUp(mode)
        val a = createLogFile("app.log", size = 100)
        val b = createLogFile("app.log.1", size = 200)

        // Persisted checkpoint refers to a file that has since vanished — different fileKey, different path
        val fileKey = if (mode == FileKeyMode.WITH_FILE_KEY) "vanished-key" else null
        writeCheckpointFile(Checkpoint(fileKey = fileKey, path = tempDir.resolve("vanished.log"), byteOffset = 42))

        val checkpoint = checkpointer.getCheckpoint(GROUP, filesConfig(), listOf(a, b))

        assertThat(checkpoint).isEqualTo(Checkpoint(fileKey = attrs.fileKeyOf(a), path = a, byteOffset = 0))
        verify { helper wasNot Called }
    }

    @ParameterizedTest
    @EnumSource(FileKeyMode::class)
    fun testGetCheckpoint_checkpointedFileSmallerThanOffset_resetsOffsetToZero(mode: FileKeyMode) {
        setUp(mode)
        val a = createLogFile("app.log", size = 50)

        val persisted = Checkpoint(fileKey = attrs.fileKeyOf(a), path = a, byteOffset = 1000)
        writeCheckpointFile(persisted)

        val checkpoint = checkpointer.getCheckpoint(GROUP, filesConfig(), listOf(a))

        assertThat(checkpoint).isEqualTo(persisted.copy(byteOffset = 0))
        verify { helper wasNot Called }
    }

    @ParameterizedTest
    @EnumSource(FileKeyMode::class)
    fun testGetCheckpoint_checkpointedFileExistsButIsNotOldest_archivesOlderFilesAndResumes(mode: FileKeyMode) {
        setUp(mode)
        val a = createLogFile("app.log", size = 100)
        val b = createLogFile("app.log.1", size = 200)
        val c = createLogFile("app.log.2", size = 300)

        val persisted = Checkpoint(fileKey = attrs.fileKeyOf(b), path = b, byteOffset = 50)
        writeCheckpointFile(persisted)

        val config = filesConfig()
        val checkpoint = checkpointer.getCheckpoint(GROUP, config, listOf(a, b, c))

        assertThat(checkpoint).isEqualTo(persisted)
        coVerify(exactly = 1) { helper.archive(a, config) }
        confirmVerified(helper)
    }

    @ParameterizedTest
    @EnumSource(FileKeyMode::class)
    fun testGetCheckpoint_checkpointedFileIsOldestWithNonZeroOffset_returnsCheckpointUnchanged(mode: FileKeyMode) {
        setUp(mode)
        val a = createLogFile("app.log", size = 500)
        val b = createLogFile("app.log.1", size = 200)

        val persisted = Checkpoint(fileKey = attrs.fileKeyOf(a), path = a, byteOffset = 123)
        writeCheckpointFile(persisted)

        val checkpoint = checkpointer.getCheckpoint(GROUP, filesConfig(), listOf(a, b))

        assertThat(checkpoint).isEqualTo(persisted)
        verify { helper wasNot Called }
    }

    @Test
    fun testGetCheckpoint_checkpointedFileMovedSinceLastRun_resumesAtNewPathPreservingOffset() {
        // Only meaningful with file keys: without them, "same file at a new path" is indistinguishable from "a different file"
        setUp(FileKeyMode.WITH_FILE_KEY)
        val oldPath = tempDir.resolve("app.log")
        val newPath = createLogFile("app.log.1", size = 500)
        val identity = attrs.fileKeyOf(newPath)

        // Checkpoint was written when the file lived at oldPath, but the file (same fileKey/inode) has since been renamed
        val persisted = Checkpoint(fileKey = identity, path = oldPath, byteOffset = 123)
        writeCheckpointFile(persisted)

        val checkpoint = checkpointer.getCheckpoint(GROUP, filesConfig(), listOf(newPath))

        assertThat(checkpoint).isEqualTo(persisted.copy(path = newPath))
        verify { helper wasNot Called }
    }

    @ParameterizedTest
    @EnumSource(FileKeyMode::class)
    fun testCommit_noPreviousCheckpoint_writesFreshCheckpointAtIncrementedOffset(mode: FileKeyMode) {
        setUp(mode)
        val a = createLogFile("app.log", size = 500)

        val result = checkpointer.commit(GROUP, filesConfig(), origin = a, byteOffsetInc = 100, eof = false)

        assertCommittedCheckpointIs(a, byteOffset = 100, result)
        verify { helper wasNot Called }
    }

    @ParameterizedTest
    @EnumSource(FileKeyMode::class)
    fun testCommit_originDiffersFromPreviousCheckpoint_resetsOffsetToIncrement(mode: FileKeyMode) {
        setUp(mode)
        val a = createLogFile("app.log", size = 500)
        val b = createLogFile("app.log.1", size = 500)
        writeCheckpointFile(Checkpoint(fileKey = attrs.fileKeyOf(a), path = a, byteOffset = 400))

        // Commit refers to the next file — the persisted offset on `a` should not carry over to `b`
        val result = checkpointer.commit(GROUP, filesConfig(), origin = b, byteOffsetInc = 100, eof = false)

        assertCommittedCheckpointIs(b, byteOffset = 100, result)
        verify { helper wasNot Called }
    }

    @ParameterizedTest
    @EnumSource(FileKeyMode::class)
    fun testCommit_originMatchesPreviousCheckpoint_incrementsOffset(mode: FileKeyMode) {
        setUp(mode)
        val a = createLogFile("app.log", size = 1000)
        writeCheckpointFile(Checkpoint(fileKey = attrs.fileKeyOf(a), path = a, byteOffset = 400))

        val result = checkpointer.commit(GROUP, filesConfig(), origin = a, byteOffsetInc = 100, eof = false)

        assertCommittedCheckpointIs(a, byteOffset = 500, result)
        verify { helper wasNot Called }
    }

    @ParameterizedTest
    @EnumSource(FileKeyMode::class)
    fun testCommit_originMatchesPreviousCheckpoint_eof_incrementsOffsetAndArchives(mode: FileKeyMode) {
        setUp(mode)
        val a = createLogFile("app.log", size = 1000)
        writeCheckpointFile(Checkpoint(fileKey = attrs.fileKeyOf(a), path = a, byteOffset = 400))

        val config = filesConfig()
        val result = checkpointer.commit(GROUP, config, origin = a, byteOffsetInc = 100, eof = true)

        assertCommittedCheckpointIs(a, byteOffset = 500, result)
        coVerify(exactly = 1) { helper.archive(a, config) }
        confirmVerified(helper)
    }

    /**
     * Proves that the per-log-group dispatcher (`limitedParallelism(1)`) serializes reads and writes:
     * a [Checkpointer.commit] that hangs mid-execution must block a concurrent [Checkpointer.getCheckpoint] from
     * seeing the pre-commit state.
     */
    @Test
    fun testConcurrency_commitAndGetCheckpointOnSameLogGroup_serializedOnSingleThread() = runBlocking<Unit> {
        setUp(FileKeyMode.WITH_FILE_KEY)
        val logFile = createLogFile("app.log", size = 1000)
        val initial = Checkpoint(fileKey = attrs.fileKeyOf(logFile), path = logFile, byteOffset = 0)
        writeCheckpointFile(initial)

        // Wrap the fake reader so the very first call hangs for 250ms — that call happens inside the writer's commit,
        // so the writer is forced to hold its dispatcher slot well past the reader's 50ms delay.
        val firstCall = AtomicBoolean(true)
        val hangingAttrs = spyk(attrs)
        every { hangingAttrs.read(any()) } answers {
            if (firstCall.compareAndSet(true, false)) Thread.sleep(250)
            callOriginal()
        }
        val checkpointer = Checkpointer(listOf(GROUP), helper, hangingAttrs)
        val config = filesConfig()
        val expectedAfterCommit = initial.copy(byteOffset = 500)

        val writer = launch(Dispatchers.IO) {
            checkpointer.commit(GROUP, config, origin = logFile, byteOffsetInc = 500, eof = false)
        }
        val reader = async(Dispatchers.IO) {
            // Start after the writer has already grabbed the per-group slot and started hanging
            delay(50)
            checkpointer.getCheckpoint(GROUP, config, listOf(logFile))
        }

        // If serialization works, the reader sees the post-commit value; otherwise it would race in and read [initial].
        assertThat(reader.await()).isEqualTo(expectedAfterCommit)
        writer.join()
        assertThat(readCheckpointFile()).isEqualTo(expectedAfterCommit)
    }

    private fun assertCommittedCheckpointIs(path: Path, byteOffset: Long, actual: Checkpoint) {
        val expected = Checkpoint(fileKey = attrs.fileKeyOf(path), path = path, byteOffset = byteOffset)
        assertThat(actual).isEqualTo(expected)
        assertThat(readCheckpointFile()).isEqualTo(expected)
    }

    private fun filesConfig() = FilesConfig(root = tempDir.toString(), glob = "app.log*")

    private fun createLogFile(name: String, size: Long): Path {
        val path = tempDir.resolve(name)
        Files.write(path, ByteArray(size.toInt()))
        attrs.register(path)
        return path
    }

    private fun writeCheckpointFile(checkpoint: Checkpoint) {
        val dir = tempDir.resolve("log-nanny")
        Files.createDirectories(dir)
        dir.resolve("$GROUP-checkpoint.txt").outputStream().use { Json.encodeToStream(checkpoint, it) }
    }

    private fun readCheckpointFile(): Checkpoint =
        tempDir.resolve("log-nanny/$GROUP-checkpoint.txt").inputStream().use { Json.decodeFromStream(it) }

    /**
     * Simulates a filesystem's attribute reader without depending on the platform:
     * - In [FileKeyMode.WITH_FILE_KEY] mode every registered file gets a stable synthetic key that survives renames.
     * - In [FileKeyMode.WITHOUT_FILE_KEY] mode reads always return a null fileKey, mirroring filesystems without inode-style identifiers.
     * Size is always read from the real on-disk file so we don't have to thread sizes through every assertion.
     */
    private class FakeAttrReader(private val mode: FileKeyMode) : FileAttrReader {
        private val keys = mutableMapOf<Path, String>()
        private var nextKey = 0

        fun register(path: Path) {
            keys[path] = "key-${nextKey++}"
        }

        fun fileKeyOf(path: Path): String? = if (mode == FileKeyMode.WITH_FILE_KEY) keys.getValue(path) else null

        override fun read(path: Path): FileAttrs = FileAttrs(fileKey = fileKeyOf(path), size = Files.size(path))
    }

    private companion object {
        const val GROUP = "my-group"
    }
}