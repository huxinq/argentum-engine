package com.wingedsheep.sdk.core

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class TypeLineTest : DescribeSpec({
    describe("TypeLine.parse") {
        it("preserves hyphenated subtypes across string serialization") {
            val source = TypeLine(
                cardTypes = setOf(CardType.LAND, CardType.CREATURE),
                subtypes = setOf(Subtype("Assembly-Worker"), Subtype("Lizard")),
            )

            TypeLine.parse(source.toString()) shouldBe source
        }

        it("accepts the legacy space-delimited ASCII separator") {
            TypeLine.parse("Creature - Assembly-Worker") shouldBe TypeLine.creature(
                setOf(Subtype("Assembly-Worker"))
            )
        }

        it("accepts Unicode separators without surrounding spaces") {
            TypeLine.parse("Creature—Human") shouldBe TypeLine.creature(setOf(Subtype("Human")))
            TypeLine.parse("Creature–Wizard") shouldBe TypeLine.creature(setOf(Subtype("Wizard")))
        }
    }
})
