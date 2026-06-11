package com.simpleloganalyzer.agent.config

import com.simpleloganalyzer.testcommons.assertions.MoreAssertions
import kotlinx.serialization.SerializationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Paths
import kotlin.time.Duration.Companion.seconds

class LogPollerConfigParserTest {
    @Test
    fun testParse_validation_maxPutDelay_zero() {
        val toml = """
            [g]
            log.files.glob = "x"
            log.maxPutDelaySeconds = 0
        """.trimIndent()

        MoreAssertions.assertThatThrownBy { LogPollerConfigParser.parse(toml) }
            .isLike(IllegalArgumentException("log.maxPutDelaySeconds must be between 1 and 3600 (inclusive), got 0"))
    }

    @Test
    fun testParse_validation_maxPutDelay_aboveUpperBound() {
        val toml = """
            [g]
            log.files.glob = "x"
            log.maxPutDelaySeconds = 3601
        """.trimIndent()

        MoreAssertions.assertThatThrownBy { LogPollerConfigParser.parse(toml) }
            .isLike(IllegalArgumentException("log.maxPutDelaySeconds must be between 1 and 3600 (inclusive), got 3601"))
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 3600])
    fun testParse_validation_maxPutDelay_atLowerAndUpperBounds(value: Int) {
        val toml = """
            [g]
            log.files.glob = "x"
            log.maxPutDelaySeconds = $value
        """.trimIndent()

        val log = LogPollerConfigParser.parse(toml).getValue("g").log
        assertThat(log.maxPutDelay).isEqualTo(value.seconds)
    }

    @Test
    fun testParse_validation_dateFieldOnPlainText() {
        val toml = """
            [g]
            log.files.glob = "x"
            log.format = "plain-text"
            log.date.format = "yyyy-MM-dd"
            log.date.field = "ts"
        """.trimIndent()

        MoreAssertions.assertThatThrownBy { LogPollerConfigParser.parse(toml) }
            .isLike(IllegalArgumentException(("log.date.field is not applicable for format plain-text")))
    }

    @Test
    fun testParse_validation_unknownFormatValue() {
        val toml = """
            [g]
            log.files.glob = "x"
            log.format = "xml"
        """.trimIndent()

        MoreAssertions.assertThatThrownBy { LogPollerConfigParser.parse(toml) }
            .isLike(SerializationException("Invalid value 'xml' for enumeration LogFormat. Valid values: [plain-text, json, logfmt]"))
    }

    @Test
    fun testParse_validation_unknownCompressionValue() {
        val toml = """
            [g]
            log.files.glob = "x"
            log.transit.compression = "snappy"
        """.trimIndent()

        MoreAssertions.assertThatThrownBy { LogPollerConfigParser.parse(toml) }
            .isLike(SerializationException("Invalid value 'snappy' for enumeration CompressionMode. Valid values: [none, gzip]"))
    }

    @Test
    fun testParse_validation_logFiles_missingPattern() {
        val toml = """
            [g]
            log.files.root = "/var/log"
        """.trimIndent()

        assertThatThrownBy { LogPollerConfigParser.parse(toml) }
            .hasMessageContainingAll("Field 'glob' is required for type with serial name", "FilesConfig")
    }

    @Test
    fun testParse_validation_unknownKey() {
        val toml = """
            [g]
            log.files.glob = "x"
            log.bogus = "y"
        """.trimIndent()

        assertThatThrownBy { LogPollerConfigParser.parse(toml) }
            .isInstanceOf(SerializationException::class.java)
            .hasMessage("bogus")
    }

    @Test
    fun testParse_validation_unsupportedGlobChar() {
        val toml = """
            [g]
            log.files.glob = "app.?.log"
        """.trimIndent()

        MoreAssertions.assertThatThrownBy { LogPollerConfigParser.parse(toml) }
            .isLike(IllegalArgumentException("log.files.glob only supports '*' as a glob metacharacter, found '?' in 'app.?.log'"))
    }

    @Test
    fun testParse_emptyFilesPattern_throwsNonEmptyError() {
        val toml = """
            [g]
            log.files.glob = ""
        """.trimIndent()

        MoreAssertions.assertThatThrownBy { LogPollerConfigParser.parse(toml) }
            .isLike(IllegalArgumentException("log.files.glob must be a non-empty string"))
    }
    
    @Test
    fun testParse_logFiles_onlyPatternProvided_appliesAllDefaults() {
        val toml = """
            [myapp]
            log.files.glob = "app.log"
        """.trimIndent()

        assertThat(LogPollerConfigParser.parse(toml)).isEqualTo(mapOf(
            "myapp" to LogGroupConfig(log = LogSection(files = FilesConfig(glob = "app.log"))),
        ))
    }

    @Test
    fun testParse_logFiles_rootOmitted_rootPathResolvesToCwd() {
        val toml = """
            [g]
            log.files.glob = "app.log"
        """.trimIndent()

        val config = LogPollerConfigParser.parse(toml).getValue("g")
        assertThat(config.log.files.rootPath).isEqualTo(Paths.get("").toAbsolutePath())
    }

    @Test
    fun testEffectiveDateField_logFiles_jsonFormatWithoutDateField_defaultsToTimestamp() {
        val toml = """
            [api]
            log.files.glob = "app.log"
            log.format = "json"
        """.trimIndent()

        val log = LogPollerConfigParser.parse(toml).getValue("api").log
        assertThat(log.effectiveDateField).isEqualTo("timestamp")
    }

    @Test
    fun testParse_dateConfig_validation_emptyFieldOrFormat() {
        MoreAssertions.assertThatThrownBy { DateConfig(field = "", format = "yyyy-MM-dd") }
            .isLike(IllegalArgumentException("log.date.field must be a non-empty string when set"))

        MoreAssertions.assertThatThrownBy { DateConfig(format = "") }
            .isLike(IllegalArgumentException("log.date.format must be a non-empty string"))
    }

    @Test
    fun testParse_dateConfig_validation_invalidFormat() {
        val toml = """
            [g]
            log.files.glob = "x"
            log.date.format = "yyyy-MM-dd'unterminated"
        """.trimIndent()

        MoreAssertions.assertThatThrownBy { LogPollerConfigParser.parse(toml) }
            .isLike(IllegalArgumentException("log.date.format is not a valid Java date pattern 'yyyy-MM-dd'unterminated'"))
    }

    @Test
    fun testParse_dateConfig_validation_formatAndisEpochMillisMutuallyExclusive() {
        MoreAssertions.assertThatThrownBy { DateConfig(format = "yyyy-MM-dd", isEpochMillis = true) }
            .isLike(IllegalArgumentException("log.date.format and log.date.isEpochMillis are mutually exclusive"))
    }

    @Test
    fun testParse_dateConfig_validation_fieldWithoutFormat() {
        MoreAssertions.assertThatThrownBy { DateConfig(field = "ts") }
            .isLike(IllegalArgumentException("log.date.field requires either log.date.format or log.date.isEpochMillis to be set"))
    }

    @Test
    fun testParse_dateConfig_validation_isEpochMillisWithoutField() {
        MoreAssertions.assertThatThrownBy { DateConfig(isEpochMillis = true) }
            .isLike(IllegalArgumentException("log.date.isEpochMillis cannot be set without log.field"))
    }

    @Test
    fun testParse_byteSize_suffixToBytesResolution() {
        fun parseSize(value: String): Long {
            val toml = """
                [g]
                log.files.glob = "x"
                log.maxEventByteSize = "$value"
            """.trimIndent()
            return LogPollerConfigParser.parse(toml).getValue("g").log.maxEventByteSize.bytes
        }

        assertThat(parseSize("1024")).isEqualTo(1024L)
        assertThat(parseSize("2K")).isEqualTo(2L * 1024)
        assertThat(parseSize("512k")).isEqualTo(512L * 1024)
        assertThat(parseSize("3M")).isEqualTo(3L * 1024 * 1024)
        assertThat(parseSize("1G")).isEqualTo(1024L * 1024 * 1024)
    }

    @Test
    fun testParse_byteSize_invalidFormat() {
        val toml = """
            [g]
            log.files.glob = "x"
            log.maxEventByteSize = "1KB"
        """.trimIndent()

        MoreAssertions.assertThatThrownBy { LogPollerConfigParser.parse(toml) }
            .isLike(SerializationException("byte size must be a positive number optionally followed by K, M, or G (e.g. '1M', '512K', '1024'), got '1KB'"))
    }

    @Test
    fun testParse_multipleGroups() {
        val toml = """
            [myapp]
            log.files.root = "/var/log/myapp"
            log.files.glob = "application.log*"
            log.format = "json"
            log.date.field = "ts"
            log.date.format = "yyyy-MM-dd"
            log.maxEventByteSize = "512K"

            [nginx]
            log.files.root = "/var/log/nginx"
            log.files.glob = "access.log*"
            log.format = "plain-text"
            log.date.format = "dd/MM/yyyy:HH:mm:ss Z"
            log.maxEventByteSize = "1M"
            log.transit.compression = "none"
        """.trimIndent()

        assertThat(LogPollerConfigParser.parse(toml)).isEqualTo(
            mapOf(
                "myapp" to LogGroupConfig(
                    log = LogSection(
                        files = FilesConfig(root = "/var/log/myapp", glob = "application.log*"),
                        format = LogFormat.JSON,
                        date = DateConfig(field = "ts", format = "yyyy-MM-dd"),
                        maxEventByteSize = ByteSize(512L * 1024),
                    ),
                ),
                "nginx" to LogGroupConfig(
                    log = LogSection(
                        files = FilesConfig(root = "/var/log/nginx", glob = "access.log*"),
                        format = LogFormat.PLAIN_TEXT,
                        date = DateConfig(format = "dd/MM/yyyy:HH:mm:ss Z"),
                        maxEventByteSize = ByteSize(1024L * 1024),
                        transit = TransitConfig(compression = CompressionMode.NONE),
                    ),
                ),
            )
        )
    }
}
