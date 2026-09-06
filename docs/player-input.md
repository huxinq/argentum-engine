# Player input and live response ownership

The engine runs synchronously until it reaches a player decision, a terminal state, or an error.
It stores the data needed to resume; no coroutine or closure is retained in `GameState`.

## Engine suspension

A question producer calls `GameState.suspendForDecision` with a question factory and an
`AnswerContinuation` payload. The operation allocates one deterministic routing ID, creates a
`Suspension(question, answer)`, installs it on the continuation stack, and emits a
`DecisionRequestedEvent`. `GameState.pendingDecision` reads the question from the top suspension.

Surrounding execution uses `ExecutionResult.propagatePause` to carry that existing suspension.
It does not allocate another ID or emit another request. Automatic work beneath a question uses
`AutomaticContinuation` frames, which have no response address. The stack determines their order.
A mana ability inside a payment window sets aside the whole suspension, then restores it with the
same ID and refreshed source menu after the nested execution finishes.

`SubmitDecisionHandler` validates the acting player and choice payload against the pending
question. `ContinuationHandler` verifies the response ID, pops the suspension, and dispatches its
answer data. The paired question is available to resumers that need its original shape, including
combat assignment. Legal options and their validation remain engine responsibilities.

## Live transports

The browser receives masked state and server-computed options. AI updates also retain the live
origin from their particular snapshot, including through asynchronous thinking and approval.
Both adapters submit a canonical engine action with that origin through `LiveActionSubmission`.
`GameSession.executeLiveAction` checks freshness and executes under one session lock.

Engine question IDs reproduce when the same saved execution is replayed. Live undo independently
rotates the session's interaction epoch, invalidating deliveries from the abandoned branch even
when the restored counter produces the same question ID. Browser decisions encode the epoch in
the opaque question token; ordinary browser actions send it in their envelope. AI keeps raw engine
IDs for simulations and sends the epoch separately. Neither adapter supplies a newer epoch at
response time. See [data contracts](data-contracts.md) for transport compatibility and recovery.

## Saved states

Snapshots serialize question and answer together. The previous independent pending-question
and answer-frame format is unsupported and fails explicitly, including with the server's
permissive persistence decoder. Deployments that must resume already-saved paused games need
the companion legacy-reader change. The choice is whether to merge and deploy that companion;
there is no runtime feature flag. See
[architecture principles](architecture-principles.md#24-reentrant-continuations) for the boundary.
