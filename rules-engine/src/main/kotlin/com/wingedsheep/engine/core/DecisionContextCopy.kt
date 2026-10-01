package com.wingedsheep.engine.core

/** Preserve a question while attaching its captured public rule context. */
fun PendingDecision.withContext(context: DecisionContext): PendingDecision = when (this) {
    is ChooseTargetsDecision -> copy(context = context)
    is SelectCardsDecision -> copy(context = context)
    is YesNoDecision -> copy(context = context)
    is BatchYesNoDecision -> copy(context = context)
    is ChooseModeDecision -> copy(context = context)
    is ChooseColorDecision -> copy(context = context)
    is ChooseNumberDecision -> copy(context = context)
    is DistributeDecision -> copy(context = context)
    is OrderObjectsDecision -> copy(context = context)
    is SplitPilesDecision -> copy(context = context)
    is ChooseOptionDecision -> copy(context = context)
    is ChooseReplacementDecision -> copy(context = context)
    is AssignDamageDecision -> copy(context = context)
    is SearchLibraryDecision -> copy(context = context)
    is ReorderLibraryDecision -> copy(context = context)
    is SelectManaSourcesDecision -> copy(context = context)
    is BudgetModalDecision -> copy(context = context)
    is CombatResolutionDecision -> copy(context = context)
}
