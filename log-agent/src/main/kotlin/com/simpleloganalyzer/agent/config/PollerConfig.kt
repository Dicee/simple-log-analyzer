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
import java.nio.file.Path
import java.nio.file.Paths
import java.time.format.DateTimeFormatter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Serializable
data class LogGroupConfig(val log: LogSection)

@Serializable
data class LogSection(
    val files: FilesConfig,
    val format: LogFormat = LogFormat.PLAIN_TEXT,
    val date: DateConfig = DateConfig(format = "yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
    val maxEventByteSize: ByteSize = ByteSize(1L shl 18), // 256 KiB
    val transit: TransitConfig = TransitConfig(),
    private val maxPutDelaySeconds: Int = 60,
) {
    init {
        require(maxPutDelaySeconds in 1..3600) {
            "log.maxPutDelaySeconds must be between 1 and 3600 (inclusive), got $maxPutDelaySeconds"
        }
        if (format == LogFormat.PLAIN_TEXT) require(date.field == null) {
            "log.date.field is not applicable for format 'plain-text'"
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
data class FilesConfig(val root: String = "", val pattern: String) {
    init {
        require(pattern.isNotEmpty()) { "log.files.pattern must be a non-empty string" }

        val illegalGlob = pattern.firstOrNull { it in FORBIDDEN_GLOB_CHARS }
        require(illegalGlob == null) { "log.files.pattern only supports '*' as a glob metacharacter, found '$illegalGlob' in '$pattern'" }

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
data class DateConfig(val field: String? = null, val format: String) {
    init {
        if (field != null) {
            require(field.isNotEmpty()) { "log.date.field must be a non-empty string when set" }
        }

        require(format.isNotEmpty()) { "log.date.format must be a non-empty string" }
        try {
            DateTimeFormatter.ofPattern(format)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("log.date.format is not a valid Java date pattern '$format'", e)
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
        //kotlinx.serialization.descriptors.buildSerialDescriptor()
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
private class CompressionSerializer : EnumSerializer<Compression>(Compression::class.java)
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
    val compression: Compression = Compression.GZIP,
)

@Serializable(with = CompressionSerializer::class)
enum class Compression {
    @SerialName("none") NONE,
    @SerialName("gzip") GZIP,
}

private val TOML = Toml { ignoreUnknownKeys = false }

object PollerConfigParser {
    fun parse(toml: String): Map<String, LogGroupConfig> {
        return TOML.decodeFromString(serializer<Map<String, LogGroupConfig>>(), toml)
    }
}
