@file:OptIn(ExperimentalSerializationApi::class)

package com.simpleloganalyzer.commons.serialization

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class InstantIsoSerializerTest {
    private val json = Json {
        serializersModule = SerializersModule {
            contextual(Instant::class, InstantIsoSerializer)
        }
    }
    private val contextualSerializer = json.serializersModule.getContextual(Instant::class) as KSerializer<Instant>

    @Test
    fun testRoundTrip() {
        val initial = Instant.fromEpochMilliseconds(1002233456)
        val s = json.encodeToString(contextualSerializer, initial)

        assertThat(s).isEqualTo("\"1970-01-12T14:23:53.456Z\"")

        val roundTripped = json.decodeFromString(contextualSerializer,s)
        assertThat(roundTripped).isEqualTo(initial)
    }

    @Test
    fun testDeserializationFailure() {
        assertThatThrownBy { json.decodeFromString(contextualSerializer,"\"1970-01-12 14:23:53.456Z\"") }
            .isExactlyInstanceOf(SerializationException::class.java)
            .hasMessage("Failed parsing 1970-01-12 14:23:53.456Z to instant")
            .hasRootCauseInstanceOf(IllegalArgumentException::class.java)
    }
}