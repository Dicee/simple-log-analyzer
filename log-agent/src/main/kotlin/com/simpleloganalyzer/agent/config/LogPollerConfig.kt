package com.simpleloganalyzer.agent.config

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.serializer
import net.peanuuutz.tomlkt.Toml
import org.jetbrains.annotations.VisibleForTesting
import java.nio.file.Path
import java.nio.file.Paths
import java.time.format.DateTimeFormatter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// Internal configuration of the agent, separate from the log group config that is set by the user. Some parameters
// may be set in the CLI, but mostly it's to allow injectability in tests
@VisibleForTesting
internal const val DEFAULT_FILE_CACHE_EXPIRY_SECONDS = 10L
internal const val DEFAULT_PENDING_FILES_PER_LOG_GROUP = 50

private const val LOG_BATCH_SIZE = 1000 // non-configurable, comes from the ingestion service
private val DEFAULT_JITTER = 100.milliseconds

// how long to wait before restarting a log group's pipeline after a failure
private val DEFAULT_RESTART_BACKOFF = 15.seconds
private val DEFAULT_PUBLISH_RETRY_INITIAL_BACKOFF = 200.milliseconds
private val DEFAULT_PUBLISH_RETRY_MAX_BACKOFF = DEFAULT_RESTART_BACKOFF

data class LogPollerConfig(
    val fileCacheExpirySeconds: Long = DEFAULT_FILE_CACHE_EXPIRY_SECONDS,
    val maxPendingFilesPerLogGroup: Int = DEFAULT_PENDING_FILES_PER_LOG_GROUP,
    val logBatchSize: Int = LOG_BATCH_SIZE,
    val jitter: Duration = DEFAULT_JITTER,
    val restartBackoff: Duration = DEFAULT_RESTART_BACKOFF,
    val publishRetryInitialBackoff: Duration = DEFAULT_PUBLISH_RETRY_INITIAL_BACKOFF,
    val publishRetryMaxBackoff: Duration = DEFAULT_PUBLISH_RETRY_MAX_BACKOFF,
) {
    init {
        require(maxPendingFilesPerLogGroup > 0) { "Max pending files per log group must be positive" }
        require(fileCacheExpirySeconds > 0) { "File cache expiry seconds must be positive" }
        require(logBatchSize > 0) { "Log batch size must be positive" }
    }
}

// Customer configuration of the log groups present on their disk

@Serializable
data class LogGroupConfig(val log: LogSection)

@VisibleForTesting
internal const val DEFAULT_MAX_PUT_DELAY_SECONDS = 60

@Serializable
data class LogSection(
    val files: FilesConfig,
    val format: LogFormat = LogFormat.PLAIN_TEXT,
    val date: DateConfig = DateConfig(),
    val maxEventByteSize: ByteSize = ByteSize(1L shl 18), // 256 KiB
    val transit: TransitConfig = TransitConfig(),
    private val maxPutDelaySeconds: Int = DEFAULT_MAX_PUT_DELAY_SECONDS,
) {
    init {
        require(maxPutDelaySeconds in 1..300) {
            "log.maxPutDelaySeconds must be between 1 and 300 (inclusive), got $maxPutDelaySeconds"
        }
        if (format == LogFormat.PLAIN_TEXT) require(date.field == null) {
            "log.date.field is not applicable for format ${format.displayName}"
        }
    }

    val effectiveDateField: String?
        get() = when (format) {
            LogFormat.PLAIN_TEXT -> null
            LogFormat.JSON, LogFormat.LOGFMT -> date.field ?: "timestamp"
        }

    val maxPutDelay: Duration
        get() = maxPutDelaySeconds.seconds
}

@Serializable
data class FilesConfig(val root: String = "", val glob: String) {
    init {
        require(glob.isNotEmpty()) { "log.files.glob must be a non-empty string" }

        val illegalGlob = glob.firstOrNull { it in FORBIDDEN_GLOB_CHARS }
        require(illegalGlob == null) { "log.files.glob only supports '*' as a glob metacharacter, found '$illegalGlob' in '$glob'" }

        if (root.isNotEmpty()) {
            require(root.isNotBlank()) { "log.files.root must not be blank when set" }
        }
    }

    val rootPath: Path
        get() = if (root.isEmpty()) Paths.get("").toAbsolutePath() else Paths.get(root)

    companion object {
        private val FORBIDDEN_GLOB_CHARS = setOf('?', '[', ']', '{', '}')
    }
}

@Serializable(with = LogFormatSerializer::class)
enum class LogFormat(val displayName: String) {
    @SerialName("plain-text") PLAIN_TEXT("plain-text"),
    @SerialName("json") JSON("json"),
    @SerialName("logfmt") LOGFMT("logfmt"),
}

@Serializable
data class DateConfig(val field: String? = null, val format: String? = null, val isEpochMillis: Boolean = false) {
    init {
        require(!isEpochMillis || format == null) { "log.date.format and log.date.isEpochMillis are mutually exclusive" }

        if (field != null) {
            require(format != null || isEpochMillis) { "log.date.field requires either log.date.format or log.date.isEpochMillis to be set" }
            require(field.isNotEmpty()) { "log.date.field must be a non-empty string when set" }
        } else {
            require(!isEpochMillis) { "log.date.isEpochMillis cannot be set without log.field" }
        }

        if (format != null) {
            require(format.isNotEmpty()) { "log.date.format must be a non-empty string" }
            try {
                DateTimeFormatter.ofPattern(format)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("log.date.format is not a valid Java date pattern '$format'", e)
            }
        }
    }
}

@Serializable(with = ByteSizeSerializer::class)
data class ByteSize(val bytes: Long) {
    init {
        require(bytes > 0) { "byte size must be strictly positive, got $bytes" }
    }

    companion object {
        private val PATTERN = Regex("^(\\d+)([KMG]?)$", RegexOption.IGNORE_CASE)

        fun parse(raw: String): ByteSize {
            val match = PATTERN.matchEntire(raw) ?: throw IllegalArgumentException(
                "byte size must be a positive number optionally followed by K, M, or G " +
                        "(e.g. '1M', '512K', '1024'), got '$raw'"
            )
            val n = match.groupValues[1].toLong()
            val multiplier = when (match.groupValues[2].uppercase()) {
                "" -> 1L
                "K" -> 1024L
                "M" -> 1024L * 1024
                "G" -> 1024L * 1024 * 1024
                else -> error("unreachable: regex restricts suffix to [KMG]?")
            }
            return ByteSize(n * multiplier)
        }
    }
}

private object ByteSizeSerializer : KSerializer<ByteSize> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ByteSize", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): ByteSize {
        val raw = decoder.decodeString()
        return try {
            ByteSize.parse(raw)
        } catch (e: IllegalArgumentException) {
            throw SerializationException(e.message, e)
        }
    }

    override fun serialize(encoder: Encoder, value: ByteSize) {
        // we do not bother implementing a nicer format with KMG, because in practice we'll never serialize, only deserialize
        encoder.encodeString(value.bytes.toString())
    }
}

// A bit sad that I had to do this, but I find the default enum serializer bad in terms of error message, and its implementation looks overcomplicated.
// I am probably missing the point of why they had to do it the way they did, but ok. This is simple and gets me what I want.
private class LogFormatSerializer : EnumSerializer<LogFormat>(LogFormat::class.java)
private class CompressionSerializer : EnumSerializer<CompressionMode>(CompressionMode::class.java)
private open class EnumSerializer<T: Enum<T>>(private val clazz: Class<T>) : KSerializer<T> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor(clazz.name, PrimitiveKind.STRING)

    private val bySerialName: Map<String, T> = clazz.enumConstants.associateBy { constant ->
        clazz.getDeclaredField(constant.name).getDeclaredAnnotation(SerialName::class.java)?.value ?: constant.name
    }

    override fun deserialize(decoder: Decoder): T {
        val name = decoder.decodeString()
        return bySerialName[name]
            ?: throw SerializationException("Invalid value '$name' for enumeration ${clazz.simpleName}. Valid values: ${bySerialName.keys}")
    }

    override fun serialize(encoder: Encoder, value: T) {
        val serialName = clazz.getDeclaredField(value.name).getDeclaredAnnotation(SerialName::class.java)?.value ?: value.name
        encoder.encodeString(serialName)
    }
}

@Serializable
data class TransitConfig(
    val compression: CompressionMode = CompressionMode.GZIP,
)

@Serializable(with = CompressionSerializer::class)
enum class CompressionMode {
    @SerialName("none") NONE,
    @SerialName("gzip") GZIP,
}

private val TOML = Toml { ignoreUnknownKeys = false }

object LogPollerConfigParser {
    fun parse(toml: String): Map<String, LogGroupConfig> {
        return TOML.decodeFromString(serializer<Map<String, LogGroupConfig>>(), toml)
    }
}