package com.simpleloganalyzer.commons.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Instant

/**
 * ISO-8601 string serializer for [kotlin.time.Instant]. kotlinx-serialization 1.8.x has no
 * built-in serializer for this format.
 */
object InstantIsoSerializer : KSerializer<Instant> {
    override val descriptor =
        PrimitiveSerialDescriptor("IsoKotlinInstant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) =
        encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Instant {
        val raw = decoder.decodeString()
        try {
            return Instant.parse(raw)
        } catch (e: IllegalArgumentException) {
            throw SerializationException("Failed parsing $raw to instant", e)
        }
    }
}