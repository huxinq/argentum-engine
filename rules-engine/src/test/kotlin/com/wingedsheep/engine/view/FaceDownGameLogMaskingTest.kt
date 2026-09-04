package com.wingedsheep.engine.view

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.event.GrantedActivatedAbility
import com.wingedsheep.engine.handlers.effects.FaceDownTurnUp
import com.wingedsheep.engine.state.components.battlefield.PhasedOutComponent
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
import com.wingedsheep.engine.state.components.identity.FaceDownModeComponent
import com.wingedsheep.engine.state.components.stack.ActivatedAbilityOnStackComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.state.nameVisibleToAll
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AbilityCost
import com.wingedsheep.sdk.scripting.AbilityId
import com.wingedsheep.sdk.scripting.ActivatedAbility
import com.wingedsheep.sdk.scripting.Duration
import com.wingedsheep.sdk.scripting.effects.FaceDownMode
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import com.wingedsheep.sdk.scripting.targets.TargetCreature
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * The game log must never print the name of a card that is face down on the battlefield.
 *
 * CR 708.2a: a face-down permanent has no name. The cast line was masked from the start, but the
 * three lines that follow it were not, so a disguised creature announced itself the moment it
 * resolved:
 *
 * ```
 * Opponent cast Face-down creature
 * Aurelia's Vindicator resolved                                      <- leak
 * Opponent's Aurelia's Vindicator entered the battlefield            <- leak
 * You cast Igneous Inspiration targeting Aurelia's Vindicator        <- leak
 * Opponent's Aurelia's Vindicator triggered: ... unless ... pays {2} <- leak
 * ```
 *
 * [ClientEventTransformer] cannot fix this downstream — it maps engine events to log text with no
 * `GameState` in hand, so it cannot tell a face-down permanent from a face-up one. Every one of
 * these is therefore masked where the event is emitted, in `StackResolver`.
 *
 * The mask applies to *both* players' logs, as the cast line already did: the object genuinely has
 * no name, and a controller who wants to know what their own face-down permanent is looks at the
 * card (CR 708.5) — [ClientStateTransformer] keeps the real name in their card view, which is what
 * [FaceDownHelperCardVisibilityTest] covers.
 */
class FaceDownGameLogMaskingTest : FunSpec({

    val disguisedAngel = card("Disguised Angel") {
        manaCost = "{2}{W}{W}"
        typeLine = "Creature — Angel"
        power = 4
        toughness = 2
        disguise = "{3}{W}"
    }

    // A targeted *trigger* is the reachable path to a ChooseTargetsDecision: a non-modal spell
    // declares its targets at cast time and never prompts.
    val tapper = card("Test Tapper") {
        manaCost = "{2}{B}"
        typeLine = "Creature — Human"
        power = 1
        toughness = 1
        triggeredAbility {
            trigger = Triggers.EntersBattlefield
            target("target creature", TargetCreature())
            effect = Effects.Tap(EffectTarget.ContextTarget(0))
            description = "When this creature enters, tap target creature."
        }
    }

    fun driver(): GameTestDriver {
        val d = GameTestDriver()
        d.registerCards(TestCards.all + listOf(disguisedAngel, tapper))
        d.initMirrorMatch(deck = Deck.of("Swamp" to 40))
        d.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return d
    }

    /** Every log line this game has produced, as [viewingPlayerId] would read it. */
    fun GameTestDriver.logAsSeenBy(viewingPlayerId: EntityId): List<String> =
        ClientEventTransformer.transform(events, viewingPlayerId).map { it.description }

    /** Put [cardName] onto the battlefield face down under [mode], as a real face-down entry does. */
    fun GameTestDriver.putFaceDown(playerId: EntityId, cardName: String, mode: FaceDownMode): EntityId {
        val id = putCreatureOnBattlefield(playerId, cardName)
        val cardDef = cardRegistry.requireCard(cardName)
        replaceState(
            state.updateEntity(id) { container ->
                var c = container.with(FaceDownComponent).with(FaceDownModeComponent(mode))
                FaceDownTurnUp.dataFor(cardDef, cardName, mode)?.let { c = c.with(it) }
                c
            }
        )
        return id
    }

    test("a creature cast face down is not named as it resolves or as it enters") {
        val d = driver()
        val caster = d.activePlayer!!
        val opponent = d.getOpponent(caster)

        val cardId = d.putCardInHand(caster, "Disguised Angel")
        d.giveMana(caster, Color.BLACK, 3)
        d.submitSuccess(
            CastSpell(
                playerId = caster,
                cardId = cardId,
                castFaceDown = true,
                paymentStrategy = PaymentStrategy.FromPool,
            )
        )
        d.bothPass()

        val opponentLog = d.logAsSeenBy(opponent)
        withClue("opponent's log: $opponentLog") {
            opponentLog.forEach { it shouldNotContain "Disguised Angel" }
            opponentLog.contains("Face-down creature resolved") shouldBe true
            opponentLog.contains("Opponent's Face-down creature entered the battlefield") shouldBe true
        }

        val casterLog = d.logAsSeenBy(caster)
        withClue("caster's own log: $casterLog") {
            casterLog.forEach { it shouldNotContain "Disguised Angel" }
            casterLog.contains("Your Face-down creature entered the battlefield") shouldBe true
        }
    }

    test("the ward a disguised permanent triggers is not attributed to the hidden card") {
        val d = driver()
        val activePlayer = d.activePlayer!!
        val opponent = d.getOpponent(activePlayer)

        // The face-down permanent's ward {2} comes from disguise (CR 702.168a), not from any
        // printed ability — a face-down permanent has none (CR 708.2).
        val hidden = d.putFaceDown(opponent, "Disguised Angel", FaceDownMode.DISGUISE)

        // Exactly {R} for Bolt and nothing left for the ward, so it counters without a prompt.
        d.giveMana(activePlayer, Color.RED, 1)
        val bolt = d.putCardInHand(activePlayer, "Lightning Bolt")
        d.castSpellWithTargets(
            activePlayer, bolt, listOf(ChosenTarget.Permanent(hidden))
        ).isSuccess shouldBe true

        d.bothPass()

        val log = d.logAsSeenBy(activePlayer)
        withClue("targeting player's log: $log") {
            log.forEach { it shouldNotContain "Disguised Angel" }
            // The spell that targeted it must not name it either — this is the line that gave the
            // Vindicator away in the transcript this test was written from.
            log.contains("You cast Lightning Bolt targeting Face-down creature") shouldBe true
            log.any { it.startsWith("Opponent's Face-down creature triggered:") } shouldBe true
        }
    }

    test("a spell that answers a face-down spell does not name it either") {
        val d = driver()
        val caster = d.activePlayer!!
        val opponent = d.getOpponent(caster)

        val cardId = d.putCardInHand(caster, "Disguised Angel")
        d.giveMana(caster, Color.BLACK, 3)
        d.submitSuccess(
            CastSpell(
                playerId = caster,
                cardId = cardId,
                castFaceDown = true,
                paymentStrategy = PaymentStrategy.FromPool,
            )
        )

        // A face-down *spell* is nameless too (CR 708.4), so the counter that answers it on the
        // stack must not name it — the leak is the same one, a zone earlier.
        d.passPriority(caster)
        d.giveMana(opponent, Color.BLUE, 2)
        val counter = d.putCardInHand(opponent, "Counterspell")
        d.castSpellWithTargets(
            opponent, counter, listOf(ChosenTarget.Spell(cardId))
        ).isSuccess shouldBe true

        val log = d.logAsSeenBy(caster)
        withClue("log: $log") {
            log.forEach { it shouldNotContain "Disguised Angel" }
            log.contains("Opponent cast Counterspell targeting Face-down creature") shouldBe true
        }
    }

    context("which zone the mask applies in") {

        test("battlefield and stack are masked; a face-down card in hand is not") {
            val d = driver()
            val player = d.activePlayer!!

            // A card in hand is hidden by the zone itself; calling it "Face-down creature" would
            // be a different claim, not a safer one.
            val inHand = d.putCardInHand(player, "Disguised Angel")
            d.replaceState(d.state.updateEntity(inHand) { it.with(FaceDownComponent) })
            nameVisibleToAll(d.state, inHand, "Disguised Angel") shouldBe "Disguised Angel"

            val onBattlefield = d.putFaceDown(player, "Disguised Angel", FaceDownMode.DISGUISE)
            nameVisibleToAll(d.state, onBattlefield, "Disguised Angel") shouldBe "Face-down creature"
        }

        test("a phased-out face-down permanent is still masked") {
            val d = driver()
            val player = d.activePlayer!!
            val hidden = d.putFaceDown(player, "Disguised Angel", FaceDownMode.DISGUISE)

            // GameState.getBattlefield() deliberately hides phased-out permanents, so a naive
            // battlefield check unmasks them. Phasing never changes zones (CR 702.26d) — the
            // permanent is still on the battlefield, and its identity is still hidden.
            d.replaceState(
                d.state.updateEntity(hidden) { it.with(PhasedOutComponent(phasedOutByController = player)) }
            )
            d.state.getBattlefield() shouldNotContain hidden
            nameVisibleToAll(d.state, hidden, "Disguised Angel") shouldBe "Face-down creature"
        }

        test("a card face down in exile reads as a card, not a creature") {
            val d = driver()
            val player = d.activePlayer!!

            val exiled = d.putCardInExile(player, "Disguised Angel")
            d.replaceState(d.state.updateEntity(exiled) { it.with(FaceDownComponent) })

            // Not "Face-down creature" — an exiled face-down card has no characteristics to show
            // and is not a creature.
            nameVisibleToAll(d.state, exiled, "Disguised Angel") shouldBe "Face-down card"
        }
    }

    test("a face-down permanent's controller may still read its name where the text has an audience") {
        val d = driver()
        val controller = d.activePlayer!!
        val opponent = d.getOpponent(controller)
        val hidden = d.putFaceDown(controller, "Disguised Angel", FaceDownMode.DISGUISE)
        val visibility = Visibility(d.cardRegistry)

        // CR 708.5 — you may look at a face-down permanent you control. Text with an audience asks
        // the shared identity authority and picks its label from that answer; the flat game-log
        // strings have no audience to ask about and mask for everyone.
        visibility.isCardIdentityVisibleTo(d.state, Zone.BATTLEFIELD, hidden, controller) shouldBe true
        visibility.isCardIdentityVisibleTo(d.state, Zone.BATTLEFIELD, hidden, opponent) shouldBe false
        withClue("a spectator is never the player who may look") {
            visibility.isCardIdentityVisibleTo(
                d.state,
                Zone.BATTLEFIELD,
                hidden,
                controller,
                isSpectator = true,
            ) shouldBe false
        }
        withClue("the flat game-log string masks for everyone, controller included") {
            nameVisibleToAll(d.state, hidden, "Disguised Angel") shouldBe "Face-down creature"
        }
    }

    test("a decision summary does not name the face-down permanent it targeted") {
        val d = driver()
        val chooser = d.activePlayer!!
        val opponent = d.getOpponent(chooser)

        val hidden = d.putFaceDown(opponent, "Disguised Angel", FaceDownMode.DISGUISE)

        // A second legal target, so the choice is a genuine prompt rather than an auto-select.
        d.putCreatureOnBattlefield(chooser, "Grizzly Bears")

        d.giveMana(chooser, Color.BLACK, 3)
        val tapperCard = d.putCardInHand(chooser, "Test Tapper")
        d.submitSuccess(
            CastSpell(
                playerId = chooser,
                cardId = tapperCard,
                paymentStrategy = PaymentStrategy.FromPool,
            )
        )
        d.bothPass()

        d.pendingDecision.shouldBeInstanceOf<ChooseTargetsDecision>()
        d.submitTargetSelection(chooser, listOf(hidden))

        val log = d.logAsSeenBy(chooser)
        withClue("chooser's log: $log") {
            log.forEach { it shouldNotContain "Disguised Angel" }
            log.contains("You: (Test Tapper) Targeting Face-down creature") shouldBe true
        }
    }

    test("a granted activation from a face-down permanent uses its public name in the log") {
        val d = driver()
        val controller = d.activePlayer!!
        val opponent = d.getOpponent(controller)
        val hidden = d.putFaceDown(controller, "Disguised Angel", FaceDownMode.DISGUISE)
        val grantedAbility = ActivatedAbility(
            id = AbilityId("test_face_down_granted_activation"),
            cost = AbilityCost.Free,
            effect = Effects.GainLife(1),
            descriptionOverride = "Gain 1 life.",
        )
        d.replaceState(
            d.state.copy(
                grantedActivatedAbilities = d.state.grantedActivatedAbilities +
                    GrantedActivatedAbility(
                        entityId = hidden,
                        ability = grantedAbility,
                        duration = Duration.Permanent,
                    ),
            ),
        )

        d.submitSuccess(ActivateAbility(controller, hidden, grantedAbility.id))

        val stackAbility = d.state.getEntity(d.state.stack.single())
            ?.require<ActivatedAbilityOnStackComponent>()
        withClue("resolution keeps the private source provenance") {
            stackAbility?.sourceName shouldBe "Disguised Angel"
            stackAbility?.sourceId shouldBe hidden
        }
        withClue("controller's log: ${d.logAsSeenBy(controller)}") {
            d.logAsSeenBy(controller).forEach { it shouldNotContain "Disguised Angel" }
            d.logAsSeenBy(controller).contains("You activated Face-down creature") shouldBe true
        }
        withClue("opponent's log: ${d.logAsSeenBy(opponent)}") {
            d.logAsSeenBy(opponent).forEach { it shouldNotContain "Disguised Angel" }
            d.logAsSeenBy(opponent).contains("Opponent activated Face-down creature") shouldBe true
        }
    }

    test("a face-up permanent is still named normally") {
        val d = driver()
        val caster = d.activePlayer!!
        val opponent = d.getOpponent(caster)

        val cardId = d.putCardInHand(caster, "Disguised Angel")
        d.giveMana(caster, Color.WHITE, 4)
        d.submitSuccess(
            CastSpell(
                playerId = caster,
                cardId = cardId,
                paymentStrategy = PaymentStrategy.FromPool,
            )
        )
        d.bothPass()

        val opponentLog = d.logAsSeenBy(opponent)
        withClue("opponent's log: $opponentLog") {
            opponentLog.contains("Disguised Angel resolved") shouldBe true
            opponentLog.contains("Opponent's Disguised Angel entered the battlefield") shouldBe true
        }
    }
})
