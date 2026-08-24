package com.wingedsheep.gameserver.ai

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AiControllerModeTest : FunSpec({
    test("all supported modes parse to a closed domain") {
        AiControllerMode.parse("engine") shouldBe AiControllerMode.ENGINE
        AiControllerMode.parse("llm") shouldBe AiControllerMode.LLM
        AiControllerMode.parse("search-teacher") shouldBe AiControllerMode.SEARCH_TEACHER
    }

    test("unknown mode fails instead of falling through to llm") {
        shouldThrow<IllegalArgumentException> { AiControllerMode.parse("teacher-ish") }
    }
})
