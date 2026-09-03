package com.wingedsheep.gym

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.ChooseOptionDecision
import com.wingedsheep.engine.core.Concede
import com.wingedsheep.engine.core.OptionChosenResponse
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.CardScript
import com.wingedsheep.sdk.scripting.effects.ChooseOptionEffect
import com.wingedsheep.sdk.scripting.effects.CompositeEffect
import com.wingedsheep.sdk.scripting.effects.OptionType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.types.shouldBeInstanceOf

class GameEnvironmentExactOneSubmissionTest : ScenarioTestBase() {

    private val twoChoices = CardDefinition.sorcery(
        name = "Two Choices",
        manaCost = ManaCost.parse("{B}"),
        oracleText = "Choose a color. Choose a color.",
        script = CardScript.spell(
            effect = CompositeEffect(
                listOf(
                    ChooseOptionEffect(OptionType.COLOR, storeAs = "firstColor"),
                    ChooseOptionEffect(OptionType.COLOR, storeAs = "secondColor")
                )
            )
        )
    )

    init {
        cardRegistry.register(twoChoices)

        test("exactly one cast leaves the spell on the stack with priority unresolved") {
            val game = scenario()
                .withPlayers()
                .withCardInHand(1, "Two Choices")
                .withLandsOnBattlefield(1, "Swamp", 1)
                .build()
            val environment = GameEnvironment.create(cardRegistry)
            environment.restore(game.state, listOf(game.player1Id, game.player2Id))
            val spell = game.state.getHand(game.player1Id).single()

            val result = environment.stepExactlyOne(CastSpell(game.player1Id, spell))

            result.shouldBeInstanceOf<ExactOneSubmissionResult.Applied>()
            environment.state.stack shouldBe listOf(spell)
            environment.state.priorityPlayerId shouldBe game.player1Id
            environment.pendingDecision.shouldBeNull()
        }

        test("exactly one decision response leaves the following genuine decision unresolved") {
            val game = scenario()
                .withPlayers()
                .withCardInHand(1, "Two Choices")
                .withLandsOnBattlefield(1, "Swamp", 1)
                .build()
            game.castSpell(1, "Two Choices")
            game.resolveStack()
            val firstDecision = game.getPendingDecision().shouldBeInstanceOf<ChooseOptionDecision>()

            val environment = GameEnvironment.create(cardRegistry)
            environment.restore(game.state, listOf(game.player1Id, game.player2Id))

            val result = environment.stepExactlyOne(
                SubmitDecision(firstDecision.playerId, OptionChosenResponse(firstDecision.id, 0))
            )

            val applied = result.shouldBeInstanceOf<ExactOneSubmissionResult.Applied>()
            val secondDecision = applied.step.pendingDecision.shouldBeInstanceOf<ChooseOptionDecision>()
            (secondDecision.id == firstDecision.id) shouldBe false
        }

        test("rejection is explicit and state-atomic") {
            val game = scenario()
                .withPlayers()
                .withCardInHand(1, "Two Choices")
                .withLandsOnBattlefield(1, "Swamp", 1)
                .build()
            val environment = GameEnvironment.create(cardRegistry)
            environment.restore(game.state, listOf(game.player1Id, game.player2Id))
            val spell = game.state.getHand(game.player1Id).single()
            val rejectedAction = CastSpell(game.player2Id, spell)
            val stateBefore = environment.state

            val result = environment.stepExactlyOne(rejectedAction)

            val rejected = result.shouldBeInstanceOf<ExactOneSubmissionResult.Rejected>()
            rejected.action shouldBe rejectedAction
            rejected.reason.shouldNotBeBlank()
            environment.state shouldBe stateBefore
            environment.events.shouldBeEmpty()
            environment.lastStepEvents.shouldBeEmpty()
            environment.lastRejection shouldBe rejected.reason
            environment.stepCount shouldBe 1
        }

        test("an accepted terminal action keeps ordinary terminal rewards") {
            val game = scenario().withPlayers().build()
            val environment = GameEnvironment.create(cardRegistry)
            environment.restore(game.state, listOf(game.player1Id, game.player2Id))

            val result = environment.stepExactlyOne(Concede(game.player1Id))

            val applied = result.shouldBeInstanceOf<ExactOneSubmissionResult.Applied>()
            applied.step.terminated shouldBe true
            applied.step.reward[game.player1Id] shouldBe -1.0
            applied.step.reward[game.player2Id] shouldBe 1.0
        }
    }
}
