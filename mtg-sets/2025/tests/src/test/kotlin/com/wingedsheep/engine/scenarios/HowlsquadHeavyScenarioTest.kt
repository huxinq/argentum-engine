package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.mechanics.layers.Layer
import com.wingedsheep.engine.mechanics.layers.SerializableModification
import com.wingedsheep.engine.mechanics.layers.StateProjector
import com.wingedsheep.engine.mechanics.layers.addFloatingEffect
import com.wingedsheep.engine.mechanics.mana.ManaSolver
import com.wingedsheep.engine.mechanics.speed.SpeedService
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.state.components.combat.MustAttackThisTurnComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Speed
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.scripting.Duration
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class HowlsquadHeavyScenarioTest : ScenarioTestBase() {

    init {
        test("beginning of combat creates a hasty Goblin that must attack this combat") {
            val game = scenario()
                .withPlayers("Player", "Opponent")
                .withCardOnBattlefield(1, "Howlsquad Heavy", summoningSickness = false)
                .withCardOnBattlefield(1, "Goblin Surveyor", summoningSickness = false)
                .withCardInLibrary(1, "Mountain")
                .withCardInLibrary(2, "Mountain")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            val surveyor = game.findPermanent("Goblin Surveyor")!!
            val battlefieldBeforeCombat = game.state.getBattlefield().toSet()
            StateProjector().project(game.state).hasKeyword(surveyor, Keyword.HASTE) shouldBe true

            game.passUntilPhase(Phase.COMBAT, Step.DECLARE_ATTACKERS)
            game.resolveStack()

            val token = game.state.getBattlefield().single { it !in battlefieldBeforeCombat }
            game.state.getEntity(token)?.has<MustAttackThisTurnComponent>() shouldBe true
            StateProjector().project(game.state).hasKeyword(token, Keyword.HASTE) shouldBe true
        }

        test("max-speed mana evaluates the exact current Goblin count for affordability") {
            val game = howlsquadManaGame(includeOtherGoblins = true)
            val howlsquad = game.findPermanent("Howlsquad Heavy")!!
            val solver = ManaSolver(cardRegistry)

            val source = solver.findAvailableManaSources(game.state, game.player1Id)
                .single { it.entityId == howlsquad }

            source.producesColors shouldContain Color.RED
            source.manaAmount shouldBe 3
            solver.getAvailableManaCount(game.state, game.player1Id) shouldBe 3
            solver.canPay(game.state, game.player1Id, ManaCost.parse("{2}{R}")) shouldBe true

            val solution = solver.solve(game.state, game.player1Id, ManaCost.parse("{2}{R}"))
                .shouldNotBeNull()
            solution.sources.map { it.entityId } shouldBe listOf(howlsquad)
            solution.manaProduced[howlsquad]?.amount shouldBe 3
        }

        test("auto-payment taps Howlsquad and preserves all surplus red mana") {
            val game = howlsquadManaGame(includeOtherGoblins = true, includeLightningStrike = true)
            val howlsquad = game.findPermanent("Howlsquad Heavy")!!

            game.castSpellTargetingPlayer(1, "Lightning Strike", 2).error shouldBe null

            game.state.getEntity(howlsquad)?.has<TappedComponent>() shouldBe true
            val pool = game.state.getEntity(game.player1Id)?.get<ManaPoolComponent>()
            (pool?.red ?: 0) shouldBe 1
        }

        test("a zero-producing Howlsquad is not offered as a usable mana source") {
            val game = howlsquadManaGame(includeOtherGoblins = false)
            val howlsquad = game.findPermanent("Howlsquad Heavy")!!

            // A type-changing effect can leave Howlsquad with its mana ability but no Goblin
            // permanents to count. This is a compact way to exercise the zero end of the actual
            // card's DynamicAmount without replacing it with a generic test ability.
            game.state = game.state.addFloatingEffect(
                layer = Layer.TYPE,
                modification = SerializableModification.SetCreatureSubtypes(emptySet()),
                affectedEntities = setOf(howlsquad),
                duration = Duration.Permanent,
                context = EffectContext(
                    sourceId = howlsquad,
                    controllerId = game.player1Id,
                    targets = emptyList(),
                    xValue = null,
                ),
            )

            game.state.projectedState.getSubtypes(howlsquad) shouldNotContain "Goblin"
            val solver = ManaSolver(cardRegistry)
            solver.findAvailableManaSources(game.state, game.player1Id)
                .map { it.entityId } shouldNotContain howlsquad
            solver.getAvailableManaCount(game.state, game.player1Id) shouldBe 0
            solver.canPay(game.state, game.player1Id, ManaCost.parse("{R}")) shouldBe false
        }
    }

    private fun howlsquadManaGame(
        includeOtherGoblins: Boolean,
        includeLightningStrike: Boolean = false,
    ): TestGame {
        val builder = scenario()
            .withPlayers("Player", "Opponent")
            .withCardOnBattlefield(1, "Howlsquad Heavy", summoningSickness = false)
            .withCardInLibrary(1, "Mountain")
            .withCardInLibrary(2, "Mountain")
            .withActivePlayer(1)
            .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
        if (includeOtherGoblins) {
            builder
                .withCardOnBattlefield(1, "Burnout Bashtronaut", summoningSickness = false)
                .withCardOnBattlefield(1, "Hexing Squelcher", summoningSickness = false)
        }
        if (includeLightningStrike) {
            builder.withCardInHand(1, "Lightning Strike")
        }

        val game = builder.build()
        game.state = SpeedService.set(game.state, game.player1Id, Speed.MAX, "test").first
        return game
    }
}
