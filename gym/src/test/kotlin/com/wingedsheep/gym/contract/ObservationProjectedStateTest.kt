package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.CrewVehicle
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.battlefield.SummoningSicknessComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
import com.wingedsheep.engine.state.components.stack.ActivatedAbilityOnStackComponent
import com.wingedsheep.engine.state.components.stack.SpellOnStackComponent
import com.wingedsheep.engine.state.components.stack.TriggeredAbilityOnStackComponent
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.mtg.sets.definitions.blc.cards.RollingHamsphere
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.model.EntityId
import io.kotest.matchers.shouldBe

/** Public Gym fields must read the engine semantic authority for the object kind in question. */
class ObservationProjectedStateTest : ScenarioTestBase() {

    init {
        cardRegistry.register(RollingHamsphere)

        test("battlefield names follow the layer projection") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Test Hasty Prospector")
                .withCardOnBattlefield(1, "Witness Protection")
                .build()
            val creature = permanentNamed(game.state, "Test Hasty Prospector")
            val aura = permanentNamed(game.state, "Witness Protection")

            // Nothing has renamed it yet: projection carries no name and the printed one stands.
            game.state.projectedState.getName(creature) shouldBe null
            entity(observe(game.state, game.player1Id), creature).name shouldBe
                "Test Hasty Prospector"

            val enchanted = game.state.updateEntity(aura) { it.with(AttachedToComponent(creature)) }

            enchanted.projectedState.getName(creature) shouldBe "Legitimate Businessperson"
            entity(observe(enchanted, game.player1Id), creature).name shouldBe
                "Legitimate Businessperson"
        }

        test("an uncrewed Vehicle has no public power or toughness") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, RollingHamsphere.name)
                .withCardOnBattlefield(1, "Hill Giant")
                .build()
            val vehicle = permanentNamed(game.state, RollingHamsphere.name)
            val creature = permanentNamed(game.state, "Hill Giant")

            game.state.projectedState.isCreature(vehicle) shouldBe false
            // Projection retains the printed values for a later animation, but public P/T is
            // conditional on the object currently being a creature.
            game.state.projectedState.getPower(vehicle) shouldBe 4
            val observation = observe(game.state, game.player1Id)
            entity(observation, vehicle).let {
                it.power shouldBe null
                it.toughness shouldBe null
            }
            // The gate hides P/T for non-creatures only — a creature beside it still reports its.
            entity(observation, creature).let {
                it.power shouldBe 3
                it.toughness shouldBe 3
            }
        }

        test("crewing a Vehicle makes its power and toughness public") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, RollingHamsphere.name)
                .withCardOnBattlefield(1, "Hill Giant", summoningSickness = false)
                .build()
            val vehicle = permanentNamed(game.state, RollingHamsphere.name)
            val crew = permanentNamed(game.state, "Hill Giant")

            game.execute(CrewVehicle(game.player1Id, vehicle, listOf(crew))).error shouldBe null
            game.resolveStack()

            game.state.projectedState.isCreature(vehicle) shouldBe true
            entity(observe(game.state, game.player1Id), vehicle).let {
                it.power shouldBe 4
                it.toughness shouldBe 4
            }
        }

        test("haste suppresses effective summoning sickness in the observation") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(
                    1,
                    "Test Hasty Prospector",
                    summoningSickness = true,
                )
                .build()
            val creature = permanentNamed(game.state, "Test Hasty Prospector")

            // The engine marker is what makes this a real suppression rather than an absent flag.
            game.state.getEntity(creature)!!.has<SummoningSicknessComponent>() shouldBe true
            game.state.projectedState.hasKeyword(creature, Keyword.HASTE) shouldBe true
            entity(observe(game.state, game.player1Id), creature).summoningSick shouldBe false
        }

        test("a creature without haste still reports the restriction") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Hill Giant", summoningSickness = true)
                .build()
            val creature = permanentNamed(game.state, "Hill Giant")

            game.state.projectedState.hasKeyword(creature, Keyword.HASTE) shouldBe false
            entity(observe(game.state, game.player1Id), creature).summoningSick shouldBe true
        }

        test("a face-down permanent reveals its card only to the player who may look") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(2, "Hill Giant")
                .build()
            val morph = permanentNamed(game.state, "Hill Giant")
            val state = game.state.updateEntity(morph) { it.with(FaceDownComponent) }

            entity(observe(state, game.player1Id), morph).let {
                // CR 708.2a — a 2/2 creature with no name, no subtypes and no mana cost.
                it.name shouldBe "Face-down creature"
                it.cardDefinitionId shouldBe null
                it.oracleText shouldBe ""
                it.manaCost shouldBe ""
                it.manaValue shouldBe 0
                it.types shouldBe setOf("CREATURE")
                it.subtypes shouldBe emptySet()
                it.power shouldBe 2
                it.toughness shouldBe 2
            }
            // CR 708.5 — its controller may look at it.
            entity(observe(state, game.player2Id), morph).let {
                it.name shouldBe "Hill Giant"
                it.cardDefinitionId shouldBe "Hill Giant"
            }
        }

        test("stack controller and kind come from stack components rather than battlefield projection") {
            val game = scenario()
                .withPlayers()
                .withCardInHand(2, "Hill Giant")
                .build()
            val spell = game.state.getHand(game.player2Id).single()
            val trigger = EntityId.of("trigger-on-stack")
            val activation = EntityId.of("activation-on-stack")
            val state = game.state
                .removeFromZone(ZoneKey(game.player2Id, Zone.HAND), spell)
                .updateEntity(spell) { it.with(SpellOnStackComponent(casterId = game.player2Id)) }
                .withEntity(
                    trigger,
                    ComponentContainer.of(
                        TriggeredAbilityOnStackComponent(
                            sourceId = EntityId.of("trigger-source"),
                            sourceName = "Trigger Source",
                            controllerId = game.player1Id,
                            effect = Effects.DrawCards(1),
                            description = "Draw a card",
                        ),
                    ),
                )
                .withEntity(
                    activation,
                    ComponentContainer.of(
                        ActivatedAbilityOnStackComponent(
                            sourceId = EntityId.of("activation-source"),
                            sourceName = "Activation Source",
                            controllerId = game.player2Id,
                            effect = Effects.DrawCards(1),
                        ),
                    ),
                )
                .copy(stack = listOf(spell, trigger, activation))

            val stack = observe(state, game.player1Id).stack.associateBy { it.entityId }
            stack.getValue(spell).let {
                it.kind shouldBe StackItemKind.SPELL
                it.controllerId shouldBe game.player2Id
                it.name shouldBe "Hill Giant"
            }
            // An ability carries no CardComponent, so its source name and description are the
            // only identity the observation can offer.
            stack.getValue(trigger).let {
                it.kind shouldBe StackItemKind.TRIGGERED_ABILITY
                it.controllerId shouldBe game.player1Id
                it.name shouldBe "Trigger Source"
                it.oracleText shouldBe "Draw a card"
            }
            stack.getValue(activation).let {
                it.kind shouldBe StackItemKind.ACTIVATED_ABILITY
                it.controllerId shouldBe game.player2Id
                it.name shouldBe "Activation Source"
            }
        }

        test("a face-down spell on the stack is nameless to its opponent") {
            val game = scenario()
                .withPlayers()
                .withCardInHand(2, "Hill Giant")
                .build()
            val spell = game.state.getHand(game.player2Id).single()
            val state = game.state
                .removeFromZone(ZoneKey(game.player2Id, Zone.HAND), spell)
                .updateEntity(spell) {
                    it.with(SpellOnStackComponent(casterId = game.player2Id, castFaceDown = true))
                }
                .copy(stack = listOf(spell))

            observe(state, game.player1Id).stack.single().let {
                it.name shouldBe "Face-down creature"
                it.oracleText shouldBe ""
            }
            observe(state, game.player2Id).stack.single().name shouldBe "Hill Giant"
        }

        test("an ability on the stack hides its face-down source but keeps its public effect text") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(2, "Hill Giant")
                .build()
            val source = permanentNamed(game.state, "Hill Giant")
            val activated = EntityId.of("face-down-activated-source")
            val triggered = EntityId.of("face-down-triggered-source")
            val state = game.state
                .updateEntity(source) { it.with(FaceDownComponent) }
                .withEntity(
                    activated,
                    ComponentContainer.of(
                        ActivatedAbilityOnStackComponent(
                            sourceId = source,
                            sourceName = "Hill Giant",
                            controllerId = game.player2Id,
                            effect = Effects.DrawCards(1),
                            descriptionOverride = "Draw a card.",
                        ),
                    ),
                )
                .withEntity(
                    triggered,
                    ComponentContainer.of(
                        TriggeredAbilityOnStackComponent(
                            sourceId = source,
                            sourceName = "Hill Giant",
                            controllerId = game.player2Id,
                            effect = Effects.DrawCards(1),
                            description = "Draw a card.",
                        ),
                    ),
                )
                .copy(stack = listOf(activated, triggered))

            observe(state, game.player1Id).stack.map { it.name } shouldBe listOf(
                "Face-down creature activated ability",
                "Face-down creature triggered ability",
            )
            observe(state, game.player1Id).stack.map { it.oracleText } shouldBe listOf(
                "Draw a card.",
                "Draw a card.",
            )
            observe(state, game.player2Id).stack.map { it.name } shouldBe listOf(
                "Hill Giant",
                "Hill Giant",
            )
        }
    }

    private fun observe(state: GameState, viewer: EntityId): TrainingObservation =
        ObservationBuilder(cardRegistry)
            .build(state, viewer, legalActions = emptyList())
            .observation as TrainingObservation

    private fun entity(observation: TrainingObservation, entityId: EntityId): EntityFeatures =
        observation.zones.flatMap { it.cards }.single { it.entityId == entityId }

    private fun permanentNamed(state: GameState, name: String): EntityId =
        state.getBattlefield().single { id ->
            state.getEntity(id)?.get<CardComponent>()?.name == name
        }
}
