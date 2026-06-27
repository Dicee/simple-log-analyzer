package com.simpleloganalyzer.ingestion.persistence.metadata

import com.simpleloganalyzer.ingestion.model.CompressionMode
import com.simpleloganalyzer.ingestion.model.LogFile
import com.simpleloganalyzer.ingestion.model.LogFormat
import com.simpleloganalyzer.ingestion.model.LogGroup
import com.simpleloganalyzer.ingestion.model.LogStream
import com.simpleloganalyzer.testcommons.time.FakeTickerClock
import io.mockk.spyk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.time.Instant

const val TEST_GROUP = "testGroup"
const val TEST_STREAM = "testStream"

const val STREAM_1 = "stream1"
const val STREAM_2 = "stream2"
const val FILE_1 = "file1"
const val FILE_2 = "file2"

const val OTHER_GROUP = "otherGroup"
const val OTHER_STREAM = "otherStream"

val EPOCH = Instant.fromEpochSeconds(0)

abstract class DatabaseTestBase {
    @TempDir private lateinit var tempDir: Path

    protected lateinit var clock: FakeTickerClock
    protected lateinit var db: MetadataDatabase

    internal lateinit var groupDaoSpy: LogGroupDao
    internal lateinit var streamDaoSpy: LogStreamDao
    internal lateinit var fileDaoSpy: LogFileDao

    @BeforeEach
    protected fun setUp() {
        clock = FakeTickerClock()

        val dbPath = tempDir.resolve("test.db").createFile()
        db = LogMetadataStore.connect(dbPath)

        groupDaoSpy = spyk(LogGroupDao(db, clock))
        streamDaoSpy = spyk(LogStreamDao(db, clock))
        fileDaoSpy = spyk(LogFileDao(db, clock))
    }

    protected fun createGroup(name: String): LogGroup = groupDaoSpy.create(name, LogFormat.LOGFMT, CompressionMode.NONE)
    protected fun createStream(group: String, name: String): LogStream = streamDaoSpy.create(group, name)

    protected fun newLogGroup(name: String, description: String? = null): LogGroup =
        LogGroup(name, description, EPOCH, LogFormat.LOGFMT, CompressionMode.NONE)

    protected fun newLogStream(group: String, name: String): LogStream = LogStream(group, name, EPOCH)
    protected fun newLogFile(group: String, stream: String, fileName: String, firstTimestamp: Instant, lastTimestamp: Instant): LogFile =
        LogFile(group, stream, fileName, EPOCH, EPOCH, firstTimestamp, lastTimestamp)
}