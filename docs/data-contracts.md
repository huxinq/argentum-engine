# Data Interfaces & Contracts

This document defines the JSON payloads exchanged between `web-client` and `game-server`.

## 1. Core Philosophy

* **Server -> Client:** The Server pushes the **Truth**. The Client renders it.
* **Client -> Server:** The Client pushes **Intent**. The Server validates it.

## 2. Gameplay Payload (WebSocket)

### A. State Update (Server -> Client)

Sent whenever the game state changes.

```json
{
  "type": "stateUpdate",
  // 1. The Visual State (Masked)
  "state": {
    "activePlayerId": "player-1",
    "phase": "MAIN_1",
    "zones": [
      {
        "name": "BATTLEFIELD",
        "cards": [
          {
            "id": "ent-1",
            "name": "Grizzly Bears",
            "tapped": true,
            "pt": "2/2"
          }
        ]
      },
      {
        "name": "HAND",
        "ownerId": "player-1",
        "cards": [
          {
            "id": "ent-2",
            "name": "Generous Gift"
          },
          // Visible to owner
          {
            "id": "ent-3",
            "name": "???"
          }
          // Masked to opponent
        ]
      }
    ]
  },
  // 2. The Animation Stream (What just happened?)
  "events": [
    {
      "type": "Tapped",
      "entityId": "ent-1"
    },
    {
      "type": "DamageDealt",
      "targetId": "player-2",
      "amount": 2
    }
  ],
  // 3. The Legal Actions (What can I do now?)
  // The ENGINE calculates this. The Client just renders it.
  "legalActions": [
    {
      "actionId": "act-1",
      "type": "PlayLand",
      "description": "Play Forest",
      "sourceId": "ent-5"
    },
    {
      "actionId": "act-2",
      "type": "CastSpell",
      "description": "Cast Shock",
      "sourceId": "ent-6",
      // If targeting is needed, the engine provides the Valid Candidates
      "targeting": {
        "required": true,
        "validTargets": [
          "ent-1",
          "player-2",
          "player-1"
        ]
      }
    },
    {
      "actionId": "act-3",
      "type": "PassPriority",
      "description": "Pass Turn"
    }
  ]
}
```

### B. Action Submission (Client -> Server)

Sent when the user interacts with the UI.

**Simple Action (No targets):**

```json
{
  "type": "submitAction",
  "action": {
    "type": "PlayLand",
    "cardId": "ent-5"
  }
}
```

**Complex Action (With targets):**

```json
{
  "type": "submitAction",
  "action": {
    "type": "CastSpell",
    "cardId": "ent-6",
    "targets": [
      {
        "id": "ent-1",
        "type": "Creature"
      }
    ]
  }
}
```

### B2. Persistent Yields (Client -> Server)

MTGO-style per-ability yields (backlog §C). Keyed by the ability's **AbilityIdentity**
(`cardDefinitionId` + `abilityId`), so a preference set once follows every current and future
copy/instance of that card ability. The server applies the change to the immutable `GameState`
(`yieldsByPlayer`), so it survives serialization and replays deterministically.

```json
{ "type": "setAbilityYield", "cardDefinitionId": "Soul Warden#ALA-25", "abilityId": "ability_42",
  "kind": "ALWAYS_ANSWER_YES" }
```

`kind` ∈ `YIELD_UNTIL_END_OF_TURN` (auto-pass priority on this ability's stack objects until end of
turn), `YIELD_WHOLE_GAME` (same, rest of game), `ALWAYS_ANSWER_YES` / `ALWAYS_ANSWER_NO`
(auto-resolve the ability's optional "you may" may-question). Revoke with
`{ "type": "clearAbilityYield", "cardDefinitionId": …, "abilityId": … }` or clear everything with
`{ "type": "clearAllYields" }`.

The viewer's own yields come back in the state update as `activeYields` (masked — a player never sees
another player's yields), each `{ cardDefinitionId, abilityId, displayName, untilEndOfTurn,
wholeGame, autoAnswer }`. A triggered/activated ability on the stack carries its `abilityIdentity`
in its `ClientCard`, so the stack-item context menu can target it. When a yield auto-answers a
may-question, the server emits an `abilityAutoAnswered` log event (shown only to the controller).

### B3. Deck Tracker (`deck` on the client state)

The state update carries the viewer's own decklist as `deck` — one entry per distinct card,
`{ cardName, copies, remaining, cmc, cardTypes, colors, imageUri }`. It drives the in-game deck
panel behind the Deck pile (also `D`), which renders it through the same `DeckCardBody` component
as the recorded-deck viewer — hence the field names matching `GameDeckCard`.

The server builds it in `ClientStateTransformer` from live *ownership* rather than a stored
decklist, which is what keeps it honest: a permanent an opponent stole is still in its owner's deck
(CR 108.3), a token copy of one never is, and a permanent copying something else counts as the card
it was printed as. The sideboard is excluded as outside the game (CR 400.11a); the command zone is
included so a commander doesn't flicker in and out as it's cast and returns.

Two masking rules matter:

- **`deck` describes only `viewingPlayerId`, and is empty for spectators.** No player, spectator or
  replay viewer ever receives another player's decklist.
- **`remaining` is "copies you can't currently see", not "copies in your library."** Those are the
  same number in an ordinary game, but a card of yours hidden elsewhere — exiled face down, or a
  face-down permanent an opponent controls — stays counted as `remaining`. Publishing an exact
  library count would let the panel be read backwards to learn *which* card got exiled face down.

Aggregate counts only: library *order* is never exposed here. (The Library-order tab in the same
panel is the pre-existing view, and shows card backs for everything not revealed to the viewer.)

`StateDelta.deck` is sent only when a count actually moved (a draw, a mill, a tutor), so the
many updates that just shuffle the battlefield around don't re-send the list. Absent from a delta
means unchanged — the client carries the previous value forward.

### C. Connection Liveness (Client <-> Server)

`{"type": "ping"}` (client) is always answered with `{"type": "pong"}` (server), regardless of
authentication or game state. The client sends it when a backgrounded tab becomes visible while
the socket still claims to be open: a socket can sit half-open after OS sleep without ever firing
`close`, and a silent server (no message within 5s) tells the client to tear the socket down and
reconnect. Any inbound message counts as proof of life, not just the pong.

Related recovery contracts:

- `{"type": "requestResync"}` (client) asks for a full `stateUpdate` instead of deltas — sent on
  tab return and when a `stateVersion` gap is detected.
- A `NOT_CONNECTED` error (server) means the socket is open but not associated with an
  authenticated session (e.g. the server restarted). The client recovers by re-sending `connect`
  with its stored token rather than surfacing the error.
- `{"type": "sessionReplaced"}` (server) is sent to the *previous* socket when the same identity
  (token) authenticates from a new socket — i.e. the player opened the game in another tab or
  device. The server closes that socket right after sending; the receiving client stops all
  auto-reconnect (reconnecting would steal the session straight back) and shows a takeover
  overlay whose "Use here" button reclaims the session explicitly.

---

## 3. Drafting Payload (REST / HTTP)

Drafting is lower frequency, so standard HTTP JSON is used.

**Request: Pick a Card**
`POST /api/draft/pick`

```json
{
  "draftSessionId": "sess_draft_0912",
  "packId": "pack_88",
  "cardId": "uuid-shivan-dragon"
}
```

**Response: Updated State**

```json
{
  "status": "PickRecorded",
  "waitingForOthers": true,
  // Or, if the next pack is ready:
  "nextPack": {
    "packId": "pack_89",
    "cards": [
      ...
    ]
  }
}

## 3a. Set Catalog & Coverage (REST / HTTP)

Set-level metadata for the deckbuilder, pickers, and the **Set Completion** view. Low frequency,
plain HTTP JSON.

**List sets** — `GET /api/sets` → `[{ "code", "name", "releaseDate" }]` (every catalogued set).
**Booster-ready** — `GET /api/sets/booster-ready` → `[{ "setCode", "setName", "implementedCount", "incomplete" }]`
(subset draftable for sealed/draft).

**Set coverage** — `GET /api/sets/coverage` → per-set card-implementation coverage, newest release
first. Powers the Set Completion grid (`/set-completion`). The headline `percent` is over the
**booster (draft)** cards only — a set reads 100% once every boosterable card is implemented; the
completionist extras are reported separately.

```json
[
  { "code": "BLB", "name": "Bloomburrow", "releaseDate": "2024-08-02", "setType": "expansion",
    "block": null, "implemented": 261, "total": 261, "extraImplemented": 18, "extraTotal": 18,
    "notPlanned": 0, "extraNotPlanned": 0, "percent": 100.0, "inStandard": true, "assayReady": 0 }
]
```

**Set detail** — `GET /api/sets/{code}/coverage` → one set's full canonical card list: the `draft`
pool plus the extras split into `extraGroups`, each card marked. 404 if the code isn't a catalogued
set with baked totals. Drives the click-through detail view.

```json
{ "code": "ELD", "name": "Throne of Eldraine", "releaseDate": "2019-10-04", "block": null,
  "implemented": 254, "total": 254, "extraImplemented": 0, "extraTotal": 31,
  "notPlanned": 0, "extraNotPlanned": 0, "percent": 100.0, "assayReady": 0,
  "draft": [{ "name": "Acclaimed Contender", "implemented": true,
              "imageUri": "https://cards.scryfall.io/normal/front/…jpg", "notPlanned": null,
              "assay": { "readsWhole": false, "kind": "LINE_DECLINED",
                         "line": "When Acclaimed Contender enters, if you control…" } }, ...],
  "extraGroups": [
    { "label": "Planeswalker Decks", "implemented": 0, "total": 10, "notPlanned": 0,
      "cards": [{ "name": "...", "implemented": false, "imageUri": "…", "notPlanned": null,
                  "assay": { "readsWhole": true, "kind": null, "line": null } }, ...] },
    { "label": "Brawl Decks", "implemented": 0, "total": 20, "notPlanned": 0, "cards": [...] },
    { "label": "Promos", "implemented": 0, "total": 1, "notPlanned": 0, "cards": [...] }] }
```

**Assay verdicts.** Every card carries `assay` — Argentum Assay's reading of it — and every set
carries `assayReady`, the count of booster cards *still to build* that Assay reads whole. That's the
free-to-implement number: such a card needs no new grammar and no new SDK vocabulary, so the view
badges it, filters to it, and can sort the whole grid by it. `null` means **unknown**, never "no":
either the ledger has no row for that card, or none is baked. The view distinguishes the two, since
"this set has no cheap work left" is a finding and "nobody baked the ledger" is a missing input.

Baked for the same reason the denominator is — production has no Scryfall cache, and computing a
verdict means running the grammar over Oracle text. `just assay-bake` writes
`game-server/.../resources/coverage/assay-verdicts.json`: one sorted line per card, `{ name }` when
[`CardCompiler`](../oracle-assay/README.md#the-verdict-ledger) reads it whole and
`{ name, kind, line }` when it doesn't, naming the decline and the printed line it points at.
`AssayVerdictService` joins it by front-face name; a missing or malformed resource degrades to no
badges rather than a server that won't boot. Re-bless it in its own commit after a grammar change —
the file doubles as Assay's per-card regression ledger, so the diff is the list of cards whose
reading moved.

**Assay Explorer** — `GET /api/assay/explorer` (+ `/{route}`) → the live Argentum Assay explorer,
mounted inside the app so the Set Completion view can offer it as a second tab. It is the *same*
page and the same handlers `just assay-explore` serves: `ExploreApi` in `:oracle-assay` owns every
route, both servers only move bytes, and the page's request prefix is substituted at serve time. The
client frames it in an `<iframe>` rather than reimplementing the views, which would be free to drift
from the gates they display.

Ungated: it is a read over public card text, holds no state a request can mutate, and touches no
game, account or corpus. The cost is resources, not exposure, and the sweep is lazy for exactly that
reason — `ExploreApi` is constructed and its corpus sweep started on the **first request**, so a
server nobody opens the tool on fetches nothing. Where `~/.cache/scryfall` is absent (a production
container) that first sweep downloads the 24 MB Oracle bulk; if it can't, the page stays up with its
live parser and rule tree and reports the failure through `status`. The differential decodes
`mtg-sets` *test* resources, which aren't in the bootJar, so in production that one page answers
"no goldens found" and the rest is unaffected.

**Extras are sectioned like a Scryfall set page.** scryfall.com/sets/`<code>` splits a set into
"Draft Cards" plus named runs of non-booster printings, and `extraGroups` mirrors the ones that
matter here — "Starter Decks", "Planeswalker Decks", "Brawl Decks", "Starter Collection",
"Beginner Box", "Set Extension", "Promos", "Special Art", and an "Other Cards" catch-all — so the
view can say *which product* a completionist card comes from. `scripts/gen-set-totals` derives each
label from the printings' Scryfall `promo_types` (see `EXTRA_GROUPS` there) and emits `extra`
pre-sorted into those sections; the server groups by label in encounter order. Scryfall's remaining
headings are art-variant runs (Borderless, Showcase, Extended Art, Raised Foil) — those are
alternate *printings* of cards already in the draft pool, so against this card-name denominator they
contain nothing new and never appear. Sectioning only partitions the extras: it never moves a card
in or out of `extraTotal` / `extraImplemented`. Sets with no booster at all have no extras and so no
sections.

**Cards we won't implement.** A card needing a mechanic the engine will never carry (ante, subgames,
physical dexterity) is listed in the repo-root `coverage/card-exclusions.json` manifest, keyed by name
so one entry covers every set that prints it. `scripts/gen-set-totals` bakes the flag onto the card as
`"notPlanned": { "kind": "ante", "why": "…" }` — exclusion is carried *as* its reason, so a not-planned
card can never render as an unexplained gap. Those cards stay in `draft` / `extraGroups` (the detail
view lists them with a badge) but drop out of `total` / `extraTotal` while unimplemented and are counted in
`notPlanned` / `extraNotPlanned` instead, so "complete" means *everything we intend to build is built*.
Implementing one silently un-excludes it: the flag only ever moves a card out of the still-to-do
bucket. `scripts/card-status` applies the same manifest in its `Skip` column.

**Implementation progress** — `GET /api/sets/progress` → the distinct-implemented-cards-over-time
series (one cumulative point per calendar day since the project began), `[{ date, added, total }]`.
Drives the chart behind the Set Completion overall-progress element. Git history isn't reachable at
runtime, so `scripts/card-progress-graph` bakes the series (alongside the root
`card-implementation-progress.html` + README SVG) into
`game-server/.../resources/coverage/implementation-history.json`.

The denominator (canonical booster + extra front-face card names) isn't knowable at runtime — it
lives only in the local Scryfall cache. `scripts/gen-set-totals` bakes those canonical cards, split
into `draft` (some printing of the card in that set is Scryfall `booster: true`) and `extra`, each
`{ name, img }` (direct CDN art URL) plus `{ products, group }` on the extras, into
the committed `game-server/.../resources/coverage/set-totals.json` resource (same partitioning as
`scripts/card-status`, so the numbers match the mtgish coverage TUI). Baking the art URL lets the
detail view render set-specific images for *missing* cards too, without hammering the rate-limited
Scryfall name-lookup API. At request time `SetCoverageService` joins that static denominator with the
*live* card catalog: `implemented` is the count of a set's canonical names we've actually authored
(`card` + `basicLand` + reprint `Printing` rows, front-faces) — an intersection, so it can never
exceed the canonical count. A set with no booster (Commander / supplemental, every card
`booster: false`) uses the whole set as the main pool, so its headline isn't a useless 0/0. Re-run
`scripts/gen-set-totals` (after `scripts/card-status --refresh`) to refresh totals for new/spoiler
sets.

## 3b. AI Assistance Payload (REST / HTTP)

In-app AI help for the player at the wheel: **Suggest Pick** (draft) and **Auto-build** (deckbuild).
Stateless w.r.t. the draft/deckbuild flow — the client sends card **names** (it already holds the
pack/pool) and the server re-resolves them against the card registry. The actual engines live behind
a pluggable SPI in the `ai` module (`AdvisorCatalog`). Two engines ship: **`heuristic`** (the
default, effect-tree heuristic) and **`draftsim`** (a port of the Draftsim ratings/archetype model;
loads per-set ratings/removal/archetype tables, falling back to a rarity ladder for sets it has no
table for). The client picks the engine via the per-player dropdown; `advisorId` omitted ⇒ default.

**Gating.** When a `lobbyId` is supplied and that tournament has `aiAssistEnabled = false` (a
`LobbySettings` field, host-toggled), every endpoint below returns **403**. The client also hides the
controls. Requests with no `lobbyId` (practice) are allowed. This gate is **advisory, not
anti-cheat**: it trusts the client-supplied `lobbyId` (as do the other REST endpoints), so a modified
client could still reach the engines. The toggle signals that assistance is unwelcome for an event;
it does not hard-enforce it.

**List engines** — `GET /api/ai-advisors` → `{ "draft": [{ "id", "name" }], "deckbuild": [...] }`.
Populates the per-player engine dropdowns.

**Suggest a pick** — `POST /api/draft/suggest-pick`

```json
{ "lobbyId": "lob_1", "advisorId": "draftsim", "pack": ["Shivan Dragon", "..."],
  "pickedSoFar": ["..."], "packNumber": 1, "pickNumber": 3, "picksRequired": 1,
  "setCodes": ["LTR"] }
```
Response: `{ "advisorId", "scores": [{ "cardName", "score": 0-100, "reason" }], "recommended": ["..."] }`.
`setCodes` lets a set-specific engine (Draftsim) load the right tables; when a known `lobbyId` is
supplied the server overrides it with the lobby's authoritative set codes (the body value is the
practice / no-lobby fallback). The heuristic engine ignores it.

**Auto-build / complete a deck** — `POST /api/deckbuild/auto-build`

```json
{ "lobbyId": "lob_1", "advisorId": "draftsim", "pool": ["Bear", "Bear", "..."],
  "basics": ["Plains", "Island", "Swamp", "Mountain", "Forest"],
  "lockedDeck": { "Bear": 2 }, "targetSize": 40, "setCodes": ["LTR"] }
```
Response: `{ "advisorId", "deckList": { "<name>": <count> }, "score": <number|null>, "archetype": <string|null> }`.
The client splits `deckList` into non-land cards + basic-land counts and applies it via the
deckbuilder's `setDeck`. `lockedDeck` empty = build fresh; non-empty = keep those cards and only fill
the rest (**heuristic** engine). The **draftsim** engine ignores `lockedDeck`/`targetSize` and always
returns a fresh 40-card limited build (23 nonland + 17 lands), matching the original Auto-Build.

## 3c. Cube Pack Source (WebSocket)

`UpdateLobbySettings` may replace the lobby's normal set source with a cube by sending the full
`cubeCards` name list plus `cubeName`, `packSize`, and `cubeBasicLandSetCode`. Duplicate names are
duplicate physical cards. The server resolves the entire list atomically; an unresolved card rejects
the update, and `cubeCards: []` clears cube mode. While a cube is active, `setCodes` changes,
`boosterDistribution`, and `chaosBoosters` are inert.

`LobbySettings` broadcasts only the public summary: `cubeName`, `cubeCardCount`, `packSize`, and
`cubePoolPlay`. The synthetic `CUBE` set is deliberately absent from `availableSets`. The server
rejects starting when the selected format would need more cards than the cube contains.

Saved cubes are account data, not lobby data: `/api/account/cubes` (see
[`accounts-and-persistence.md`](accounts-and-persistence.md)) stores them, and the lobby only ever
sees the expanded `cubeCards` list — which is what lets a guest, or a cube that was never saved
anywhere, play exactly the same way.

### Pool Play

`UpdateLobbySettings.cubePoolPlay` turns a **cube `SEALED`** lobby into Pool Play: nothing is dealt,
every player's `cardPool` is the entire cube, and copies are unlimited up to the 4-of cap. It is
rejected on a lobby with no cube or a non-`SEALED` format (rather than accepted and ignored), and
cleared automatically when the cube is cleared or the format changes away from `SEALED`.

`SealedPoolGenerated.poolPlay` tells the deckbuilder which pool semantics apply: with `poolPlay: true`
`cardPool` is the whole cube and adding a card must not consume it, so the client shows copies-in-deck
rather than copies-remaining. Consequences on the server side: the capacity check does not apply, the
"copies available in pool" validation is skipped (membership + the 4-of cap still hold), and the
sideboard is **not** derived from the pool — a Pool Play deck submits an empty sideboard, because
deriving `pool − maindeck` would seed the entire cube into the SIDEBOARD zone.

## 3d. Free-for-All Lobby Mode (WebSocket)

A lobby carries two orthogonal axes: the **format** (`SEALED` / `DRAFT` / `PREMADE_DECKS` / …,
how the card pool is built) and a new **mode** (`gameMode`: `TOURNAMENT` or `FREE_FOR_ALL`, what
happens once decks are in). `TOURNAMENT` runs the existing round-robin bracket of 2-player matches.
`FREE_FOR_ALL` (CR 806) seats **every** lobby player (2–6) in **one** multiplayer `GameSession` —
no rounds, no matches, no bracket. The two axes compose: any pool-building format + FFA = "draft (or
sealed, or premade), then one N-player game".

- **`CreateTournamentLobby` / `UpdateLobbySettings`** gain an optional `gameMode` (default
  `TOURNAMENT`). `LobbySettings.gameMode` echoes it. Switching a lobby to `FREE_FOR_ALL` caps
  `maxPlayers` at 6. `TWO_HEADED_GIANT` and `TEAM_VS_TEAM` are the two **team** modes (see below);
  both share the single-pod FFA lifecycle (one `GameSession`, play-again, standings).
- **AI seats at a pod.** `AddAiToLobby` works in every mode and every format: an AI is an ordinary
  seat, counted by the mode's own cap, and the engine AI reads a pod as N opposing sides
  (`ai/engine/Sides.kt`) and a 2HG team's pooled life as one total. `FreeForAllHandler` wires each AI
  seat to the pod's `GameSession` when the game starts and marks them ready between games, so only
  the humans are ever waited on. Where the AI's deck comes from follows the format: a generated pool
  is built by `buildAiPoolDeck`, and `PREMADE_DECKS` — which generates no pool — has one rolled by
  `RandomDeckResolver` at the moment the AI sits down, the same component and the same rule the quick
  lobby's `vsAi` seat has always used. Changing the lobby's format or `deckFormat` afterwards
  re-rolls it, since both decide what may be in it.
- **`SetLobbyAiDeck { playerId, spec }`** is the per-seat twin of `SetQuickGameAiDeck`: the host picks
  what *one* AI brings, in the same `AiDeckSpec` vocabulary (`auto` / `sets` / `deck`). Held per seat
  on `LobbyPlayerState.aiDeckSpec` and echoed back as `LobbyPlayerInfo.aiDeck` — an `AiDeckSpecView`
  summary (kind, sets, label, card count, designated commander), never the decklist itself, since
  lobby state re-broadcasts on every change. A `deck` spec carries an optional `commander`; the list
  is validated against the lobby's `deckFormat` on arrival, and the
  seat's deck is re-rolled immediately rather than at game start: the premade start gate wants every
  seat to have submitted, so the deck has to exist while the host is still looking at the lobby.
  Rejected outside `PREMADE_DECKS`, where the AI builds from the pool it was dealt.
- **Commander AI.** Every source picks its own commander. `auto` / `sets` build a singleton deck to
  the lobby's commander-shaped `deckFormat` — or to paper Commander when the Rules axis says Commander
  and no legality was set — and a limited pool is built from with `CommanderDeckGenerator.generateFromPool`.
  A `deck` spec is the one source that can be *missing* a commander, since the host chose the list; the
  server validates the full Commander deck on arrival and the lobby holds at its normal deck-submission
  gate until the choice exists. A seat whose pool holds no legal commander at all stays un-submitted
  rather than seating a deck the engine would refuse at init.
- **Attack rule.** The same two messages also carry an optional `attackMode` (default `MULTIPLE`),
  echoed by `LobbySettings.attackMode`, choosing which opponents creatures may attack in the FFA
  game (CR 802 / 803; CR 806.2b requires exactly one): `MULTIPLE` (any opponent), `LEFT`, or
  `RIGHT` (only the neighbour in that seat direction). It threads to the engine via
  `GameConfig.attackMode` → `GameState.attackMode`; the legal-action enumerator filters
  `validAttackTargets` and the engine rejects an out-of-seat declaration. Ignored in `TOURNAMENT`
  mode and in any two-player game (all three modes permit the sole opponent).
- **Team modes — Two-Headed Giant (CR 810) and Team vs. Team (CR 808).** Both split the pod into two
  even teams and share the same controls: an optional `randomTeams` (default **`true`**) and
  `teamAssignments` (playerId → team index `0`/`1`, full map), echoed by `LobbySettings`.
  `randomTeams = true` shuffles the seats into two even teams at game start, re-rolled each game;
  `false` uses the host's `teamAssignments`. At start the server resolves the partition
  (`EvenTeams.partition`): random, or the manual map balanced into even teams, falling back to
  seat-order grouping if the manual assignment can't form two equal teams. The result flows to
  `GameSession.teams` → `GameConfig.teams`. The two modes differ only in the engine `Format` chosen:
  - `TWO_HEADED_GIANT` — exactly four players (`Format.TwoHeadedGiant`): teams share one 30-life
    total, take shared turns, fight combined combat, and win/lose together.
  - `TEAM_VS_TEAM` — an even pod of 4/6/8 (`Format.TeamVsTeam`, i.e. 2v2/3v3/4v4): **nothing is
    shared** (CR 808.5). Each player keeps their own 20 life and their own turn, is eliminated
    individually (CR 104.3b), and a team loses only once all its members have left (CR 104.2c).
    `maxPlayers` caps at 8.

  The seat roster (`PlayerSeatInfo`) carries `teamIndex` for grouping and a game-level
  `teamSharedLife` flag (`true` for 2HG, `false` for Team vs. Team) so the client renders either a
  single shared-life team header or per-player life. Ignored outside a team mode.
- **Free mulligan.** A game that begins with more than two players (any FFA pod) uses the CR 800.6
  multiplayer mulligan: a player's *first* mulligan is free — it bottoms 0 cards and doesn't count
  toward the mulligan limit. This is engine-internal; the existing `MulliganDecision.cardsToPutOnBottom`
  already reflects the discounted count, so no client change is needed. Two-player games are
  unaffected (plain London Mulligan).
- **Start.** When the last deck is submitted (or the host starts a premade FFA lobby), the server
  creates one `GameSession` seating all players and broadcasts **`freeForAllGameStarting`**
  `{ lobbyId, gameSessionId, gameNumber, players: PlayerSeatInfo[] }` — the FFA counterpart of
  `tournamentMatchStarting`. Each recipient's roster flags its own seat (`isYou`); spectators get an
  all-`isYou=false` roster. `GameStarted` + the mulligan flow follow exactly as in any game.
- **Mid-game elimination.** Conceding (or a disconnect-forfeit) in a >2 pod concedes that seat and
  the game **continues** for the rest (CR 800.4a). The conceding player gets a personal
  **`playerEliminated`** `{ gameId, reason }` so their client shows defeat and returns to the pod
  standings while the table plays on; everyone else sees the seat drop out via the normal state
  rebroadcast (the eliminated player's `ClientPlayer.hasLost` is `true`). The game-wide `gameOver`
  only fires when ≤1 player remains (CR 104.2a).
- **Standings + play-again.** When the game ends, **`freeForAllGameComplete`**
  `{ lobbyId, standings: FfaStandingInfo[], gamesPlayed }` reports the **elimination order** as
  placements (`placement` 1 = winner, then last-eliminated, … back to first-eliminated). The pod
  stays open: each player sends `readyForNextRound` ("Play Again") and, when all connected players
  are ready, a new game (`gameNumber + 1`) starts with the same seats. Replays are saved per game as
  usual and browsable via the lobby's replay endpoint.
- Quick Game stays strictly 2-player (its `QuickGameLobby.MAX_PLAYERS` is untouched); FFA lives only
  in the tournament-lobby infrastructure. Its opponent seat is mutable: the host sends
  **`AddQuickGameAi`** to fill an open 1v1 seat with the built-in AI and **`RemoveQuickGameAi`** to
  reopen that same seat to a human. `QuickGameLobbyState.vsAi` reports the current occupant rather
  than a separate lobby kind; the create message's `vsAi` remains a shortcut for initially filling
  the seat.

## 4. Scenario Builder Payload (REST / HTTP)

The Scenario Builder lets any player construct an arbitrary board state and play it. It is a
production feature: `POST /api/scenarios` is **not** gated behind `game.dev-endpoints.enabled`
(the older `POST /api/dev/scenarios` is the dev-only equivalent and shares the same request
shape + builder via `ScenarioBuilderService` / `ScenarioSessionFactory`).

**Request: `POST /api/scenarios`** (`ScenarioRequest`)

```json
{
  "player1Name": "Me",
  "player2Name": "Also me",
  "player1": {
    "lifeTotal": 20,
    "hand": ["Lightning Bolt"],
    "battlefield": [
      { "name": "Grizzly Bears", "tapped": true, "counters": { "PLUS_ONE_PLUS_ONE": 2 } },
      { "name": "Pacifism", "attachedTo": "Grizzly Bears" }
    ],
    "graveyard": ["Mountain"],
    "exile": ["Swamp"],
    "library": ["Forest", "Forest"],
    "commanders": []
  },
  "player2": { "lifeTotal": 20, "battlefield": [{ "name": "Hill Giant" }] },
  "phase": "PRECOMBAT_MAIN",
  "activePlayer": 1,
  "mode": "SELF"
}
```

- `mode` selects how the opponent seat is filled: `SELF` (single-client hotseat / play against
  yourself — one token controls both seats), `AI` (engine AI, requires `game.ai.enabled`;
  `aiPlayer` 1|2 picks the seat), or `TWO_PLAYER` (two tokens). When omitted it is derived from
  `aiPlayer` for back-compat.
- Validation rejects unknown card names and (production) enforces per-zone + total card caps,
  returning `400` with `{ "errors": ["Unknown card: …", …] }`.
- `customCards` (optional) is a list of **Scryfall(-style) card objects, as JSON strings**. Argentum
  Assay compiles each into a real `CardDefinition` and registers it in a `CardRegistry` overlay for
  that session only, so its name can be used in any zone like a corpus card. Requires
  `game.dev-endpoints.enabled`; a card any of whose printed lines Assay cannot read is refused with
  the line that stopped it. The sources travel with the scenario (JSON, file, share link) — without
  them the names do not resolve and the request fails with `Unknown card`.

**Dev-only: `POST /api/dev/scenarios/assay`** (`AssayCompileRequest` → `AssayCompileResponse`) —
compile one pasted card without starting a game. Always `200`; a card that does not compile is the
answer, not an error.

```json
// request
{ "json": "{\"name\":\"Argentum Sentinel\",\"mana_cost\":\"{2}{W}\", …}" }

// response
{
  "cardName": "Argentum Sentinel",
  "compiled": true,
  "lines": [
    { "index": 0, "text": "Flying, vigilance", "verdict": "ROUND_TRIP" },
    { "index": 1, "text": "When ~ enters, draw a card.", "verdict": "ROUND_TRIP" }
  ],
  "declines": [],
  "warnings": [],
  "definition": { "name": "Argentum Sentinel", "…": "the compiled CardDefinition" }
}
```

`verdict` is the touchstone's own vocabulary (`ROUND_TRIP` | `VARIANT` | `DECLINED` | `AMBIGUOUS` |
`MISMATCH`); `printed` carries the canonical spelling of a `VARIANT`, and `explanation` carries
`assay explain`'s caret for a line that declined. Lines are shown normalized, where `~` is the
card's own name.

**Response** (`ScenarioResponse`)

```json
{
  "sessionId": "…",
  "player1": { "name": "Me", "token": "<token>", "playerId": "player-1" },
  "player2": { "name": "Also me", "token": "<token>", "playerId": "player-2" },
  "message": "Hotseat scenario created — you control both players.",
  "mode": "SELF"
}
```

The client joins by navigating to `/?token=<token>` (token-based connect). For `SELF`/`AI` a
single human token is returned (in `SELF` both `playerX.token` echo the same value); for
`TWO_PLAYER` the two tokens differ.

### Hotseat (`hotseat` on the client state)

`SELF` mode stamps a `HotseatControlComponent(controllerId)` on every seat, so the engine's
`GameState.actorFor(playerId)` routes input authority (decision delivery, legal-action
enumeration, per-action seat authorization, and hand visibility) for both seats to the single
connection — the non-turn-scoped generalization of the Mindslaver-style hijack seam. The
client state carries a boolean **`hotseat`** so the UI can show a "controlling both players"
banner and act for whichever seat currently holds priority (board actions ride the
server-provided `legalActions`, which already carry the acting seat; `SubmitDecision` is stamped
with `pendingDecision.playerId`; combat declarations with the active/defending seat).

### Canonical replays (record choices and authoritative states)

New recordings use canonical replay schema v1. The stream contains a header with the complete
initial `GameState` and initialization events; one transition for every accepted player action,
meaningful authorized rejection, automatic action, and out-of-band system mutation; and a terminal
record. Each transition carries the exact action or mutation, actor and submitter, emitted events,
resulting-state digest, and either a full canonical JSON state or a deterministic JSON patch.

Reconstruction applies the recorded patches directly. It does not execute current engine or card
logic, so a rules change cannot rewrite the game. A SHA-256 record chain detects removal, insertion,
reordering, and modification. Full states are forced every 128 transitions to bound patch depth for
indexed seeking; between them a patch is selected only when its encoded form is smaller.
`COMPLETE` is legal only for a terminal `GameState`; interrupted or abandoned streams end with an
explicit `INCOMPLETE` reason. A crash before terminalization leaves a plainly named `.partial`
tournament artifact or an in-progress server prefix, never a file that claims completion.

#### One store

Every replay — finished or still being recorded — has a parent row in `game_replays`, written by
`ReplayService` and nobody else. `ReplayStore` has two implementations: `JdbcReplayStore` when
accounts (and therefore a database) are enabled, and a bounded `InMemoryReplayStore` for a server
running without one. With JDBC, the stable compatibility envelope stays in the parent and canonical
records are gzip-compressed in append-only `game_replay_chunks` rows. A checkpoint inserts only the
new suffix and updates the parent count/fingerprint; it never rewrites prior records, deck setup, or
pinned definitions. Readers require contiguous chunks and an exact count before exposing a replay.

In-progress recordings are flushed every few seconds by `ReplayCheckpointFlusher` and picked back
up on restart, which lets the Redis session blob carry no replay data. The flusher keys progress on
canonical record count—not legacy action count—so rejected attempts and yield/undo mutations are
durable even when they do not append an accepted action.

#### Exhaustive recordings and legacy truncation

Canonical writers have no replay-length cutoff. The in-memory lists are append-efficient and the
database writes append-only suffix batches. A pathological game is resolved by `GameStallGuard`; its
final forced state change is itself recorded. `CompactReplay.truncated` and
`ReplayRecordingPolicy.MAX_RECORDED_ACTIONS` remain only to decode and explain v2 prefixes written
before canonical replay v3.

The flush is on a timer, not per action, so a crash can lose the tail of a recording. Splicing the
rest of the game onto that short prefix would produce a record of a game nobody played, so each
flush also writes a `resume_fingerprint` of the live position; on restore, `GameSession` compares it
against the recovered state and stops recording if they disagree, keeping the shorter honest replay.

#### Surviving deploys

Canonical states are the primary durable truth. The older deterministic setup/action recipe remains
readable, and three compatibility payloads continue to protect those historical recordings:

| | What | Cost |
|---|---|---|
| `pinnedCards` | Compiled `CardDefinition` JSON for every card in the decks, overlaid on the live corpus during reconstruction (`ReplayCardPin` → a child `CardRegistry`). Card edits stop mattering; ability ids also stay stable, so recorded yields keep matching. Stored in its own write-once `pinned_cards` column, not in `data`, so the periodic flush doesn't rewrite it. | 7 KB gzipped on POR (34 definitions) up to ~40 KB on a modern set (113) — scales with deck variety, not game length, and is usually the largest part of a record |
| `checkpoints` | A cheap position fingerprint (`ReplayFingerprint`: entity counter, clock, turn/phase, zone sizes, life) every 20 actions. Catches *silent* drift — actions that still apply but no longer produce the board that was played — instead of rendering it. | ~30 bytes each |
| `presentation` | Legacy-only `{initialSnapshot, deltas}` fallback, materialized just after game over and stored gzipped in its own column. Canonical v3 never writes this duplicate because it already decodes authoritative states without current engine code. | Zero for v3; historical v1/v2 archives scale with game length |

`ReplayService.viewerPayload` directly reconstructs canonical states with `EXACT` fidelity. For a v1/v2
record it re-simulates the legacy recipe and verifies checkpoints; if that diverges it serves the
archived presentation instead. `stateReproducible` remains true only when a real authoritative
`GameState` is available.

`CompactReplay.version` is 3. New JDBC envelopes omit duplicate action/checkpoint/canonical arrays;
their transition stream lives only in chunks. Defaults and tolerant envelope decoding preserve v1/v2
reads, while the canonical codec itself is strict about unknown fields and schema versions.

**Size measurement.** `CompactReplaySizeBenchmark` plays random games through the real canonical
recorder and reports the stable envelope, append-only chunks, pins, the avoided legacy presentation,
and a full-state-at-every-transition baseline separately. It reports both compressed binary bytes
and the Base64 text actually stored in JDBC. Random play is action-heavy, so run both a small smoke sample and a representative modern
set before capacity planning:

```bash
just benchmark-replay 40 BLB
```

### "Share frame as scenario" (replay)

The replay viewer can also reproduce an **exact full-state snapshot** — stack, targets, floating
effects, mana, counters and all trackers, not just the public board — by decoding the canonical state
at the requested frame (legacy records still re-simulate). Two entry points:

- **Share as scenario** → copies a *short* link that only references the stored frame:
  `/scenario?replay=<gameId>&frame=<n>`. Opening it `POST`s to `/api/scenarios/from-replay-frame`
  (`{gameId, frame, mode?}`), which calls `ReplayService.reconstructStateAt(gameId, frame)` and
  injects the result into a fresh hotseat session (`mode=SELF` default).
- **Download** → saves the frame's full state as a JSON file
  (`GET /api/public/replays/{gameId}/frames/{frame}/full-state`). Reload it locally from the
  builder's **Load file** button, which `POST`s the file to `/api/scenarios/from-state` (a raw
  serialized `GameState` body) and jumps in. "Load file" also accepts a **name-based** scenario
  JSON (like the `manual-scenarios/*.json`), loading it into the editable builder instead.

A snapshot is exact but **not editable** in the card-search builder; the builder's own name-based
`?s=` share remains for authoring/editing. The engine `GameState` is (de)serialized with
`persistenceJson` (`allowStructuredMapKeys` — `zones` is keyed by `ZoneKey`).
