package com.wingedsheep.gameserver.session

import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.sdk.model.CharacteristicValue
import com.wingedsheep.sdk.scripting.values.DynamicAmount
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.*

/** Walk generated typed fields, sharing unchanged objects. No browser schema or JSON tree. */
@OptIn(ExperimentalSerializationApi::class)
internal object SeatReferences {
    fun <T> strings(serializer: KSerializer<T>, value: T): Set<String> {
        val found = HashSet<String>()
        serializer.serialize(object : AbstractEncoder() {
            override val serializersModule = engineSerializersModule
            override fun encodeValue(value: Any) { if (value is String) found += value }
            override fun encodeNull() {}
            override fun <V> encodeSerializableValue(serializer: SerializationStrategy<V>, value: V) {
                // The SDK's compact characteristic serializer is JSON-specific; its sole child
                // is traversed directly, as in ScriptTreeWalker.
                when (value) {
                    is CharacteristicValue.Fixed -> Unit
                    is CharacteristicValue.Dynamic -> encodeSerializableValue(DynamicAmount.serializer(), value.source)
                    is CharacteristicValue.DynamicWithOffset -> encodeSerializableValue(DynamicAmount.serializer(), value.source)
                    else -> serializer.serialize(this, value)
                }
            }
        }, value)
        return found
    }

    fun <T> map(serializer: KSerializer<T>, value: T, rename: (String) -> String?): T {
        @Suppress("UNCHECKED_CAST")
        when (value) {
            is CharacteristicValue.Fixed -> return value
            is CharacteristicValue.Dynamic -> {
                val source = map(DynamicAmount.serializer(), value.source, rename)
                return (if (source === value.source) value else value.copy(source = source)) as T
            }
            is CharacteristicValue.DynamicWithOffset -> {
                val source = map(DynamicAmount.serializer(), value.source, rename)
                return (if (source === value.source) value else value.copy(source = source)) as T
            }
        }
        val fields = LinkedHashMap<Int, Any?>()
        var changed = false
        serializer.serialize(object : AbstractEncoder() {
            override val serializersModule = engineSerializersModule
            private var index = -1
            override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
                this.index = index; return true
            }
            override fun encodeValue(value: Any) {
                val mapped = if (value is String) rename(value) ?: value else value
                if (mapped != value) changed = true
                fields[index] = mapped
            }
            override fun encodeNull() { fields[index] = null }
            override fun <V> encodeSerializableValue(serializer: SerializationStrategy<V>, value: V) {
                @Suppress("UNCHECKED_CAST")
                val mapped = map(serializer as KSerializer<V>, value, rename)
                if (mapped !== value) changed = true
                fields[index] = mapped
            }
        }, value)
        if (!changed) return value
        return serializer.deserialize(object : AbstractDecoder() {
            override val serializersModule = engineSerializersModule
            private val indices = fields.keys.iterator()
            private var index = -1
            override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
                if (!indices.hasNext()) return CompositeDecoder.DECODE_DONE
                index = indices.next(); return index
            }
            override fun decodeValue(): Any = requireNotNull(fields[index])
            override fun decodeNotNullMark(): Boolean = fields[index] != null
            override fun decodeNull(): Nothing? = null
            override fun <V> decodeSerializableValue(deserializer: DeserializationStrategy<V>): V {
                @Suppress("UNCHECKED_CAST")
                return fields[index] as V
            }
        })
    }
}
