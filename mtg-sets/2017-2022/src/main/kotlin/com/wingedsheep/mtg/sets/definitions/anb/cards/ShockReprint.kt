package com.wingedsheep.mtg.sets.definitions.anb.cards

import com.wingedsheep.sdk.model.Printing
import com.wingedsheep.sdk.model.Rarity

/**
 * Shock reprint in Arena Beginner Set. The canonical CardDefinition lives in Stronghold (`sth`),
 * the card's earliest real printing; this file contributes only per-printing presentation data.
 */
val ShockReprint = Printing(
    oracleId = "a9d288b8-cdc1-4e55-a0c9-d6edfc95e65d",
    name = "Shock",
    setCode = "ANB",
    collectorNumber = "84",
    scryfallId = "d5c146ec-3006-4ac1-b2b6-fe0f9e879dc9",
    artist = "Jason Rainville",
    imageUri = "https://cards.scryfall.io/normal/front/d/5/d5c146ec-3006-4ac1-b2b6-fe0f9e879dc9.jpg?1783929799",
    releaseDate = "2020-08-13",
    rarity = Rarity.COMMON,
)
