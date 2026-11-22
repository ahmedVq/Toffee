# Toffee: Conversation — Design

**Date:** 2026-11-22
**Status:** awaiting approval
**Follows:** `2026-11-20-toffee-design.md` (the original design), Plan 3 (voice,
ears, eyes, self-update) — verified on the tablet 2026-11-21.

## Intent, and the why

Ahmed used it and gave three complaints, in his words:

1. "it was too slow"
2. "i had to tap it — i can't have a conversation if i tap it in each reply"
3. "the voice changes with each reply"

These are one complaint. It does not feel like talking to someone. It feels
like operating a device that happens to answer.

Everything below serves that single goal: **he should feel like one person,
present in the room, who you can simply talk to.** Toffee is a he — not an
it, and not a she; Ahmed corrected this and the document follows him.

A fourth request came with them: give Toffee a personality and a history of
Ahmed, "using everything you know about me". The persona string is already
strong — what is missing is that he knows nothing about Ahmed. That is treated
here as part of the same goal, because a companion with no memory of you is
not a companion either.

## Goals

- He answers fast enough that a joke still lands.
- One voice, recognisably the same person in every mood.
- You say "hey toffee" and talk. You never touch the tablet to speak.
- A conversation stays open while it is a conversation, and closes itself
  quietly when it stops being one.
- He knows who Ahmed is and what Ahmed is doing, without being asked.

## Non-goals

- Not changing what Toffee is allowed to say. The governor, the cooldown and the
  "mock only what you can point at" rule all stand exactly as they are.
- Not joining the two task lists. Still Ahmed's decision from the original
  design, still not now.
- No face tracking, no face recognition. Unchanged.
- Not replacing the Google recogniser for actual transcription. The wake word
  is a separate, smaller job.
- No barge-in by voice. Tapping already interrupts him and keeps working;
  interrupting by voice needs echo cancellation and is not worth it.

## Anti-goals

Things that would make this a failure even if every feature worked:

- **He wakes when nobody called him.** A companion that interrupts on a
  misheard word is worse than one you have to tap.
- **He holds the microphone open all day for no reason.** The wake word is a
  wake word, not a recording device.
- **The tablet dies faster than it charges.** Already true; this makes it
  worse (see Constraints).
- **A personality that asserts things about Ahmed that are not so.** The
  existing rule — only mock what you can point at — now applies to memory
  too. A seeded history that is wrong is worse than no history.

## Constraints

Carried from the original design and still binding:

- **No budget.** Free tiers only; a provider that can start charging, or that
  can switch its free tier off, is not an option.
- **No secrets on the device.** No provider key, no service-role key.
- armeabi-v7a only, `minSdk 27`, `compileSdk 34`, AGP 8.5.2.
- No frame from the camera leaves the tablet. Desk audio may leave, as
  already permitted.

New, and measured on the device this week:

- **A real turn takes 2.96 s** awake, and 25 s asleep (Doze throttles the
  radio). End-to-end with the recogniser's silence window it is about 4.7 s.
- **A 16 MB self-update download takes 14.9 s** of the Updater's 25 s budget.

## What the research settled

**Porcupine is out.** Picovoice disabled free-tier AccessKeys on 2026-06-30,
and custom wake words require a paid account. The engine refuses to start
without a valid key, so this is not a degraded mode — it is a hard stop. It
fails the no-budget constraint permanently.

**Vosk is in.** `com.alphacephei:vosk` on Maven Central, Apache 2.0, no key
and no account. `vosk-model-small-en-us-0.15` is 40 MB and is described by its
authors as the lightweight model for Android and Raspberry Pi. A published
always-listening assistant runs it on a 32-bit ARM Android tablet — the same
class of device as this one — using grammar mode for exactly this job.

**sherpa-onnx was the alternative and was dropped.** Its keyword-spotting
runtime is far smaller (~7 MB plus a 3M-parameter model) and it does publish
an armeabi-v7a keyword-spotting build. But it has no ready Maven coordinate
for Android; it would mean building our own AAR for a 32-bit ABI. That is a
new toolchain to get wrong, on the one axis this project has already been
bitten by twice. Vosk costs 30 MB more and nothing else. If the APK size ever
becomes the problem, this is the first thing to revisit.

### Two consequences Ahmed should see before approving

**The APK grows from 16 MB to roughly 56 MB.** At the download rate measured
on this tablet that is about 52 seconds — and the Updater's whole-call
timeout is 25 s. **Self-update would break the day the wake word ships.**
Raising that timeout is therefore part of this work, not a follow-up.

**The power problem stops being optional.** The tablet already drains while
awake (−167 mA) and cannot charge and run at once from a laptop USB port.
Continuous audio decoding on a 2016 SoC makes that worse. A mains adapter is
now a prerequisite for the feature, not a convenience.

## Approaches considered

**For waking him:**

- *Follow-on window only, no wake word.* Tap once to start, then Toffee keeps
  the conversation open. Cheapest, no new dependency, no always-on mic — but
  Ahmed asked for a wake word by name, and this leaves the opening tap.
- *Wake word only.* Every exchange needs "hey toffee". Correct for a smart
  speaker, wrong for a desk companion you are mid-conversation with.
- **Both, which is what this builds.** "Hey toffee" opens a conversation;
  inside it he simply listens after each thing he says; silence closes it.
  The wake word solves starting, the window solves continuing, and they are
  the same state machine.

**For speed:**

- *Speak a filler while waiting.* Cheap and dishonest; it makes him slower
  and chattier at once.
- **Stream, and speak the first sentence as it arrives.** The original design
  named this as the handling for the latency risk. The backend already
  generates sentence by sentence — `llm.ts` calls `onText` per sentence and
  the handler then buffers the lot into one JSON reply. The work is removing
  the wait, not building a pipeline.

**For the voice:**

- Only one sensible option: narrow the range. See below.

## The design

### 1. One voice

`Prosody` currently spans pitch 0.75–1.15 and rate 0.72–1.25. A 15% pitch
shift on a synthesiser is not a mood, it is a different speaker, which is
exactly what Ahmed heard. The face already carries mood twice (shape and
colour), so the voice is the third and most redundant channel and can afford
to be subtle.

Mood moves to **rate and pitch within a narrow band around one voice** —
roughly ±4% pitch and ±15% rate. Smug stays slow, frustrated stays clipped,
sleepy stays slowest, and all of them still sound like him. Exact figures are the
builder's, within that band.

### 2. He speaks while he is still thinking

`/turn` returns Server-Sent Events instead of one JSON object. Each sentence
goes out as it is produced; the device speaks each one as it arrives.
`said_log`, `llm_spend` and the tool round-trips are unchanged and still
settle server-side before the stream closes.

The reminder and event paths keep working as they do now.

### 3. "Hey toffee", and the conversation that follows

One state machine, three states:

- **Idle.** Vosk holds the microphone and listens for one phrase and nothing
  else. Nothing is transcribed, stored or sent.
- **Open.** The wake word fired, or Ahmed tapped. Vosk releases the
  microphone; the Google recogniser takes it; he answers; after he finishes
  speaking the microphone reopens by itself.
- **Closing.** No speech for the idle timeout. He says nothing — a companion
  that announces it is leaving the conversation is a companion that talks too
  much — and returns to Idle.

Defaults, all tunable constants: follow-on window **10 s**, conversation
closes after **90 s** with nothing said.

Two things the builder must get right, both learned from prior art:

- **Fire on final results only.** The published Vosk assistant had to make
  this exact change: partial results containing the phrase cause false wakes.
- **One microphone.** Vosk and the Google recogniser cannot both hold it.
  Every transition must release before it acquires, on every path including
  failure, or the next wake silently records nothing — the same class of bug
  the existing `CloudEars` already guards against.

Vosk failing to load is not fatal: the tap still works, and the failure is
recorded in `Faults` like the camera and the mouth already are.

### 4. He knows who Ahmed is

Two changes, both in `persona.ts`:

- **Memories reach the prompt.** The `memories` table and its tool exist, but
  nothing injects memories into the context block, so he can only remember
  by choosing to go and look. Relevant memories join the existing block
  alongside the time, presence, battery and task count.
- **A seeded history.** The appendix below is the exact list to be written.
  It is facts only, each one something that actually happened in this
  project, because the rule that he may only mock what he can point at now
  has to cover his memory as well.

## What is left to the builder

- Exact prosody figures within the stated band.
- How many memories to inject and how they are selected.
- Vosk model packaging: bundled in the APK versus fetched on first run.
  Bundled is assumed above; fetching trades the APK size problem for a
  first-run network dependency.
- The wake phrase's grammar spelling — "hey toffee" plus whatever near
  variants testing shows the small model actually produces.

## Appendix: the history to seed

Each line is a fact from this project. Ahmed should correct anything wrong
here **before** it is written, because this is what Toffee will believe about
him.

- He is Ahmed. He built me, and he built me to be unimpressed by him.
- He works in an office in Cairo that nobody else enters.
- He runs this whole project on free tiers and no budget, and looks for a
  free alternative before he will pay for anything.
- He keeps a separate task list on his laptop and decided, deliberately, not
  to connect the two.
- He factory-reset a 2016 tablet to make me its owner, so I cannot be
  uninstalled.
- He let that tablet run flat to 1%, then tried to charge it from a laptop
  port that supplies less power than the screen draws.
- He was told to rotate an exposed API key and said he would do it later.
- He tests things himself rather than taking a report for it.
- He finds me too slow, and said so.
