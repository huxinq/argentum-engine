package com.wingedsheep.mtg.sets.definitions.otj.cards

import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.KeywordAbility
import com.wingedsheep.sdk.scripting.targets.EffectTarget
/**
 * Slickshot Show-Off
 * {1}{R}
 * Creature — Bird Wizard
 * 1/2
 * Flying, haste
 * Whenever you cast a noncreature spell, this creature gets +2/+0 until end of turn.
 * Plot {1}{R} (You may pay {1}{R} and exile this card from your hand. Cast it as a sorcery on a later turn without paying its mana cost. Plot only as a sorcery.)
 */
val SlickshotShowOff = card("Slickshot Show-Off") {
    manaCost = "{1}{R}"
    colorIdentity = "R"
    typeLine = "Creature — Bird Wizard"
    oracleText = "Flying, haste\nWhenever you cast a noncreature spell, this creature gets +2/+0 until end of turn.\nPlot {1}{R} (You may pay {1}{R} and exile this card from your hand. Cast it as a sorcery on a later turn without paying its mana cost. Plot only as a sorcery.)"
    power = 1
    toughness = 2
    keywords(Keyword.FLYING, Keyword.HASTE)
    triggeredAbility {
        trigger = Triggers.YouCastNoncreature
        effect = Effects.ModifyStats(2, 0, EffectTarget.Self)
    }
    keywordAbility(KeywordAbility.plot("{1}{R}"))

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "146"
        artist = "Augusto Quirino"
        imageUri = "https://cards.scryfall.io/normal/front/7/0/7054012b-4f9d-44a0-aaf9-7fd3bddc7b2d.jpg"
    }
}
