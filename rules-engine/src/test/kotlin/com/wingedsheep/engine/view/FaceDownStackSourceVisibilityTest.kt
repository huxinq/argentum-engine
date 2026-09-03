package com.wingedsheep.engine.view

import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
import com.wingedsheep.engine.state.components.stack.ActivatedAbilityOnStackComponent
import com.wingedsheep.engine.state.components.stack.TriggeredAbilityOnStackComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AbilityId
import com.wingedsheep.sdk.scripting.AbilityIdentity
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * A stack ability has no CardComponent of its own. Its source's identity is still private when
 * that source is face down, so each public stack presentation must ask [Visibility] about the
 * source rather than treating the synthetic stack entity as a public card.
 */
class FaceDownStackSourceVisibilityTest : FunSpec({
    val secretSource = card("Secret Source") {
        manaCost = "{2}{R}"
        typeLine = "Creature — Goblin"
        power = 2
        toughness = 2
    }

    fun driver(): GameTestDriver = GameTestDriver().also { driver ->
        driver.registerCards(TestCards.all + secretSource)
        driver.initMirrorMatch(deck = Deck.of("Mountain" to 40))
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
    }

    /** A deliberately art-bearing face-down source makes every identity-bearing field observable. */
    fun hiddenSource(driver: GameTestDriver): Pair<EntityId, EntityId> {
        val controller = driver.activePlayer!!
        val source = driver.putCreatureOnBattlefield(controller, secretSource.name)
        val card = driver.state.getEntity(source)!!.require<CardComponent>()
        driver.replaceState(driver.state.updateEntity(source) {
            it.with(card.copy(imageUri = "https://private.example/secret-source.jpg"))
                .with(FaceDownComponent)
        })
        return source to controller
    }

    fun opponentOf(driver: GameTestDriver, controller: EntityId): EntityId =
        if (controller == driver.player1) driver.player2 else driver.player1

    test("a face-down activated source exposes neither identity, colours, art, nor ability identity") {
        val driver = driver()
        val (source, controller) = hiddenSource(driver)
        val stackId = EntityId.of("face-down-activated-source")
        driver.replaceState(driver.state
            .withEntity(
                stackId,
                ComponentContainer.of(
                    ActivatedAbilityOnStackComponent(
                        sourceId = source,
                        sourceName = secretSource.name,
                        controllerId = controller,
                        effect = Effects.DrawCards(1),
                        abilityIdentity = AbilityIdentity(secretSource.name, AbilityId("secret_activated")),
                    ),
                ),
            )
            .copy(stack = listOf(stackId)))

        val opponentView = ClientStateTransformer(driver.cardRegistry)
            .transform(driver.state, opponentOf(driver, controller))
            .cards.getValue(stackId)
        opponentView.name shouldBe "Face-down creature ability"
        opponentView.colors.shouldBeEmpty()
        opponentView.imageUri.shouldBeNull()
        opponentView.abilityIdentity.shouldBeNull()

        val controllerView = ClientStateTransformer(driver.cardRegistry)
            .transform(driver.state, controller)
            .cards.getValue(stackId)
        controllerView.name shouldBe "Secret Source ability"
        controllerView.colors shouldBe secretSource.colors
        controllerView.imageUri shouldBe "https://private.example/secret-source.jpg"
        controllerView.abilityIdentity!!.cardDefinitionId shouldBe secretSource.name
    }

    test("a face-down triggered source has the same source-identity boundary") {
        val driver = driver()
        val (source, controller) = hiddenSource(driver)
        val stackId = EntityId.of("face-down-triggered-source")
        driver.replaceState(driver.state
            .withEntity(
                stackId,
                ComponentContainer.of(
                    TriggeredAbilityOnStackComponent(
                        sourceId = source,
                        sourceName = secretSource.name,
                        controllerId = controller,
                        effect = Effects.DrawCards(1),
                        description = "Draw a card.",
                        abilityIdentity = AbilityIdentity(secretSource.name, AbilityId("secret_triggered")),
                    ),
                ),
            )
            .copy(stack = listOf(stackId)))

        val opponentView = ClientStateTransformer(driver.cardRegistry)
            .transform(driver.state, opponentOf(driver, controller))
            .cards.getValue(stackId)
        opponentView.name shouldBe "Face-down creature trigger"
        opponentView.colors.shouldBeEmpty()
        opponentView.imageUri.shouldBeNull()
        opponentView.abilityIdentity.shouldBeNull()

        val controllerView = ClientStateTransformer(driver.cardRegistry)
            .transform(driver.state, controller)
            .cards.getValue(stackId)
        controllerView.name shouldBe "Secret Source trigger"
        controllerView.colors shouldBe secretSource.colors
        controllerView.imageUri shouldBe "https://private.example/secret-source.jpg"
        controllerView.abilityIdentity!!.cardDefinitionId shouldBe secretSource.name
    }

    test("a stack ability follows the source's current visibility, not an ad-hoc creation snapshot") {
        val driver = driver()
        val controller = driver.activePlayer!!
        val source = driver.putCreatureOnBattlefield(controller, secretSource.name)
        val stackId = EntityId.of("source-turned-down-in-response")
        val stateWithAbility = driver.state
            .withEntity(
                stackId,
                ComponentContainer.of(
                    ActivatedAbilityOnStackComponent(
                        sourceId = source,
                        sourceName = secretSource.name,
                        controllerId = controller,
                        effect = Effects.DrawCards(1),
                    ),
                ),
            )
            .copy(stack = listOf(stackId))

        val transformer = ClientStateTransformer(driver.cardRegistry)
        transformer.transform(stateWithAbility, opponentOf(driver, controller))
            .cards.getValue(stackId).name shouldBe "Secret Source ability"

        val sourceTurnedDown = stateWithAbility.updateEntity(source) { it.with(FaceDownComponent) }
        transformer.transform(sourceTurnedDown, opponentOf(driver, controller))
            .cards.getValue(stackId).name shouldBe "Face-down creature ability"

        val sourceTurnedUp = sourceTurnedDown.updateEntity(source) { it.without<FaceDownComponent>() }
        transformer.transform(sourceTurnedUp, opponentOf(driver, controller))
            .cards.getValue(stackId).name shouldBe "Secret Source ability"
    }
})
