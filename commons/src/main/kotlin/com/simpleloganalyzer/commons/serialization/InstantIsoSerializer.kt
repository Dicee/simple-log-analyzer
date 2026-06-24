package com.simpleloganalyzer.commons.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Instant

/**
 * ISO-8601 string serializer for [kotlin.time.Instant]. kotlinx-serialization 1.8.x has no
 * built-in, and the stdlib `Instant.toString()` / `Instant.parse(...)` already round-trip via
 * ISO-8601, so this is a one-liner each way.
 */
object InstantIsoSerializer : KSerializer<Instant> {
    override val descriptor =
        PrimitiveSerialDescriptor("kotlin.time.Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) =
        encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Instant =
        Instant.parse(decoder.decodeString())
}