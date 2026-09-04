package com.wingedsheep.gym.trainer

import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.GameEnvironment
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeSameInstanceAs

class GameEnvironmentRegistryAuthorityTest : FunSpec({

    test("external adapters can retain the environment's registry authority across forks") {
        val registry = CardRegistry()
        val environment = GameEnvironment.create(registry)

        environment.cardRegistry shouldBeSameInstanceAs registry
        environment.fork().cardRegistry shouldBeSameInstanceAs registry
    }
})
