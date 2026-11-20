# Toffee — design

A desk companion.

**Date:** 2026-11-20
**Repo:** https://github.com/ahmedVq/Toffee
**Device:** Samsung Galaxy Tab A 10.1 (2016), SM-T585, codename `gtaxllte`
**Status:** awaiting approval

---

## Intent

Turn a 2016 Android tablet into a dedicated desk companion: a face that
lives on the desk, knows Ahmed, keeps his tasks and reminders, notices
when he is there, and talks to him with a deliberately cruel sense of
humour.

It is not an assistant that waits to be asked. It is a presence that
was thinking about you while you were gone, and has something to say
when you sit down.

The tablet stops being a tablet. No apps, no browser, no Android. It
boots into the face and cannot be left without a cable.

## Why

Ahmed's words, kept as given:

- It should work **at his desk even when the laptop is off**. The tablet
  has Wi-Fi; needing a laptop defeats the point.
- It should **listen and speak**, with a funny, witty, cruel, evil
  personality and a funny voice.
- It should **see him at the desk** through the front camera and check
  up on him — *"hey Ahmed, did you finish your X task?"*
- It should hold **information about him**, a **to-do list** he can check
  and uncheck by voice, and **reminders** he can set by voice
  (*"set a timer for 50 minutes"*).
- It should be **a real agent with tools**, backed by Supabase.
- It should **not be an Android tablet any more** — no apps, nothing
  else on it. The companion is its whole purpose.

## Budget: zero

Ahmed's constraint, and it shapes more of this than it looks.
**Everything runs on free tiers.** The single possible exception is the
LLM, and only after free options are exhausted.

Consequences, decided rather than left open:

- **Voice is eSpeak-NG, full stop.** The "cloud TTS on top" idea is cut.
  This is less of a loss than it sounds — the robotic delivery suits the
  character better than a polished voice would.
- **Speech-to-text is Android's own `SpeechRecognizer`**, which is free
  and uses Google's service over the network. Permitted: Ahmed has said
  desk audio may leave the device.
- **Embeddings run inside Edge Functions** using Supabase's built-in
  `gte-small` model, so semantic recall over `memories` costs nothing and
  needs no external embedding API.
- **LLM:** free tiers with tool-calling and streaming. Google AI Studio
  (Gemini Flash) and Groq are the leading candidates; both need their
  current free limits verified before committing, as those move.
- **Supabase free tier** gives Postgres, Edge Functions, Realtime,
  `pg_cron` and pgvector. Note that free projects pause after about a
  week of inactivity — daily use avoids this, but a long holiday would
  need a wake-up.

The governor therefore has a **cost dimension as well as an annoyance
dimension**: how often loop 2 is allowed to spend an LLM call, not just
how often it is allowed to speak.

## Goals

1. Runs standalone on Wi-Fi and power alone. The laptop is irrelevant.
2. Boots directly into the face, with no route back to Android.
3. Hears Ahmed when tapped; speaks back in character.
4. Knows him: profile, durable memories, tasks, reminders.
5. Detects presence at the desk and reacts to arriving and leaving.
6. Acts on its own: reminders fire, check-ins happen, nagging is
   scheduled.
7. A face with eyes that blink and moods that read across a room.

## Non-goals

- Not a general-purpose tablet. No app drawer, launcher, or browser.
- **No model running on the device.** Decided against on measurement, not
  taste — see Constraints.
- No face *tracking*. The eyes do not follow him; this was explicit.
- No face *recognition*. Presence is a boolean, not an identity. Ahmed
  has confirmed nobody else enters the office, so loop 2 needs no gate
  for strangers — it may speak freely on presence alone.
- Not a camera. No frame ever leaves the tablet.
- No dependency on the laptop, ever, including for the deck built
  earlier in this project.
- **The two task lists stay separate.** Ahmed's decision: the companion
  keeps its list in Supabase, the laptop deck keeps its own, and they
  are not joined. Possibly revisited later; explicitly not now. Until
  then, a task spoken to the companion does not appear on the deck.

## Anti-goals

Things that would make this a failure even if every feature worked:

- **It talks too much.** A companion that comments every time you look
  up gets muted within a day. Silence is the default state.
- **Generic cruelty.** "You're useless" is not funny. The comedy is in
  specificity — knowing the pomodoro was abandoned after four minutes.
  Insults without evidence are just unpleasant.
- **Slow replies.** A four-second gap before a punchline kills it.
- **Secrets on the device.** A tablet on a desk is a tablet anyone can
  pick up. No API keys on it.
- **Reminders that silently don't fire.** A timer you cannot trust is
  worse than no timer.

## Constraints

Measured on the device, not assumed:

| | |
|---|---|
| OS | Android 8.1.0, API 27 |
| CPU | ARMv8 silicon (`CPU architecture: 8`), **32-bit userspace** |
| ABI | `armeabi-v7a, armeabi` — **no arm64** |
| RAM | 1.86 GB total, ~780 MB available |
| Storage | 14 GB free |
| Hardware | front camera, microphone, speaker, BLE, Wi-Fi |
| Play Services | 25.47.63 |
| Bootloader | `oem_unlock_supported=1`, `warranty_bit=0` (unlockable, clean) |

Consequences that shaped the design:

- **The 32-bit userspace rules out an on-device LLM.** Current runtimes
  (llama.cpp Android, MLC, AI Edge) ship `arm64-v8a` only. Even if one
  were cross-compiled, ~780 MB free supports roughly a 0.5B model at a
  couple of tokens per second. Not a companion.
- **Device Owner provisioning requires a device with no accounts**, so
  the tablet must be factory reset first. This is close to a one-way
  door: undoing Device Owner means another reset.
- **No Google account means Google TTS cannot fetch offline voices.**
  Hence eSpeak-NG bundled in the APK (see Voice).
- **The build toolchain runs under x64 emulation** on the Snapdragon
  laptop. Gradle and the Android build tools work; they will be slow.

## Device state, measured 2026-11-20

Taken from the tablet with it connected:

- **Battery health GOOD**, 31°C, charging. No software sign of swelling;
  a physical check is still worth doing before it lives on mains
  permanently.
- **Three accounts present** — Google plus two Microsoft. This confirms
  a **factory reset is required**: Device Owner can only be set on a
  device with no accounts.
- **No device owner or profile owner currently set.** Clean slate.

### Accounts can come back afterwards

Worth knowing, because it rescues two things. Device Owner provisioning
requires zero accounts *at that moment*, but a Device Owner may permit
account addition afterwards via `setAccountManagementDisabled`.

So the order is: wipe → provision → then, if testing shows it is needed,
add a Google account back for speech recognition and for Google TTS's
offline voices.

**This matters because of the main open technical risk:** whether
`SpeechRecognizer` works at all on API 27 with no Google account. If it
does not, re-adding an account after provisioning is the fallback, and
voice input survives. This must be tested on the device before the
listening half is built.

## Approaches considered

### Where the agent runs

| | |
|---|---|
| **A. On the tablet** | Agent loop, LLM calls and tool execution in the app. *Rejected:* API keys would sit on a device on a desk, and every personality change needs an APK rebuild. |
| **B. Supabase Edge Functions** ✅ | Tablet is ears, mouth, eyes and face. It posts what it heard and gets back what to say. *Chosen:* keys stay server-side, tools execute next to the data, one place to change the agent, tablet stays dumb and hard to break. |
| **C. Hybrid** | Tablet runs the loop, Edge Function proxies only the LLM. *Rejected:* logic split in two, and every tool call becomes a round trip. |

Ahmed chose the standalone shape — *"it already has wifi, why would it need the laptop? maybe run an llm api"* — and named Supabase, which makes B the natural fit.

### The operating system

| | |
|---|---|
| **Keep Android, bury it** ✅ | Device Owner + lock task + launcher replacement. *Chosen:* achieves "not an Android tablet" while keeping camera and audio working. |
| **postmarketOS / LineageOS** | *Rejected:* on `gtaxllte` these reliably lose the camera and partially lose audio — the two things this project is made of. The bootloader stays unlockable if this is revisited. |

### How it hears him

| | |
|---|---|
| **Tap to talk** ✅ | The whole face is the button. *Chosen:* completely reliable, no battery cost, and the listening state is never ambiguous on a device with a mic pointed at the desk. |
| **Wake word** | Deferred, not rejected. Best feel. Picovoice Porcupine must be confirmed to ship an `armeabi-v7a` build before it is promised. |
| **Camera-triggered** | *Rejected as the primary:* you can only speak in windows the device offers. |

The reasoning for starting with tap: the valuable half of this thing is the half that speaks *first*. Reminders, check-ins and nagging need no microphone at all. Listening is an upgrade, not a dependency.

### The face

Chosen from rendered options:

- **Eyes: pills.** Tall rounded bars. Most readable at desk distance and
  the easiest to deform into moods.
- **Background: true complement.** The field behind the eyes is the
  opposite hue, at 6–10% lightness. Green eyes on deep plum, orange on
  deep teal.

Rejected: round eyes with lids (more creaturely, less legible), stroke
arcs (coolest, least range), tonal backgrounds (whole panel carries the
mood — he preferred the separation), fixed charcoal (gives up colour).

## The design

### Shape

```
          ┌─────────────────────────────────────────┐
          │  TABLET  (Kotlin, Device Owner kiosk)   │
          │                                         │
          │  face · mic · speaker · camera · alarms │
          └───────────────┬─────────────────────────┘
                          │ Wi-Fi, HTTPS + Realtime
          ┌───────────────┴─────────────────────────┐
          │  SUPABASE                               │
          │  postgres · edge functions (the agent)  │
          │  pg_cron · realtime · pgvector          │
          └───────────────┬─────────────────────────┘
                          │
                   ┌──────┴──────┐
                   │   LLM API   │  tool-calling loop
                   └─────────────┘
```

The tablet never holds a secret and never calls the LLM. It posts
events and renders what comes back.

### Two loops

This is the core of the thing.

```
1.  Ahmed taps and speaks   → STT → agent turn → tools → speech
2.  Something happens       → event → agent turn → maybe speech
```

Loop 2 is what makes it a companion rather than a voice assistant.
Events: a reminder came due, he arrived, he left, he has been sitting
there 100 minutes, a task has gone untouched for days, it is 2am again.

**Loop 2 needs a governor.** Before speaking unprompted the agent must
pass a gate: has it spoken in the last N minutes, has it already made
this observation today, is this worth interrupting for. The `said_log`
table exists for exactly this. Default to silence.

### Data model (Supabase)

```
profile          name, role, working hours, projects, standing facts
tasks            title, status, due, project, created_at, done_at
reminders        text, due_at, fired_at, recurring, source
presence_events  at, present, duration
memories         distilled fact, category, embedding (pgvector)
conversation     role, content, at
said_log         what it said, when, why — so it never repeats a joke
```

`memories` is deliberately separate from `conversation`. Carrying raw
transcript into every prompt gets expensive; distilled facts stay cheap
to recall semantically.

### Tools

Fuzzy-matched by title throughout, because everything arrives by voice.

**Tasks** — `list_tasks(filter)`, `add_task(title, due?)`,
`complete_task(title)`, `uncomplete_task(title)`, `delete_task(title)`

**Time** — `set_reminder(text, when)`, `start_timer(minutes, label)`,
`list_reminders()`, `cancel_reminder(which)`

**Memory** — `remember(fact, category)`, `recall(query)`,
`update_profile(field, value)`

**Expression** — `speak(text, mood)`, `nag(task, when)`

**Context is not a tool.** Time of day, presence, how long he has been
there, tablet battery, what it last said — injected into every prompt
automatically. Cheaper than a tool call, and the agent cannot forget to
look.

### Reminders must be unmissable

A reminder that only fires when the tablet happens to be awake is not a
reminder. Both paths, always:

- **Durable:** a row with a `due_at`. `pg_cron` sweeps every minute and
  Supabase Realtime pushes to the tablet.
- **Offline backstop:** the tablet also sets a local Android alarm,
  which fires without network and can wake the device.

Whichever arrives first wins; the other is dropped by id.

### Presence

Front camera, on-device face **detection** — not recognition. ML Kit's
bundled model, so no Play account is needed after the reset.

Only `{present: bool, at: timestamp}` leaves the tablet. No frame, no
descriptor, no identity. This holds even though it is his own database:
it keeps the blast radius of the whole project at zero if anything
leaks.

Presence transitions become events for loop 2. Sustained presence
("90 minutes without moving") is also an event.

### The face

- Two pill eyes, blinking at slightly different intervals so it does not
  read as mechanical.
- Background is the complement of the current mood colour, 6–10%
  lightness, with a soft vignette so the face sits in a pool of its own
  light.
- **Mood is shape plus colour, never colour alone.** Sad and sleepy are
  both cool-toned and would be indistinguishable otherwise; they differ
  in lid height and droop.
- Moods: happy, sad, frustrated, loving, smug, sleepy, plus neutral and
  listening. Smug was added because the personality demands it.
- **On boot: eyes shut, then opening.** This is the first thing anyone
  sees it do and should be unhurried — roughly 2 seconds, with a slight
  overshoot before settling.
- The eyes do **not** track him.
- A visible, unambiguous **listening** state whenever the mic is live.

### Voice

- **eSpeak-NG bundled in the APK** as the offline baseline. Fully
  offline, tiny, armv7 builds exist, and its robotic quality suits this
  character rather than fighting it.
- **A cloud TTS on top** when the network is there, for a real
  performance. Falls back to eSpeak silently when it is not.
- Pitch and rate carry mood: low and slow for menace, clipped for
  irritation.
- To protect comic timing: stream the LLM response and begin speaking at
  the first sentence boundary rather than waiting for the full reply.

### Personality

A system prompt, versioned in the repo, not scattered in code.

The design rule that matters: **the cruelty must be evidence-based.**
The agent has access to his tasks, his history, the time, how long he
has been sitting there, what he abandoned. Jokes should cite that
material. A joke it cannot substantiate should not be made.

It is mean to Ahmed, by his explicit request, and about his own habits
and data. It does not get mean about other people, and it does not use
the memory store as ammunition against anyone but him.

## Operating decisions

Settled during review. These are my calls, not Ahmed's — correct any of
them.

### What the tablet is trusted with

The anti-goal is "no secrets on the device". Precisely: **no provider
keys and no service-role key.** The tablet does hold a credential, but a
narrow, revocable one.

- At provisioning the device gets its **own Supabase Auth identity**.
- Its refresh token lives in the **Android Keystore**, hardware-backed
  where the device offers it.
- **RLS** lets that identity read its own profile, tasks and reminders,
  and insert `presence_events`. It cannot read raw `memories`, cannot
  read `said_log`, and cannot reach any other row.
- **Edge Functions verify the JWT.** The LLM key and any cloud TTS key
  exist only as Edge Function secrets and never travel to the device.
- **Revocation** is disabling that auth user. The tablet goes inert
  within one refresh cycle. That is the answer to "someone picked it up".

### Screen, sleep and burn-in

The screen is **never fully off while powered**, because presence
detection and the arrival ambush both depend on it being awake.

- Present → full brightness.
- Absent 10 minutes → dim, eyes sleepy.
- Night hours → very dim.
- Camera samples **every few seconds, not every frame**. Continuous
  capture would cook a 2016 SoC and the battery with it.

**Burn-in is a real risk** on a ten-year-old panel showing two bright
shapes in fixed positions for months. Mitigations, all cheap:

- The whole face **drifts a few pixels** on a slow cycle.
- The background complement changes with mood, moving the load around.
- Nothing static is held at identical pixels for hours.

### With no network

The tablet keeps a **local mirror** (Room) of tasks and reminders as a
read-through cache. Writes queue and reconcile on reconnect; last write
wins per row, by server timestamp.

Reminders already fire regardless, via the local alarm backstop.

The **agent itself is simply unavailable**, and says so in character
with a small set of canned offline lines and a specific sulking face. It
does not pretend to be thinking. Faking it would be worse than admitting
it.

### Getting back in after lockdown

Device Owner with lock task means no Settings and no launcher, so
maintenance has to be built in:

- A **hidden maintenance screen** — long-press a corner for five
  seconds, then a PIN. Offers Wi-Fi configuration (Device Owner can set
  Wi-Fi programmatically), an update trigger, log export, and an
  exit-kiosk toggle.
- **ADB over TCP** stays enabled on the home network through
  development. Whether it survives into daily use is decided at
  provisioning.
- **Self-update**: APK pulled from Supabase Storage on a schedule and
  installed silently, which Device Owner permits.

Without this, a changed router password bricks the thing until a
factory reset.

### The battery

The cell is ten years old and this design holds it at 100% permanently
on a desk. **Check it for swelling before committing the device** — a
puffed cell is worth catching now rather than discovering under a
cracked screen later. If it is already swollen, this project should move
to a different tablet.

### The laptop deck does not use Supabase

Answering a reviewer's question: the deck built earlier stores its tasks
in a local JSON file on the laptop, not in Supabase. So there is no
existing schema to migrate and no collision — this is greenfield.

## What is left to the builder

- Exact mood → (hue, eye geometry) table. The six rendered are a
  starting point, not a spec.
- Choice of LLM API and model. Needs tool-calling and streaming.
- Choice of cloud TTS, if any.
- STT: on-device Android `SpeechRecognizer` versus a cloud transcription
  API. Latency and accuracy trade-off, measurable once the shell exists.
- The governor's thresholds — how often is too often. Needs tuning
  against real use, not guessing.
- Nothing further. The remaining open items are listed under Non-goals
  and Operating decisions.

## Risks

| Risk | Handling |
|---|---|
| Device Owner fails on 8.1 or the reset path goes wrong | Verify `dpm set-device-owner` on the device before committing to the wipe |
| eSpeak-NG has no usable armv7 build | Verify before relying on it; cloud TTS covers the gap |
| ML Kit bundled detection too slow on a 2016 SoC | Sample at low frequency (every few seconds, not every frame) |
| Build toolchain under emulation too slow to iterate | Measure early; a first build will tell us |
| Latency makes the comedy fall flat | Stream and speak on first sentence; measure end-to-end before tuning anything else |
| It becomes annoying and gets unplugged | The governor is a first-class feature, not a polish item |
