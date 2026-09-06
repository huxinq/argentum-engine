These synthetic fixtures were exported by executing the unchanged engine at
`fba4b704cb213843a1420809e0cf6aee656045c9`, before changing suspension storage.
They were not constructed by reversing the new representation.

Each directory contains the original paused `state.json`, the actual subsequent
`actions.json`, the resulting `after-N.json` states and `events-N.json` events,
the captured step's `creation-events.json`, and a verified `manifest.json`.
The exporter decoded the saved initial state before applying the actions and
asserted each scenario's final observable outcome. The original four exports passed with `encodeDefaults = true`.

The examples cover nested may decisions and an automatic outer tail, a repeat
loop awaiting its continue answer, an outstanding mana-payment question hidden
under a real Birds of Paradise color choice, and a free Naturalize awaiting
targets. All entities and gameplay state come from public-safe synthetic setups.

Migration tests preserve the saved counter exactly on load. Later transitions
may allocate fewer routing IDs because automatic work no longer allocates IDs;
tests explicitly rebind subsequent replies to the current question when needed.

`compact-cycling` was separately exported and reloaded at the same parent revision
with `encodeDefaults = false`. It cycles Secluded Steppe with Astral Slide and
Grizzly Bears on the battlefield. The paused stack contains an automatic cycling
draw whose default `decisionId = "cycle-draw"` is absent from the encoded JSON.
Declining the trigger resumes that draw; the parent exporter verifies one card in
hand, one remaining in the library, and a drained continuation stack. The migration
regression compares the complete resumed state and event list with this capture.
