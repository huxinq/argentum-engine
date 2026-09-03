package com.wingedsheep.engine.core

import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Encoder

@OptIn(ExperimentalSerializationApi::class)
private val entityIdSerialName = EntityId.serializer().descriptor.serialName

/** Internal seam for testing consumers' fail-closed response to projection errors. */
internal interface InFlightReferenceProjector {
    fun project(stackObject: ComponentContainer): InFlightEntityReferences.Projection
    fun project(decision: PendingDecision): InFlightEntityReferences.Projection
    fun project(frame: ContinuationFrame): InFlightEntityReferences.Projection
}

/**
 * Typed entity-reference projection for persisted in-flight engine execution.
 *
 * Live stack objects, pending decisions, and continuation frames are serializable graphs. Rather
 * than maintaining a second hand-written visitor over their many concrete shapes, this projection
 * walks their normal persistence serializers. It sees an [EntityId] only at its typed inline
 * serializer boundary, so a plain [String] which happens to equal an entity id is never mistaken
 * for a reference. Serialization also traverses collection values and map keys, which catches
 * nested `List`s and `Map<EntityId, ...>` shapes.
 *
 * This projection is complete only while persisted in-flight engine state is fully represented by
 * its normal serializers and [EntityId] keeps its normal typed inline serializer. The registration
 * hygiene test makes a missing sealed leaf observable, but registration alone is not proof that
 * every stored field is serializable. Any serialization failure therefore reports
 * [InFlightEntityReferences.Projection.Incomplete], and callers must fail closed rather than
 * treating an incomplete projection as empty.
 */
internal object InFlightEntityReferences : InFlightReferenceProjector {

    sealed interface Projection {
        data class Complete(val entityIds: Set<EntityId>) : Projection

        /** The graph could not be exhaustively traversed, so its references are unknown. */
        data class Incomplete(
            val rootType: String,
            val failure: String,
        ) : Projection
    }

    override fun project(stackObject: ComponentContainer): Projection =
        project(ComponentContainer.serializer(), stackObject)

    override fun project(decision: PendingDecision): Projection =
        project(PolymorphicSerializer(PendingDecision::class), decision)

    override fun project(frame: ContinuationFrame): Projection =
        project(PolymorphicSerializer(ContinuationFrame::class), frame)

    private fun <T : Any> project(
        serializer: SerializationStrategy<T>,
        value: T,
    ): Projection {
        val references = linkedSetOf<EntityId>()
        return try {
            serializer.serialize(EntityReferenceEncoder(references), value)
            Projection.Complete(references)
        } catch (failure: Exception) {
            Projection.Incomplete(
                rootType = value.javaClass.name,
                failure = failure.javaClass.name,
            )
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private class EntityReferenceEncoder(
        private val references: MutableSet<EntityId>,
        private val expectsEntityId: Boolean = false,
    ) : AbstractEncoder() {
        override val serializersModule = engineSerializersModule

        override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
            check(!expectsEntityId) {
                "EntityId serializer changed to a structured form; projection must be updated"
            }
            return this
        }

        @OptIn(ExperimentalSerializationApi::class)
        override fun encodeInline(descriptor: SerialDescriptor): Encoder =
            EntityReferenceEncoder(
                references = references,
                expectsEntityId = descriptor.serialName == entityIdSerialName,
            )

        override fun shouldEncodeElementDefault(descriptor: SerialDescriptor, index: Int): Boolean = true

        override fun encodeString(value: String) {
            if (expectsEntityId) references += EntityId.of(value)
        }

        override fun encodeBoolean(value: Boolean) = rejectNonStringEntityId()
        override fun encodeByte(value: Byte) = rejectNonStringEntityId()
        override fun encodeChar(value: Char) = rejectNonStringEntityId()
        override fun encodeDouble(value: Double) = rejectNonStringEntityId()
        override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) = rejectNonStringEntityId()
        override fun encodeFloat(value: Float) = rejectNonStringEntityId()
        override fun encodeInt(value: Int) = rejectNonStringEntityId()
        override fun encodeLong(value: Long) = rejectNonStringEntityId()
        override fun encodeShort(value: Short) = rejectNonStringEntityId()
        override fun encodeNull() = rejectNonStringEntityId()

        private fun rejectNonStringEntityId() {
            check(!expectsEntityId) {
                "EntityId serializer changed to a non-string form; projection must be updated"
            }
        }
    }
}
