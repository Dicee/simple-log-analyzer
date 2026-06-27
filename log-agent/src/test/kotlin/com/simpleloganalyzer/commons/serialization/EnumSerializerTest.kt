package com.simpleloganalyzer.commons.serialization

import com.simpleloganalyzer.testcommons.assertions.MoreAssertions
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EnumSerializerTest {
    @Test
    fun testRoundTrip() {
        val initial = Greetings.AHOY
        val roundTripped = Json.decodeFromString<Greetings>(Json.encodeToString(initial))
        assertThat(roundTripped).isEqualTo(initial)
    }

    @Test
    fun testDeserializationFailure() {
        MoreAssertions.assertThatThrownBy { Json.decodeFromString<Greetings>("\"hola chicos\"") }
            .isLike(SerializationException("Invalid value 'hola chicos' for enumeration Greetings. Valid values: [HI, HELLO, AHOY]"))
    }

    @Serializable(with = GreetingsSerializer::class)
    private enum class Greetings { HI, HELLO, AHOY }
    private class GreetingsSerializer : EnumSerializer<Greetings>(Greetings::class.java)
}