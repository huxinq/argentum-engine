package com.wingedsheep.engine.view

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.event.GrantedTriggeredAbility
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.PipelineState
import com.wingedsheep.engine.state.components.identity.TokenBlueprintComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.*
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.model.*
import com.wingedsheep.sdk.scripting.*
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import com.wingedsheep.sdk.scripting.targets.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class RuleContextProjectionTest : FunSpec({
    val subject = CardDefinition.creature("Rule Subject", ManaCost.parse("{1}"), emptySet(), 2, 2)
    fun driver() = GameTestDriver().also {
        it.registerCards(TestCards.all + subject)
        it.initMirrorMatch(Deck.of("Forest" to 40))
        it.passPriorityUntil(Step.PRECOMBAT_MAIN)
    }
    fun visibility(d: GameTestDriver) = Visibility(d.cardRegistry, conditionEvaluator = d.services.conditionEvaluator)

    test("public rules are retained while hidden entity references are withheld") {
        val d = driver()
        val visible = d.putCreatureOnBattlefield(d.player2, subject.name)
        val hidden = d.putCardInHand(d.player2, subject.name)
        val rule = SemanticRule(effect = Effects.DealDamage(1, EffectTarget.SpecificEntity(visible)))
        RuleContextProjection.visibleRule(rule, d.state, d.player1, visibility(d)) shouldBe rule
        val secret = rule.copy(effect = Effects.DealDamage(1, EffectTarget.SpecificEntity(hidden)))
        RuleContextProjection.visibleRule(secret, d.state, d.player1, visibility(d)).shouldBeNull()
        RuleContextProjection.visibleRule(secret, d.state, d.player2, visibility(d)) shouldBe secret
        val alias = rule.copy(effect = Effects.DealDamage(1, EffectTarget.BoundVariable(hidden.value)))
        RuleContextProjection.visibleRule(alias, d.state, d.player1, visibility(d)) shouldBe alias
        val facts = RuleFacts(targetGroups = listOf(listOf(visible, hidden, null)))
        RuleContextProjection.visibleFacts(facts, d.state, d.player1, visibility(d)).targetGroups shouldBe
            listOf(listOf(visible, null, null))
    }

    test("captured questions carry rule facts without private pipeline collections") {
        val d = driver()
        val hidden = d.putCardInHand(d.player1, subject.name)
        val question = SelectCardsDecision("discard", d.player1, "Discard", DecisionContext(),
            options = listOf(hidden), minSelections = 1, maxSelections = 1)
        val suspended = d.state.copy(continuationStack = listOf(Suspension(question, HandSizeDiscardContinuation(d.player1))))
        val rule = SemanticRule(effect = Effects.GainLife(2))
        val captured = RuleContextProjection.capture(suspended, EffectContext(
            sourceId = null, controllerId = d.player1, semanticRule = rule,
            pipeline = PipelineState(storedCollections = mapOf("private" to listOf(hidden))),
        ))
        captured.pendingDecision!!.context.semanticRule shouldBe rule
        captured.pendingDecision!!.context.ruleFacts shouldBe RuleFacts(wasKicked = false)
        captured.pendingDecision!!.context.controllerId shouldBe d.player1
        RuleContextProjection.pending(captured, captured.pendingDecision!!, d.player1, visibility(d))
            .context.gameRule shouldBe PublicGameRule.HAND_SIZE_DISCARD
    }

    test("target groups preserve empty declarations and invalid target positions") {
        val target = ChosenTarget.Permanent(EntityId("visible"))
        val requirements = listOf(TargetObject(count = 0, filter = TargetFilter.Creature),
            TargetObject(count = 2, filter = TargetFilter.Creature))
        RuleContextProjection.targetGroups(requirements, listOf(null, target)) shouldBe
            listOf(emptyList(), listOf(null, target.entityId))
    }

    test("live grants retain duplicates and hide a private granting source") {
        val d = driver()
        val recipient = d.putCreatureOnBattlefield(d.player1, subject.name)
        val hidden = d.putCardInHand(d.player2, subject.name)
        val ability = TriggeredAbility(trigger = Triggers.self.enters(), effect = Effects.GainLife(1))
        val grant = GrantedTriggeredAbility(recipient, ability, Duration.EndOfTurn)
        val state = d.state.copy(grantedTriggeredAbilities = listOf(grant, grant, grant.copy(sourceId = hidden)))
        RuleContextProjection.grantedTriggeredAbilities(state, recipient, d.player1, visibility(d)).size shouldBe 2
    }

    test("generated tokens expose their resolved intrinsic definition to a public consumer") {
        val d = driver()
        val spell = CardDefinition("Definition Token", ManaCost.parse("{0}"), TypeLine.parse("Sorcery"),
            script = CardScript(spellEffect = Effects.CreateToken(power = 1, toughness = 2,
                creatureTypes = setOf("Spirit"), count = 1)))
        d.registerCard(spell)
        val card = d.putCardInHand(d.player1, spell.name)
        d.submitSuccess(CastSpell(d.player1, card))
        repeat(10) { if (d.state.stack.isNotEmpty()) d.passPriority(d.state.priorityPlayerId!!) }
        d.state.stack shouldBe emptyList()
        val token = d.state.getBattlefield().single { d.state.getEntity(it)?.has<TokenBlueprintComponent>() == true }
        val blueprint = d.state.getEntity(token)!!.get<TokenBlueprintComponent>()!!.blueprint
        blueprint.power shouldBe 1
        blueprint.toughness shouldBe 2
        val view = ClientStateTransformer(d.cardRegistry, predicateEvaluator = d.services.predicateEvaluator).transform(d.state, d.player2)
        view.cards[token]!!.tokenBlueprint shouldBe blueprint
        view.cards[token]!!.cardDefinitionId.shouldNotBeNull()
        d.cardRegistry.definitionAddresses(spell.name).all { d.cardRegistry.getCard(it) == spell } shouldBe true
    }
})
