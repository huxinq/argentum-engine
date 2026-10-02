package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.state.components.stack.*
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.*
import com.wingedsheep.sdk.dsl.*
import com.wingedsheep.sdk.model.*
import com.wingedsheep.sdk.scripting.*
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import com.wingedsheep.sdk.scripting.targets.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull

class OptionalAbilityCopyTest : FunSpec({
    val copier = card("Optional Ability Copier") {
        manaCost = "{0}"; typeLine = "Instant"
        spell { effect = Effects.CopyTargetSpellOrAbility(target(TargetFilter.ActivatedOrTriggeredAbilityOnStack.youControl())) }
    }
    fun driver(source: CardDefinition) = GameTestDriver().also {
        it.registerCards(TestCards.all + listOf(source, copier))
        it.initMirrorMatch(Deck.of("Forest" to 40))
        it.passPriorityUntil(Step.PRECOMBAT_MAIN)
    }
    fun copy(d: GameTestDriver, original: EntityId) {
        val spell = d.putCardInHand(d.player1, copier.name)
        d.submitSuccess(CastSpell(d.player1, spell, targets = listOf(ChosenTarget.Spell(original))))
        d.bothPass().error.shouldBeNull()
    }
    fun resolve(d: GameTestDriver) {
        repeat(8) { if (d.state.stack.isNotEmpty()) d.passPriority(d.state.priorityPlayerId!!).error.shouldBeNull() }
        d.state.stack shouldBe emptyList()
        d.getLifeTotal(d.player1) shouldBe 22
    }
    for (triggered in listOf(false, true)) {
        test("all-declined ${if (triggered) "triggered" else "activated"} copies retain declarations and resolve untargeted effects") {
            val source = card("Declined Copy Source") {
                manaCost = "{0}"; typeLine = "Artifact"
                if (triggered) triggeredAbility {
                    trigger = Triggers.self.enters()
                    target(TargetPlayer(optional = true, id = "player"))
                    effect = Effects.GainLife(1)
                } else activatedAbility {
                    cost = Costs.Mana("{0}")
                    target(TargetPlayer(optional = true, id = "player"))
                    effect = Effects.GainLife(1)
                }
            }
            val d = driver(source)
            if (triggered) {
                val card = d.putCardInHand(d.player1, source.name)
                d.submitSuccess(CastSpell(d.player1, card)); d.bothPass().error.shouldBeNull()
            } else {
                val card = d.putPermanentOnBattlefield(d.player1, source.name)
                d.submit(ActivateAbility(d.player1, card, source.activatedAbilities.single().id)).error.shouldBeNull()
            }
            if (d.pendingDecision is ChooseTargetsDecision) d.submitMultiTargetSelection(d.player1, emptyMap()).error.shouldBeNull()
            val original = d.getTopOfStack()!!
            d.state.getEntity(original)!!.get<TargetsComponent>()!!.targetRequirements.size shouldBe 1
            copy(d, original)
            d.pendingDecision.shouldBeNull()
            val copied = d.state.getEntity(d.getTopOfStack()!!)!!.get<TargetsComponent>()!!
            copied.targets shouldBe emptyList()
            copied.targetRequirements.single().count shouldBe 0
            resolve(d)
        }
    }
    for (count in listOf(1, 2)) {
        test("declined empty slot does not suppress retargeting and selected slot preserves $count chosen targets") {
            val source = card("Mixed Copy Source") {
                manaCost = "{0}"; typeLine = "Artifact"
                triggeredAbility {
                    trigger = Triggers.self.enters()
                    target(TargetObject(filter = TargetFilter.Creature, optional = true, id = "empty"))
                    target(TargetPlayer(count = 2, optional = true, id = "players"))
                    effect = Effects.GainLife(1)
                }
            }
            val d = driver(source)
            val card = d.putCardInHand(d.player1, source.name)
            d.submitSuccess(CastSpell(d.player1, card)); d.bothPass().error.shouldBeNull()
            val originalTargets = if (count == 1) listOf(d.player2) else listOf(d.player1, d.player2)
            d.submitMultiTargetSelection(d.player1, mapOf(1 to originalTargets)).error.shouldBeNull()
            val original = d.getTopOfStack()!!
            copy(d, original)
            val question = d.pendingDecision as ChooseTargetsDecision
            question.targetRequirements.map { it.index } shouldBe listOf(1)
            question.targetRequirements.single().minTargets shouldBe count
            question.targetRequirements.single().maxTargets shouldBe count
            question.legalTargets.keys shouldBe setOf(1)
            val replacements = if (count == 1) listOf(d.player1) else listOf(d.player2, d.player1)
            d.submitMultiTargetSelection(d.player1, mapOf(1 to replacements)).error.shouldBeNull()
            val copied = d.state.getEntity(d.getTopOfStack()!!)!!.get<TargetsComponent>()!!
            copied.targetRequirements.map { it.count } shouldBe listOf(0, count)
            copied.targets shouldBe replacements.map { ChosenTarget.Player(it) }
            resolve(d)
        }
    }
})
