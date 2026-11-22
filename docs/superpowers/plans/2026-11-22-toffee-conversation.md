# Toffee Conversation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make talking to Toffee feel like talking to one person — fast, one
voice, no tapping, and she knows who Ahmed is.

**Architecture:** Five seams already exist and all five are reused rather than
replaced: `Voice` (the mouth), `Ears` (listening), `Agent` (the backend),
`Prosody` (mood → sound) and `persona.ts` (who she is). The wake word is a
sixth seam of the same shape — a pure phrase matcher plus a thin Android
implementation — so that everything except the Vosk binding stays unit
testable off the device.

**Tech Stack:** Kotlin / Android (`minSdk 27`, `compileSdk 34`, AGP 8.5.2,
armeabi-v7a only), Deno / Supabase Edge Functions, OkHttp, Vosk
(`com.alphacephei:vosk`, Apache 2.0).

**Spec:** `docs/superpowers/specs/2026-11-22-toffee-conversation-design.md`

## Global Constraints

- **No budget.** Free tiers only. No dependency that requires an account, a
  key, or that can have its free tier switched off.
- **No secrets on the device.** No provider key, no service-role key.
- armeabi-v7a only. Every native dependency must ship a 32-bit ARM build.
- `minSdk 27` / `compileSdk 34`. CameraX stays at 1.3.4; nothing may force
  `compileSdk 35`.
- **Nothing throws at the user.** Every failure becomes a sentence on the
  face or a line in `Faults`, never a dialog and never a crash. The app is
  the HOME activity; a crash loop is a brick.
- **She may only claim what she can point at.** This now covers memory as
  well as the live context.
- Silence stays the default. The governor, the cooldown and
  `ALWAYS_SPEAK_PREFIX` are untouched by this plan.
- The two task lists stay separate.

## Review Focus

Five things the design implies that no task's own tests exercise. Each has a
test attached to the task that owns the code.

1. **She wakes herself.** Her own voice leaves the speaker and arrives at the
   microphone; "hey toffee" inside her own sentence must not re-trigger her.
   Task 6.
2. **The microphone is never given back.** Any path out of listening that
   skips releasing it — an exception, a timeout, a closed conversation —
   leaves the next wake recording silence for ever. Task 6.
3. **A stream that dies halfway.** The connection drops after two sentences:
   she must not be left mid-thought with no mood and no reply recorded.
   Task 3 and Task 4.
4. **The conversation closes while she is still talking.** The idle timer
   must measure silence between *exchanges*, not wall-clock since the wake,
   or a long answer closes the conversation it is still answering. Task 5.
5. **A seeded memory that is no longer true.** "He finds her too slow" is a
   fact today and a lie the week after this ships. Seeded history must be
   distinguishable from live context and correctable. Task 2.

## File Structure

**Created**

- `app/src/main/java/dev/toffee/companion/voice/Conversation.kt` — pure. When
  a conversation is open and when it has lapsed.
- `app/src/main/java/dev/toffee/companion/voice/WakePhrase.kt` — pure. What
  counts as having heard the wake phrase.
- `app/src/main/java/dev/toffee/companion/voice/WakeWord.kt` — the `WakeWord`
  seam plus `SilentWakeWord`.
- `app/src/main/java/dev/toffee/companion/voice/VoskWakeWord.kt` — the only
  file that touches Vosk.
- `app/src/main/java/dev/toffee/companion/net/Sse.kt` — pure. Parses a
  Server-Sent Events byte stream into events.
- `supabase/functions/_shared/stream.ts` — turns the agent's sentence
  callbacks into an SSE `ReadableStream`.
- `scripts/seed-memories.ts` — writes the seeded history, idempotently.

**Modified**

- `voice/Prosody.kt` — the mood table (Task 1).
- `voice/Voice.kt`, `voice/AndroidVoice.kt` — queued speech (Task 4).
- `net/ToffeeClient.kt` — `Agent.turn` gains a sentence callback (Task 4).
- `Brain.kt` — speaks sentences as they arrive; owns conversation state
  (Tasks 4, 5).
- `MainActivity.kt` — wake word wiring and microphone handover (Tasks 5, 6).
- `update/Updater.kt` — a download budget that fits a 56 MB APK (Task 6).
- `supabase/functions/_shared/persona.ts` — memories reach the prompt
  (Task 2).
- `supabase/functions/_shared/turn.ts` — responds with SSE (Task 3).
- `app/build.gradle.kts` — the Vosk dependency and the model asset (Task 6).

---

### Task 1: One voice

**Files:**
- Modify: `app/src/main/java/dev/toffee/companion/voice/Prosody.kt`
- Test: `app/src/test/java/dev/toffee/companion/voice/ProsodyTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `Prosody.forMood(Mood): Prosody` — unchanged signature, new
  values.

The existing tests pin the two points the original spec fixed ("low and slow
for menace", "clipped for irritation"). Those must keep passing: this
narrows the band, it does not abolish the moods.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun everyMoodIsRecognisablyTheSameSpeaker() {
    val pitches = Mood.values().map { Prosody.forMood(it).pitch }
    assertTrue(
        "pitch spans ${pitches.min()}..${pitches.max()}; a shift this wide is a " +
            "different speaker, not a mood",
        pitches.max() - pitches.min() <= 0.10f,
    )
}

@Test
fun moodStillChangesHowSheSounds() {
    // Narrow is not flat. If every mood is identical the voice has stopped
    // carrying mood at all, which is the opposite failure.
    val all = Mood.values().map { Prosody.forMood(it) }.toSet()
    assertTrue("every mood sounds identical", all.size >= 5)
}

@Test
fun rateCarriesMoodMoreThanPitchDoes() {
    val smug = Prosody.forMood(Mood.SMUG)
    val neutral = Prosody.forMood(Mood.NEUTRAL)
    assertTrue(
        "menace must be carried by slowness, not by a pitch drop",
        (neutral.rate - smug.rate) > (neutral.pitch - smug.pitch),
    )
}
```

- [ ] **Step 2: Run them and watch the first and third fail**

Run: `./gradlew :app:testDebugUnitTest --tests "*ProsodyTest*"`
Expected: FAIL — pitch currently spans 0.40 (0.75..1.15).

- [ ] **Step 3: Retune the table in `Prosody.kt`**

Keep every mood's entry and its comment. New values stay inside pitch
0.96..1.04 and rate 0.80..1.18, preserving the existing ordering: SLEEPY
slowest, SMUG slow, FRUSTRATED fastest, NEUTRAL at 1.0/1.0. `LISTENING`
stays 1.0/1.0.

- [ ] **Step 4: Run the whole Prosody and voice suite**

Run: `./gradlew :app:testDebugUnitTest --tests "*voice*"`
Expected: PASS, including the pre-existing menace and irritation tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/toffee/companion/voice/Prosody.kt app/src/test/java/dev/toffee/companion/voice/ProsodyTest.kt
git commit -m "fix: one voice, not a cast of them"
```

---

### Task 2: She knows who he is

**Files:**
- Modify: `supabase/functions/_shared/persona.ts`
- Modify: `supabase/functions/_shared/types.ts`
- Create: `scripts/seed-memories.ts`
- Test: `tests/persona.test.ts`

**Interfaces:**
- Consumes: `recall(query, limit, embed)` and `remember(fact, category, embed)`
  from `_shared/tools/memory.ts`; `Memory` from `types.ts`.
- Produces: `Ctx` gains `coreFacts: string[]` and `relevantFacts: string[]`.
  `buildContext(query?: string): Promise<Ctx>` — the optional query is what
  Ahmed just said, used for semantic recall; absent for events.

Seeded facts use `category = "core"` and are always injected. Everything the
agent learns later keeps its own category and is recalled semantically. That
separation is Review Focus 5: a core fact is addressable and correctable,
and `scripts/seed-memories.ts` is idempotent on `fact` so re-running it
neither duplicates nor resurrects a deleted line.

- [ ] **Step 1: Write the failing tests**

```ts
Deno.test("core facts reach the prompt without being asked for", () => {
  const prompt = renderSystem({ ...baseCtx, coreFacts: ["He built her to be unimpressed by him."] });
  assertStringIncludes(prompt, "He built her to be unimpressed by him.");
});

Deno.test("a prompt with no memories says so rather than pretending", () => {
  const prompt = renderSystem({ ...baseCtx, coreFacts: [], relevantFacts: [] });
  assertStringIncludes(prompt, "You do not know anything about him yet");
});

Deno.test("what he just said is what gets recalled against", async () => {
  const asked: string[] = [];
  await buildContext("did I finish the invoice", { recall: (q) => { asked.push(q); return []; } });
  assertEquals(asked, ["did I finish the invoice"]);
});

Deno.test("seeding twice leaves one copy of each fact", async () => {
  await seed(fakeDb); await seed(fakeDb);
  assertEquals(new Set(fakeDb.facts).size, fakeDb.facts.length);
});
```

- [ ] **Step 2: Run them to verify they fail**

Run: `deno test -A tests/persona.test.ts`
Expected: FAIL — `coreFacts` is not a field on `Ctx`.

- [ ] **Step 3: Implement**

Add the two fields to `Ctx`. In `renderSystem`, extend the
`WHAT YOU KNOW RIGHT NOW` block with a `WHAT YOU KNOW ABOUT HIM` section
listing core facts then relevant facts, and the stated sentence when both are
empty. `buildContext(query?)` fetches core facts by category and, when a
query is given, recalls against it. Follow the existing rule in that file:
anything unavailable degrades to empty, never to a failed turn.

- [ ] **Step 4: Write the seed script and run it**

`scripts/seed-memories.ts` writes the nine facts from the design's appendix
verbatim, category `core`, skipping any whose `fact` text already exists.

Run: `deno run -A scripts/seed-memories.ts && deno run -A scripts/seed-memories.ts`
Expected: the first run reports 9 written; the second reports 0 written, 9 already there.

- [ ] **Step 5: Run the backend suite**

Run: `deno test -A tests/`
Expected: PASS, 117 existing tests included.

- [ ] **Step 6: Commit**

```bash
git add supabase/functions/_shared/persona.ts supabase/functions/_shared/types.ts scripts/seed-memories.ts tests/persona.test.ts
git commit -m "feat: she remembers who he is"
```

---

### Task 3: `/turn` says it as it thinks it

**Files:**
- Create: `supabase/functions/_shared/stream.ts`
- Modify: `supabase/functions/_shared/turn.ts`
- Test: `tests/stream.test.ts`, `tests/turn.test.ts`

**Interfaces:**
- Consumes: `ask(...)` in `_shared/llm.ts`, which already calls `onText` once
  per sentence.
- Produces: `sseResponse(run: (emit: Emit) => Promise<Done>): Response`,
  where `Emit = { mood(m: Mood): void; text(s: string): void }` and
  `Done = { reply: string; mood: Mood }`.

The wire format, which Task 4 consumes:

```
event: mood\ndata: {"mood":"smug"}\n\n
event: text\ndata: {"text":"One sentence."}\n\n
event: done\ndata: {"reply":"One sentence. And another.","mood":"smug"}\n\n
```

`done` carries the same field names the current JSON reply uses, so the
existing device parser keeps working against it unchanged.

- [ ] **Step 1: Write the failing tests**

```ts
Deno.test("each sentence goes out as it is produced, not at the end", async () => {
  const seen: string[] = [];
  const res = sseResponse(async (emit) => { emit.text("One."); seen.push("after-first"); emit.text("Two."); return done; });
  const chunks = await readAll(res.body!);
  assertEquals(chunks.filter(isText).map(textOf), ["One.", "Two."]);
});

Deno.test("the done event carries the whole reply and the final mood", async () => { /* ... */ });

Deno.test("a run that throws still closes the stream with a done event", async () => {
  // Review Focus 3: the tablet must never be left waiting on a stream that
  // simply stopped.
  const res = sseResponse(async () => { throw new Error("model died"); });
  const chunks = await readAll(res.body!);
  assertEquals(chunks.at(-1)?.event, "done");
});

Deno.test("a turn still records said_log and llm_spend", async () => { /* unchanged behaviour */ });
```

- [ ] **Step 2: Run them to verify they fail**

Run: `deno test -A tests/stream.test.ts`
Expected: FAIL — `sseResponse` is not defined.

- [ ] **Step 3: Implement `stream.ts` and rewire `turnHandler`**

`sseResponse` builds a `ReadableStream` with
`Content-Type: text/event-stream`, `Cache-Control: no-cache`. `turnHandler`
passes `emit.text` as `ask`'s `onText` and `emit.mood` from the `set_mood`
tool. All existing post-turn bookkeeping runs before `done` is emitted.

- [ ] **Step 4: Run the backend suite**

Run: `deno test -A tests/`
Expected: PASS.

- [ ] **Step 5: Deploy and check from the laptop**

Run: `deno run -A scripts/deploy.ts && curl -N -X POST "$SUPABASE_URL/functions/v1/turn" -H "apikey: $KEY" -H "Authorization: Bearer $TOKEN" -d '{"text":"say three short sentences"}'`
Expected: `event: text` lines appear one at a time, before `event: done`.

- [ ] **Step 6: Commit**

```bash
git add supabase/functions/_shared/stream.ts supabase/functions/_shared/turn.ts tests/stream.test.ts tests/turn.test.ts
git commit -m "feat: the backend stops waiting for the last sentence"
```

---

### Task 4: The tablet speaks as the sentences arrive

**Files:**
- Create: `app/src/main/java/dev/toffee/companion/net/Sse.kt`
- Modify: `app/src/main/java/dev/toffee/companion/voice/Voice.kt`
- Modify: `app/src/main/java/dev/toffee/companion/voice/AndroidVoice.kt`
- Modify: `app/src/main/java/dev/toffee/companion/net/ToffeeClient.kt`
- Modify: `app/src/main/java/dev/toffee/companion/Brain.kt`
- Test: `app/src/test/java/dev/toffee/companion/net/SseTest.kt`,
  `app/src/test/java/dev/toffee/companion/net/ToffeeClientTest.kt`,
  `app/src/test/java/dev/toffee/companion/BrainTest.kt`

**Interfaces:**
- Consumes: the wire format from Task 3.
- Produces:
  - `Sse.parse(source: BufferedSource, onEvent: (name: String, data: String) -> Unit)`
  - `Voice.say(text: String, mood: Mood, interrupt: Boolean = true)` — the
    default keeps every existing caller behaving exactly as it does now.
  - `Agent.turn(text: String, onSentence: (String) -> Unit = {}): Reply`

Two traps, both load-bearing:

**`QUEUE_FLUSH` is wrong for the second sentence.** `AndroidVoice.say`
currently flushes on every call, so streaming into it would make each
sentence cut off the one before. `interrupt = false` must map to
`QUEUE_ADD`.

**`speaking` must stay true between queued sentences.** It is set from
`onDone`, which fires per utterance, so sentence one finishing would report
her as silent while sentence two is still queued — and `onTap` reads exactly
that flag to decide between hushing and listening. Track outstanding
utterances, not the last one.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun sentencesAfterTheFirstAreQueuedNotFlushed() { /* fake Voice records (text, interrupt) */ }

@Test
fun sheIsStillSpeakingWhileALaterSentenceIsQueued() {
    voice.say("One.", Mood.NEUTRAL, interrupt = true)
    voice.say("Two.", Mood.NEUTRAL, interrupt = false)
    voice.finishOne()
    assertTrue("reported silent with a sentence still queued", voice.speaking)
}

@Test
fun theCaptionGrowsAsSentencesArrive() { /* Brain: line accumulates, not replaces */ }

@Test
fun aStreamThatStopsHalfwayStillLeavesHerCoherent() {
    // Review Focus 3.
    agent.emit("text", """{"text":"Half a thought."}"""); agent.die()
    assertEquals("Half a thought.", brain.state.value.line)
    assertFalse(brain.state.value.listening)
}

@Test fun moodArrivingBeforeTheFirstSentenceIsTheMoodItIsSpokenIn() { /* ... */ }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "*Sse*" --tests "*ToffeeClient*" --tests "*Brain*"`
Expected: FAIL — `Sse` undefined; `say` takes two arguments.

- [ ] **Step 3: Implement**

`Sse.parse` reads `event:`/`data:` line pairs, dispatching on the blank-line
boundary; unknown event names are ignored rather than fatal.
`ToffeeClient.turn` sends the same body, reads the response as a stream, calls
`onSentence` per `text` event, and returns the `Reply` built from `done` — or
from what arrived so far if the stream ends early. `Brain` accumulates the
line and calls `voice.say(sentence, mood, interrupt = isFirstSentence)`.

- [ ] **Step 4: Run the unit suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, all 213 existing tests included.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/toffee/companion/net/Sse.kt app/src/main/java/dev/toffee/companion/voice/ app/src/main/java/dev/toffee/companion/net/ToffeeClient.kt app/src/main/java/dev/toffee/companion/Brain.kt app/src/test/
git commit -m "feat: she starts talking before she has finished thinking"
```

---

### Task 5: A conversation that stays open

**Files:**
- Create: `app/src/main/java/dev/toffee/companion/voice/Conversation.kt`
- Modify: `app/src/main/java/dev/toffee/companion/MainActivity.kt`
- Test: `app/src/test/java/dev/toffee/companion/voice/ConversationTest.kt`

**Interfaces:**
- Produces:

```kotlin
class Conversation(
    private val idleAfterMs: Long = IDLE_AFTER_MS,
) {
    val openedAtMs: Long?
    val lastExchangeMs: Long?
    fun open(atMs: Long)
    fun exchange(atMs: Long)
    fun close()
    fun isOpen(atMs: Long): Boolean
    companion object {
        const val IDLE_AFTER_MS = 90_000L
        const val FOLLOW_ON_MS = 10_000L
    }
}
```

All times are `SystemClock.elapsedRealtime()` — never wall clock, which jumps
when the RTC is corrected after a power cut. This bit the brightness ticker
already.

`exchange` is called when he speaks **and** when she finishes speaking. That
is Review Focus 4: a forty-second answer must not age the conversation out
from under itself.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun silenceAfterTheIdleWindowClosesIt() { c.open(0); assertFalse(c.isOpen(90_001)) }
@Test fun eachExchangeKeepsItAlive() { c.open(0); c.exchange(80_000); assertTrue(c.isOpen(140_000)) }

@Test
fun aLongAnswerDoesNotAgeOutTheConversationItIsAnswering() {
    // Review Focus 4.
    c.open(0)
    c.exchange(1_000)    // he spoke
    c.exchange(95_000)   // she finally stopped speaking
    assertTrue("closed while she was still answering", c.isOpen(96_000))
}

@Test fun closingIsIdempotentAndSurvivesAReopen() { /* ... */ }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "*ConversationTest*"`
Expected: FAIL — `Conversation` not defined.

- [ ] **Step 3: Implement `Conversation` and wire it into `MainActivity`**

A tap opens the conversation. After `Heard.Words` and after she stops
speaking, `exchange`. While `isOpen`, reopen the microphone instead of
returning to idle. `Heard.Nothing` inside an open conversation re-listens if
still open and closes silently otherwise — she never announces that she is
leaving the conversation.

- [ ] **Step 4: Run the unit suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/toffee/companion/voice/Conversation.kt app/src/main/java/dev/toffee/companion/MainActivity.kt app/src/test/java/dev/toffee/companion/voice/ConversationTest.kt
git commit -m "feat: one tap starts a conversation, not a sentence"
```

---

### Task 6: "Hey toffee"

**Files:**
- Create: `app/src/main/java/dev/toffee/companion/voice/WakePhrase.kt`
- Create: `app/src/main/java/dev/toffee/companion/voice/WakeWord.kt`
- Create: `app/src/main/java/dev/toffee/companion/voice/VoskWakeWord.kt`
- Modify: `app/build.gradle.kts`, `app/src/main/java/dev/toffee/companion/MainActivity.kt`
- Modify: `app/src/main/java/dev/toffee/companion/update/Updater.kt`
- Test: `app/src/test/java/dev/toffee/companion/voice/WakePhraseTest.kt`,
  `app/src/androidTest/java/dev/toffee/companion/WakeWordTest.kt`

**Interfaces:**
- Consumes: `Conversation.open` (Task 5), `Faults.record` / `Faults.clear`.
- Produces:

```kotlin
interface WakeWord {
    fun start()
    fun stop()
    val listening: Boolean
}
object SilentWakeWord : WakeWord   // the default; tablet still works by tap

class VoskWakeWord(
    context: Context,
    private val onWake: () -> Unit,
    private val onUnavailable: (String) -> Unit,
) : WakeWord

object WakePhrase {
    val PHRASES: List<String>
    fun heard(finalText: String): Boolean
}
```

**Before any of this is built, settle the dependency on the device.** The
pattern this project already uses twice: check the ABI before writing code
against a native library.

- [ ] **Step 1: Prove Vosk loads and detects on the tablet**

Add `implementation("com.alphacephei:vosk:0.3.45")` and the
`vosk-model-small-en-us-0.15` model as an asset. Build, install, and run a
throwaway instrumented test that starts recognition and prints one final
result.

Run: `./gradlew :app:installDebug && adb shell am instrument ... VoskSpikeTest`
Expected: the APK contains `lib/armeabi-v7a/libvosk.so`, and a final result
is printed. **If the library has no armeabi-v7a build, stop and report: the
design's fallback is sherpa-onnx, and that is a new decision, not a
substitution to make silently.**

- [ ] **Step 2: Write the failing pure tests**

```kotlin
@Test fun theWakePhraseIsHeard() { assertTrue(WakePhrase.heard("hey toffee")) }

@Test
fun theSmallModelsNearMissesStillCount() {
    // A 40MB model mishears a proper noun. These are the variants testing
    // on the device actually produced.
    for (s in listOf("hey toffee", "hey toffy", "hey coffee", "a toffee"))
        assertTrue(s, WakePhrase.heard(s))
}

@Test
fun somethingThatMerelyContainsTheWordDoesNot() {
    assertFalse(WakePhrase.heard("i would like a toffee apple"))
}

@Test
fun herOwnSentenceDoesNotWakeHer() {
    // Review Focus 1: her voice reaches her own microphone.
    assertFalse(WakePhrase.heard("you said hey toffee twice already"))
}
```

- [ ] **Step 3: Run to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "*WakePhraseTest*"`
Expected: FAIL — `WakePhrase` not defined.

- [ ] **Step 4: Implement `WakePhrase`, then `VoskWakeWord`**

`WakePhrase.heard` matches the phrase at the **start** of a final utterance
only — that is what separates being called from being discussed, and it is
the cheap half of Review Focus 1. `VoskWakeWord` acts on **final results
only**; partial results containing the phrase cause false wakes. A Vosk
failure calls `onUnavailable`, is recorded in `Faults`, and leaves the tap
working.

- [ ] **Step 5: Hand the microphone over, on every path**

In `MainActivity`: `wake.stop()` before `ears.listen()`, `wake.start()` after
the conversation closes, in a `finally` that covers the exception, timeout
and closed-conversation paths alike. The second half of Review Focus 1 is
here too: stop the wake listener while she is speaking.

- [ ] **Step 6: Raise the download budget**

A 56 MB APK at the rate measured on this tablet (14.9 s for 16 MB) takes
about 52 s, and `Updater` inherits a 25 s call timeout. Give the download
call its own client with a 180 s timeout. Test: assert the Updater's download
client timeout exceeds `ToffeeHttp.DEFAULT_TIMEOUT_MS`.

- [ ] **Step 7: Write the device test**

```kotlin
@Test fun theWakeListenerStartsAndHoldsTheMicrophone()
@Test fun listeningForSpeechStopsTheWakeListenerAndGivesItBack()  // Review Focus 2
```

- [ ] **Step 8: Run everything**

Run: `./gradlew :app:testDebugUnitTest && ./gradlew :app:connectedDebugAndroidTest`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/dev/toffee/companion/voice/ app/src/main/java/dev/toffee/companion/update/Updater.kt app/build.gradle.kts app/src/main/java/dev/toffee/companion/MainActivity.kt app/src/test app/src/androidTest
git commit -m "feat: she answers to her name"
```

---

## Verification that needs the tablet

None of these can be settled off the device, and all of them need Ahmed:

- **She sounds like one person.** Ask her four things that land in different
  moods and listen for whether it is still her.
- **"Hey toffee" works across the room**, and does not fire while she is
  talking, or while Ahmed is on a phone call.
- **The conversation holds.** Say three things in a row without touching the
  tablet, then stay quiet and confirm she lets it go without comment.
- **Speed.** The number to beat is 4.7 s end to end; the target is about 2 s
  to her first word. `toffee.bench` already records turn timings.
- **Battery under continuous listening.** The number that decides whether
  this is viable at all. **Needs a mains adapter before it can be measured.**
- **The self-update still works at 56 MB**, using the same staged
  deliberately-refused row as last time.
