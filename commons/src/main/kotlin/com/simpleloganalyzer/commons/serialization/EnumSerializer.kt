package com.simpleloganalyzer.commons.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

// A bit sad that I had to do this, but I find the default enum serializer bad in terms of error message, and its implementation looks overcomplicated.
// I am probably missing the point of why they had to do it the way they did, but ok. This is simple and gets me what I want.
open class EnumSerializer<T: Enum<T>>(private val clazz: Class<T>) : KSerializer<T> {
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