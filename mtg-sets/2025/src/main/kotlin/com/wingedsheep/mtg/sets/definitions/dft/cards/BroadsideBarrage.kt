package com.wingedsheep.mtg.sets.definitions.dft.cards

import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Patterns
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.effects.DealDamageEffect
import com.wingedsheep.sdk.scripting.effects.DrawCardsEffect
import com.wingedsheep.sdk.scripting.targets.TargetCreatureOrPlaneswalker
/**
 * Broadside Barrage
 * {1}{U}{R}
 * Instant
 * Broadside Barrage deals 5 damage to target creature or planeswalker. Draw a card, then discard a card.
 */
val BroadsideBarrage = card("Broadside Barrage") {
    manaCost = "{1}{U}{R}"
    colorIdentity = "UR"
    typeLine = "Instant"
    oracleText = "Broadside Barrage deals 5 damage to target creature or planeswalker. Draw a card, then discard a card."
    spell {
        val target = target("target", TargetCreatureOrPlaneswalker())
        effect = Effects.Composite(
            DealDamageEffect(5, target),
            DrawCardsEffect(1),
            Patterns.Hand.discardCards(1)
        )
    }
    metadata {
        rarity = Rarity.UNCOMMON
        collectorNumber = "192"
        artist = "Javier Charro"
        flavorText = "\"Let's show 'em how the Keelhaulers say hello!\""
        imageUri = "https://cards.scryfall.io/normal/front/d/0/d086e4e5-98fa-437e-85aa-d8849c94ce94.jpg"
    }
}
