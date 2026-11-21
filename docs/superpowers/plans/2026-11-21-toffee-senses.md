# Toffee Senses Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the tablet a mouth, ears and eyes — it speaks every line it shows, listens when tapped, notices when Ahmed is at the desk — and a way to update itself without a cable.

**Architecture:** Everything new sits behind a pure seam that `Brain` or a pure policy object owns, with the Android class as a thin adapter. `Voice`, `Ears`, `Presence` and `Install` are decided in unit-tested Kotlin with no Android in it; `AndroidVoice`, `DeviceEars`, `CloudEars`, `CameraEye` and `Updater` are the adapters. The backend gains exactly one function (`/listen`) and one table (`app_release`); everything else Plan 3 needs — `presence_events`, the `arrived`/`left`/`still_here` event kinds, `Ctx.present` — already exists and is deployed.

**Tech Stack:** Kotlin, Android API 27 (`armeabi-v7a` only), `android.speech.tts.TextToSpeech`, `android.speech.SpeechRecognizer`, `AudioRecord`, CameraX 1.3.4, ML Kit `face-detection:16.1.7` (bundled model), Room, OkHttp, Deno/Supabase Edge Functions, OpenAI `gpt-4o-mini-transcribe`.

**Spec:** `docs/superpowers/specs/2026-11-20-toffee-design.md`

## Global Constraints

Copied from the spec. Every task's requirements include these.

- **Budget: zero.** "Everything runs on free tiers. The single possible exception is the LLM, and only after free options are exhausted."
- **No provider keys on the device.** "The LLM key and any cloud TTS key exist only as Edge Function secrets and never travel to the device." The APK may carry `SUPABASE_URL` and `SUPABASE_PUBLISHABLE_KEY` and nothing else — enforced by the `SAFE_KEYS` gate in `app/build.gradle.kts`.
- **No frame ever leaves the tablet.** "Only `{present: bool, at: timestamp}` leaves the tablet. No frame, no descriptor, no identity."
- **Presence is detection, not recognition.** "No face *recognition*. Presence is a boolean, not an identity."
- **32-bit userspace.** `abiFilters += "armeabi-v7a"`. Anything shipping arm64-only native code will not run.
- **minSdk 27, compileSdk 34, AGP 8.5.2.** Do not raise `compileSdk`: AGP 8.5 does not support 35, and raising it drags the whole toolchain.
- **The camera samples every few seconds, not every frame.** "Continuous capture would cook a 2016 SoC and the battery with it."
- **The screen is never fully off while powered.**
- **Mood is shape plus colour, never colour alone** — and now pitch and rate too: "low and slow for menace, clipped for irritation."
- **Nothing in the app throws at the user.** A failure becomes a face and a line, never a crash or a stack trace. `Reply.offline` is the existing precedent.

## Review Focus

Five things the spec implies, that no task's happy path exercises, and that would bite Ahmed on a tablet with no Settings app and no launcher. Each line's test is attached to the task that owns the code.

1. **`RECORD_AUDIO` or `CAMERA` is not granted.** On API 27 these are runtime permissions, and in a kiosk there is no Settings app to grant them from — a denied mic is permanent. Expected: as Device Owner the app grants them to itself at startup; if it is not Device Owner and the grant fails, the face says so in words rather than tapping silently for ever. *(Tasks 2 and 4.)*
2. **No TTS engine, or `onInit` fails, or the language is unavailable.** The tablet goes mute with no sign that anything is wrong. Expected: captions keep working exactly as they do today, the failure is said once in the caption, and nothing crashes. *(Task 1.)*
3. **He taps and says nothing, or the recogniser returns empty.** Expected: no `/turn` is sent, no LLM money is spent, and the face does not sit in LISTENING for ever — it returns to neutral within the listen timeout. *(Task 2.)*
4. **The detector flaps.** He leans out of frame, turns to the other monitor, looks down at the keyboard. Naive sampling fires `left` then `arrived`, spends two LLM calls and ambushes him twice in a minute. Expected: arriving and leaving have different, asymmetric thresholds, and a single empty frame mid-presence changes nothing. *(Task 4.)*
5. **A bad APK reaches the installer.** A truncated download, a mismatched hash, a `versionCode` that goes backwards, or a plain-HTTP URL. On a device with no USB access this is a brick. Expected: every one of those is refused before `PackageInstaller` is opened. *(Task 5.)*

## File Structure

**Create:**

| File | Responsibility |
|---|---|
| `app/src/main/java/dev/toffee/companion/voice/Prosody.kt` | mood → (pitch, rate). Pure. |
| `app/src/main/java/dev/toffee/companion/voice/Voice.kt` | `interface Voice` + a silent default. Pure. |
| `app/src/main/java/dev/toffee/companion/voice/AndroidVoice.kt` | `TextToSpeech` adapter. |
| `app/src/main/java/dev/toffee/companion/voice/Ears.kt` | `interface Ears`, `sealed class Heard`. Pure. |
| `app/src/main/java/dev/toffee/companion/voice/DeviceEars.kt` | `SpeechRecognizer` adapter. |
| `app/src/main/java/dev/toffee/companion/voice/Vad.kt` | when he has stopped talking. Pure. |
| `app/src/main/java/dev/toffee/companion/voice/Wav.kt` | 16-bit mono PCM → RIFF header. Pure. |
| `app/src/main/java/dev/toffee/companion/voice/CloudEars.kt` | `AudioRecord` → WAV → `/listen`. |
| `app/src/main/java/dev/toffee/companion/presence/Presence.kt` | hysteresis, transitions, sustained presence. Pure. |
| `app/src/main/java/dev/toffee/companion/presence/CameraEye.kt` | CameraX + ML Kit, throttled. |
| `app/src/main/java/dev/toffee/companion/presence/PresenceReporter.kt` | posts `presence_events`, and nothing else. |
| `app/src/main/java/dev/toffee/companion/update/Release.kt` | `Release`, and the rules that refuse one. Pure. |
| `app/src/main/java/dev/toffee/companion/update/Updater.kt` | fetch, hash, silent install. |
| `supabase/functions/listen/index.ts` | `POST /listen`. |
| `supabase/functions/_shared/listen.ts` | audio → text, never throws. |
| `supabase/migrations/0006_app_release.sql` | the release table and its RLS. |

**Modify:**

| File | Change |
|---|---|
| `app/src/main/java/dev/toffee/companion/Brain.kt` | takes a `Voice`; speaks what it shows; gains `onListening`, `onHeardNothing`, `hush`, `onStillHere`. |
| `app/src/main/java/dev/toffee/companion/MainActivity.kt` | wires voice, ears, camera; tap becomes listen; feeds presence to Brain and to `BrightnessPolicy`. |
| `app/src/main/java/dev/toffee/companion/kiosk/Kiosk.kt` | `grantOwnPermissions` — Device Owner grants itself mic and camera. |
| `app/src/main/java/dev/toffee/companion/kiosk/MaintenanceActivity.kt` | a "Check for update" button. |
| `app/src/main/AndroidManifest.xml` | `RECORD_AUDIO`, `CAMERA`, `REQUEST_INSTALL_PACKAGES`. |
| `app/build.gradle.kts`, `gradle/libs.versions.toml` | CameraX, ML Kit. |

**Test:**

`app/src/test/java/dev/toffee/companion/voice/{ProsodyTest,VadTest,WavTest}.kt`, `.../presence/PresenceTest.kt`, `.../presence/PresenceReporterTest.kt`, `.../update/ReleaseTest.kt`, extensions to `.../BrainTest.kt`; `app/src/androidTest/java/dev/toffee/companion/{VoiceTest,EarsTest,CameraEyeTest}.kt`; `tests/listen.test.ts`.

---

## Rulings already made, which the tasks assume

These settle three places where the spec's letter and the project's evidence have come apart. An implementer must not re-open them; a reviewer should weigh them.

- **TTS is Android's `TextToSpeech`, not eSpeak-NG compiled into the APK.** The spec says "eSpeak-NG bundled in the APK, full stop", reasoning from "No Google account means Google TTS cannot fetch offline voices." But on Android, eSpeak NG *is* a `TextToSpeech` engine — the same API, a different engine package. Writing against `TextToSpeech` therefore costs nothing, keeps the eSpeak option open as a sideload rather than an NDK build, and picks up Samsung's own offline engine (`com.samsung.SMT`), which ships in this ROM, survives a factory reset and needs no Google account. *Cost if wrong:* if the wiped device turns out to have no engine at all, the tablet is mute until an engine APK is sideloaded — recoverable in one `adb install`, where a failed NDK build is not.
- **Both ears get built.** The spec calls `SpeechRecognizer` on API 27 with no Google account "the main open technical risk" and says it "must be tested on the device before the listening half is built". The device is currently off USB and cannot be tested. Building `DeviceEars` and `CloudEars` behind one interface, chosen at runtime, resolves the risk instead of waiting on it. *Cost if wrong:* roughly one extra task's work and a few cents a month of transcription if the device path turns out to work after all.
- **The reply is not streamed to the tablet yet.** The spec asks to "begin speaking at the first sentence boundary". The backend already streams internally, but `/turn` answers with one JSON body, and making it stream means SSE in `turn.ts`, SSE in OkHttp and a flow through `Brain`. The spec also says "measure end-to-end before tuning anything else", so Task 2 measures it and the number decides. *Cost if wrong:* if a tap-to-first-word is over about three seconds the comedy drags, and streaming becomes its own task.

---

### Task 1: A voice

It speaks every line it already shows. Nothing else changes: the caption, the mood and the timing are exactly as they are today, with audio on top.

**Files:**
- Create: `app/src/main/java/dev/toffee/companion/voice/Prosody.kt`
- Create: `app/src/main/java/dev/toffee/companion/voice/Voice.kt`
- Create: `app/src/main/java/dev/toffee/companion/voice/AndroidVoice.kt`
- Modify: `app/src/main/java/dev/toffee/companion/Brain.kt`
- Modify: `app/src/main/java/dev/toffee/companion/MainActivity.kt`
- Test: `app/src/test/java/dev/toffee/companion/voice/ProsodyTest.kt`
- Test: `app/src/test/java/dev/toffee/companion/BrainTest.kt` (extend)
- Test: `app/src/androidTest/java/dev/toffee/companion/VoiceTest.kt`

**Interfaces:**
- Consumes: `Mood` (`dev.toffee.companion.face.Mood`), `Brain.show`, `Reply`.
- Produces:
  - `data class Prosody(val pitch: Float, val rate: Float)` with `companion object { fun forMood(mood: Mood): Prosody }`
  - `interface Voice { fun say(text: String, mood: Mood); fun hush(); val speaking: Boolean }` and `object SilentVoice : Voice` (the default, so every existing `Brain` construction still compiles)
  - `class AndroidVoice(context: Context, private val onFailure: (String) -> Unit) : Voice` with `fun shutdown()`
  - `Brain`'s constructor gains a trailing `private val voice: Voice = SilentVoice`

- [ ] **Step 1: Write the failing prosody tests**

```kotlin
// app/src/test/java/dev/toffee/companion/voice/ProsodyTest.kt
@Test fun smugIsLowerAndSlowerThanNeutral() {
    val smug = Prosody.forMood(Mood.SMUG)
    val neutral = Prosody.forMood(Mood.NEUTRAL)
    assertTrue("menace is low and slow", smug.pitch < neutral.pitch && smug.rate < neutral.rate)
}

@Test fun frustratedIsClipped() {
    assertTrue(Prosody.forMood(Mood.FRUSTRATED).rate > Prosody.forMood(Mood.NEUTRAL).rate)
}

@Test fun sleepyIsTheSlowestThingItSays() {
    val sleepy = Prosody.forMood(Mood.SLEEPY)
    for (m in Mood.values()) assertTrue(m.name, sleepy.rate <= Prosody.forMood(m).rate)
}

@Test fun everyMoodIsSayableByAnEngine() {
    // TextToSpeech clamps outside this range, which would silently flatten
    // the mood rather than fail.
    for (m in Mood.values()) {
        val p = Prosody.forMood(m)
        assertTrue(m.name, p.pitch in 0.5f..2.0f && p.rate in 0.5f..2.0f)
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*ProsodyTest*'`
Expected: FAIL — unresolved reference `Prosody`.

- [ ] **Step 3: Implement `Prosody.forMood(mood: Mood): Prosody`**

An exhaustive `when` over `Mood`. The spec fixes two of the nine: menace is low and slow, irritation is clipped. The rest follow from those two and must satisfy the tests above.

- [ ] **Step 4: Run them and watch them pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*ProsodyTest*'`
Expected: PASS, 4 tests.

- [ ] **Step 5: Write the failing Brain tests**

Add a `RecordingVoice : Voice` to `BrainTest`'s fakes that appends `(text, mood)` to a list.

```kotlin
@Test fun everyLineItShowsIsAlsoSpoken()        // say() → voice.said == listOf(reply.speak to reply.mood)
@Test fun silenceFromTheGovernorIsNotSpoken()   // Reply(speak = null) → voice.said is empty
@Test fun aReminderIsSpokenWithItsOwnWords()    // onReminders → voice.said.single().first contains r.text
@Test fun anInertTabletSaysNothingOutLoud()     // revoked session → caption set, voice.said is empty
@Test fun hushStopsTheVoice()                   // brain.hush() → voice.hushed
```

- [ ] **Step 6: Run them and watch them fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*BrainTest*'`
Expected: FAIL — `Brain` has no `voice` parameter and no `hush`.

- [ ] **Step 7: Add the voice to `Brain`**

`show()` speaks the same `line` it puts on screen, with the same mood, after setting state. `inert()` does not speak — it is a maintenance notice, and `refreshSessionState()` runs on every poll, so speaking it would make a dead tablet repeat itself for ever. `fun hush()` delegates to `voice.hush()`.

- [ ] **Step 8: Run them and watch them pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, whole unit suite green.

- [ ] **Step 9: Implement `AndroidVoice`**

Wraps `TextToSpeech`. Construction is asynchronous: queue `say` calls made before `onInit` and flush on success. On `onInit` failure, on `setLanguage` returning `LANG_MISSING_DATA`/`LANG_NOT_SUPPORTED`, or on `speak` returning `ERROR`, call `onFailure(reason)` **once** and then behave as `SilentVoice` for the rest of the process — Review Focus 2. `say` uses `QUEUE_FLUSH` so a new line interrupts the old one, applies `Prosody.forMood`, and tags the utterance id so `speaking` can be tracked through `UtteranceProgressListener`. Prefer an engine from `TextToSpeech.getEngines()` in order: the system default, then `com.samsung.SMT`, then `com.reecedunn.espeak`, then whatever is first.

- [ ] **Step 10: Wire it in `MainActivity`**

Construct `AndroidVoice(this) { reason -> /* show it in the caption once */ }` before `Brain`, pass it in, and call `shutdown()` from `onDestroy`.

- [ ] **Step 11: Write the device-side test**

```kotlin
// app/src/androidTest/java/dev/toffee/companion/VoiceTest.kt
@Test fun theTabletHasAVoice()        // TextToSpeech.getEngines() is not empty; names them in the failure message
@Test fun itCanSayASentence()         // AndroidVoice.say(...); UtteranceProgressListener reports onDone within 10s
@Test fun aMissingEngineIsReported()  // onFailure is called at most once
```

- [ ] **Step 12: Build the APK and run the device test**

Run: `./gradlew :app:assembleDebug` — Expected: BUILD SUCCESSFUL.
Run: `./gradlew :app:connectedDebugAndroidTest --tests '*VoiceTest*'` — Expected: PASS. **If no device is attached, record in the ledger that this step is owed and continue;** it is the gate before Task 1 can be called verified.

- [ ] **Step 13: Commit**

```bash
git add app/src/main/java/dev/toffee/companion/voice app/src/main/java/dev/toffee/companion/Brain.kt app/src/main/java/dev/toffee/companion/MainActivity.kt app/src/test app/src/androidTest
git commit -m "feat: it says the things it shows"
```

---

### Task 2: Ears, the device's own

Tap the face, it listens, it answers what it heard. Replaces the `TAP_PROMPT` placeholder.

**Files:**
- Create: `app/src/main/java/dev/toffee/companion/voice/Ears.kt`
- Create: `app/src/main/java/dev/toffee/companion/voice/DeviceEars.kt`
- Modify: `app/src/main/java/dev/toffee/companion/Brain.kt`
- Modify: `app/src/main/java/dev/toffee/companion/kiosk/Kiosk.kt`
- Modify: `app/src/main/java/dev/toffee/companion/MainActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/dev/toffee/companion/BrainTest.kt` (extend)
- Test: `app/src/androidTest/java/dev/toffee/companion/EarsTest.kt`

**Interfaces:**
- Consumes: `Voice`, `Brain.say`, `Brain.hush`, `Kiosk.isDeviceOwner`.
- Produces:
  - `sealed class Heard { data class Words(val text: String) : Heard(); object Nothing : Heard(); data class Unavailable(val reason: String) : Heard() }`
  - `interface Ears { fun available(): Boolean; suspend fun listen(): Heard }`
  - `class DeviceEars(private val context: Context) : Ears`, with `const val LISTEN_TIMEOUT_MS = 12_000L`
  - `Brain`: `fun onListening()`, `fun onHeardNothing()`, `fun onCannotHear(reason: String)`
  - `Kiosk`: `fun grantOwnPermissions(activity: Activity, vararg permissions: String): List<String>` returning the permissions it could *not* grant

- [ ] **Step 1: Write the failing Brain tests**

```kotlin
@Test fun whileItIsListeningTheFaceSaysSo()
    // brain.onListening() → state.mood == Mood.LISTENING && state.listening

@Test fun hearingNothingCostsNothing()
    // onListening(); onHeardNothing() → agent was never called, listening == false,
    // mood back to Mood.NEUTRAL

@Test fun aTabletThatCannotHearSaysSoOutLoud()
    // onCannotHear("no recogniser") → a caption is shown AND voice.said is not empty
```

The second is Review Focus 3: a tap that hears nothing must not reach the agent and must not leave the face listening.

- [ ] **Step 2: Run them and watch them fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*BrainTest*'`
Expected: FAIL — unresolved `onListening`.

- [ ] **Step 3: Implement the three methods on `Brain`**

`onListening()` sets `FaceState(mood = LISTENING, line = null, listening = true)`. `onHeardNothing()` returns to `Mood.NEUTRAL` with no line and `listening = false`. `onCannotHear(reason)` goes through the existing `show(...)` so it is spoken as well as shown.

- [ ] **Step 4: Run them and watch them pass**

Run: `./gradlew :app:testDebugUnitTest` — Expected: PASS, whole suite.

- [ ] **Step 5: Implement `DeviceEars`**

`available()` is `SpeechRecognizer.isRecognitionAvailable(context)`. `listen()` runs on `Dispatchers.Main` (the recogniser requires it), wraps a `RecognitionListener` in `suspendCancellableCoroutine`, and is bounded by `withTimeoutOrNull(LISTEN_TIMEOUT_MS)`. Resume exactly once: `onResults` with the top hypothesis → `Words` (or `Nothing` if blank), `onError` → `Nothing` for `ERROR_NO_MATCH`/`ERROR_SPEECH_TIMEOUT` and `Unavailable(...)` for the rest, timeout → `Nothing`. Always `destroy()` the recogniser, including on cancellation.

- [ ] **Step 6: Implement `Kiosk.grantOwnPermissions`**

When `isDeviceOwner`, call `DevicePolicyManager.setPermissionGrantState(admin, packageName, permission, PERMISSION_GRANT_STATE_GRANTED)` for each, then re-check with `checkSelfPermission` and return the ones still denied. When not Device Owner, fall back to `requestPermissions` and return those not yet held. This is Review Focus 1 — in a kiosk there is no Settings app, so a permission the app cannot grant itself is permanent.

- [ ] **Step 7: Add the permission to the manifest**

`<uses-permission android:name="android.permission.RECORD_AUDIO" />`

- [ ] **Step 8: Replace the tap placeholder in `MainActivity`**

On click: if `brain.state.value.listening` or the voice is speaking, `brain.hush()` and return (barge-in). Otherwise grant the permission if needed, `brain.onListening()`, then `when (ears.listen())` → `Words` → `brain.say(text)`; `Nothing` → `brain.onHeardNothing()`; `Unavailable` → `brain.onCannotHear(reason)`. Delete `TAP_PROMPT`.

- [ ] **Step 9: Write the device-side test**

```kotlin
// app/src/androidTest/java/dev/toffee/companion/EarsTest.kt
@Test fun doesThisTabletHaveARecogniser()
    // Asserts nothing either way — it RECORDS the answer in the failure message
    // of an assertTrue(true) so the instrumentation log carries it. This is the
    // spec's "main open technical risk" and the answer decides Task 3's default.

@Test fun listeningToSilenceEndsInNothingNotAHang()
    // Only when available(): listen() with no one talking returns within
    // LISTEN_TIMEOUT_MS + 2s and is Heard.Nothing, never Words("").

@Test fun theMicPermissionCanBeGrantedWithoutSettings()
    // Kiosk.grantOwnPermissions(activity, RECORD_AUDIO) returns an empty list.
```

- [ ] **Step 10: Run the device test and measure the round trip**

Run: `./gradlew :app:connectedDebugAndroidTest --tests '*EarsTest*'` — Expected: PASS.
Then, on the device, time a tap from touch-up to the first spoken word and **write the number in the ledger**. The spec's rule is "measure end-to-end before tuning anything else"; this number is what decides whether the streaming ruling above holds. **If no device is attached, record both as owed.**

- [ ] **Step 11: Commit**

```bash
git add app/src/main/java/dev/toffee/companion/voice app/src/main/java/dev/toffee/companion/kiosk/Kiosk.kt app/src/main/java/dev/toffee/companion/Brain.kt app/src/main/java/dev/toffee/companion/MainActivity.kt app/src/main/AndroidManifest.xml app/src/test app/src/androidTest
git commit -m "feat: tap it and it listens"
```

---

### Task 3: Ears, when the device has none

The fallback that makes Task 2's risk survivable. The tablet records, posts the audio to an Edge Function, and the key stays on the server.

**Files:**
- Create: `app/src/main/java/dev/toffee/companion/voice/Vad.kt`
- Create: `app/src/main/java/dev/toffee/companion/voice/Wav.kt`
- Create: `app/src/main/java/dev/toffee/companion/voice/CloudEars.kt`
- Create: `supabase/functions/_shared/listen.ts`
- Create: `supabase/functions/listen/index.ts`
- Modify: `app/src/main/java/dev/toffee/companion/voice/Ears.kt` (add `Ears.best`)
- Modify: `app/src/main/java/dev/toffee/companion/MainActivity.kt`
- Test: `app/src/test/java/dev/toffee/companion/voice/VadTest.kt`
- Test: `app/src/test/java/dev/toffee/companion/voice/WavTest.kt`
- Test: `tests/listen.test.ts`

**Interfaces:**
- Consumes: `Ears`, `Heard`, `Session`, `ToffeeConfig`, `OkHttpClient`.
- Produces:
  - `class Vad(startRms: Double = 600.0, stopRms: Double = 400.0, hangoverMs: Long = 1_200, maxMs: Long = 10_000)` with `fun offer(rms: Double, atMs: Long): Boolean` — true means stop recording
  - `object Wav { const val SAMPLE_RATE = 16_000; fun header(sampleRate: Int, dataBytes: Int): ByteArray }`
  - `class CloudEars(http: OkHttpClient, baseUrl: String, apiKey: String, session: Session, context: Context) : Ears`
  - `fun bestEars(device: DeviceEars, cloud: CloudEars): Ears` — top-level in `Ears.kt`, so Task 2's `interface Ears` needs no companion object added to it
  - `transcribe(audio: Uint8Array, contentType: string): Promise<string | null>` in `listen.ts`

- [ ] **Step 1: Write the failing WAV tests**

```kotlin
@Test fun theHeaderIsFortyFourBytes()
@Test fun itIsARiffWaveFile()            // bytes 0..3 == "RIFF", 8..11 == "WAVE"
@Test fun itDeclaresSixteenBitMono()     // channels == 1, bitsPerSample == 16
@Test fun theSizesAgreeWithTheData()     // riffSize == dataBytes + 36, dataSize == dataBytes
@Test fun theByteRateFollowsTheSampleRate()  // header(16000, n) → byteRate == 32000
```

Little-endian throughout. These are the tests that catch a header a transcription API silently rejects.

- [ ] **Step 2: Run them and watch them fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*WavTest*'` — Expected: FAIL, unresolved `Wav`.

- [ ] **Step 3: Implement `Wav.header`**

The canonical 44-byte RIFF/WAVE/fmt/data header, PCM format 1, mono, 16-bit, little-endian.

- [ ] **Step 4: Run them and watch them pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*WavTest*'` — Expected: PASS, 5 tests.

- [ ] **Step 5: Write the failing VAD tests**

```kotlin
@Test fun silenceThroughoutStopsAtTheCeiling()
    // offer(0.0, t) every 100ms → false until maxMs, true at maxMs

@Test fun speechThenSilenceStopsAfterTheHangover()
    // loud for 2s, then quiet: false until hangoverMs of quiet have passed, then true

@Test fun aBreathDoesNotEndTheSentence()
    // loud 1s, quiet 500ms (< hangoverMs), loud 1s → never true in that window

@Test fun itNeverStopsBeforeHeHasStarted()
    // quiet 3s then loud: the quiet before the first speech is not a hangover
```

The third is why `Vad` exists rather than a fixed window: a pause mid-sentence must not cut him off.

- [ ] **Step 6: Run them and watch them fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*VadTest*'` — Expected: FAIL, unresolved `Vad`.

- [ ] **Step 7: Implement `Vad.offer`**

Hysteresis: speech has started once `rms >= startRms`; it has ended once `rms < stopRms` has held for `hangoverMs` **since speech started**. Returns true at `maxMs` regardless.

- [ ] **Step 8: Run them and watch them pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*VadTest*'` — Expected: PASS, 4 tests.

- [ ] **Step 9: Write the failing `/listen` tests**

```ts
// tests/listen.test.ts
Deno.test("transcribe returns the text the provider gave")        // stubbed fetch
Deno.test("transcribe returns null rather than throwing on 4xx/5xx")
Deno.test("transcribe never puts the key in the URL")             // asserts on the stubbed Request
Deno.test("the handler refuses a body larger than the cap")       // 413
Deno.test("the handler refuses an empty body")                    // 400
Deno.test("a provider failure still answers 200 with text: null") // the tablet must not see an error
```

- [ ] **Step 10: Run them and watch them fail**

Run: `deno test -A tests/listen.test.ts` — Expected: FAIL, module not found.


- [ ] **Step 11: Implement `listen.ts` and `listen/index.ts`**

`POST /listen` takes raw audio with a `content-type` of `audio/wav`, caps the body at `MAX_AUDIO_BYTES = 2_000_000` (ten seconds of 16 kHz 16-bit mono is 320 KB), and posts it as `multipart/form-data` to `https://api.openai.com/v1/audio/transcriptions` with `model` from `OPENAI_TRANSCRIBE_MODEL`, defaulting to `gpt-4o-mini-transcribe`, and the key in an `authorization` header. Mirrors `llm.ts`: the key is read from `Deno.env`, never logged, never in a URL, and the function never throws at the tablet — a failure is `{ text: null }` with status 200. JWT verification is the platform's, as it is for `/turn` and `/event`.

- [ ] **Step 12: Run them and watch them pass**

Run: `deno test -A` — Expected: PASS, the whole backend suite (107 before this task).

- [ ] **Step 13: Implement `CloudEars` and `bestEars`**

`CloudEars.available()` is true whenever the app holds `RECORD_AUDIO`. `listen()` opens `AudioRecord` at `Wav.SAMPLE_RATE` mono 16-bit, reads into a buffer, computes RMS per block and feeds `Vad.offer`, stops when it says so, prepends `Wav.header`, POSTs to `${baseUrl}/functions/v1/listen`, and maps the answer to `Words`/`Nothing`. Releases the `AudioRecord` on every path. `bestEars(device, cloud)` returns `device` when `device.available()`, else `cloud`.

- [ ] **Step 14: Use it in `MainActivity`**

Replace the `DeviceEars` construction with `bestEars(DeviceEars(this), CloudEars(http, ToffeeConfig.baseUrl, ToffeeConfig.publishableKey, session, this))`.

- [ ] **Step 15: Deploy and verify the function is reachable**

Run: `deno run -A scripts/deploy.ts` — Expected: `listen` listed as deployed.
Run: the suite once more, `./gradlew :app:testDebugUnitTest && deno task test` — Expected: PASS.

- [ ] **Step 16: Commit**

```bash
git add app/src/main/java/dev/toffee/companion/voice app/src/main/java/dev/toffee/companion/MainActivity.kt supabase/functions/listen supabase/functions/_shared/listen.ts app/src/test tests/listen.test.ts
git commit -m "feat: a second pair of ears, for when the first are missing"
```

---

### Task 4: Eyes

It notices him arriving and leaving. A boolean and a timestamp leave the tablet; nothing else ever does.

**Files:**
- Create: `app/src/main/java/dev/toffee/companion/presence/Presence.kt`
- Create: `app/src/main/java/dev/toffee/companion/presence/CameraEye.kt`
- Create: `app/src/main/java/dev/toffee/companion/presence/PresenceReporter.kt`
- Modify: `app/src/main/java/dev/toffee/companion/Brain.kt` (add `onStillHere`)
- Modify: `app/src/main/java/dev/toffee/companion/MainActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`, `app/build.gradle.kts`, `gradle/libs.versions.toml`
- Test: `app/src/test/java/dev/toffee/companion/presence/PresenceTest.kt`
- Test: `app/src/test/java/dev/toffee/companion/presence/PresenceReporterTest.kt`
- Test: `app/src/androidTest/java/dev/toffee/companion/CameraEyeTest.kt`

**Interfaces:**
- Consumes: `Brain.onArrival`, `Brain.onDeparture`, `BrightnessPolicy.levelFor`, `Session`, `Kiosk.grantOwnPermissions`.
- Produces:
  - `class Presence(arriveAfterMs: Long = 6_000, leaveAfterMs: Long = 120_000, stillHereEveryMs: Long = 3_600_000)` with
    `sealed class Change { object Arrived : Change(); data class Left(val forMinutes: Int) : Change(); data class StillHere(val minutes: Int) : Change() }`,
    `fun sample(faces: Int, atMs: Long): Change?`, `val present: Boolean`, `val presentSinceMs: Long?`, `val lastSeenMs: Long?`
  - `class CameraEye(context: Context, owner: LifecycleOwner, everyMs: Long = 4_000, onFaces: (faces: Int, atMs: Long) -> Unit)` with `fun start()`, `fun stop()`
  - `class PresenceReporter(http: OkHttpClient, baseUrl: String, apiKey: String, session: Session)` with `suspend fun report(present: Boolean, atMs: Long, durationS: Int?)`
  - `Brain`: `suspend fun onStillHere(minutes: Int)`

- [ ] **Step 1: Write the failing presence tests**

```kotlin
@Test fun oneGlimpseIsNotAnArrival()
    // sample(1, 0) → null  (arriveAfterMs has not elapsed)

@Test fun sustainedFacesAreAnArrivalExactlyOnce()
    // sample(1, 0); sample(1, 6_000) → Arrived; sample(1, 10_000) → null

@Test fun leaningOutOfFrameIsNotLeaving()        // Review Focus 4
    // arrive; sample(0, t); sample(1, t + 4_000) → null, and present is still true

@Test fun aLongAbsenceIsLeaving()
    // arrive; faces stop; null until leaveAfterMs, then Left(forMinutes)

@Test fun oneEmptyFrameDoesNotResetHowLongHeHasBeenThere()
    // presentSinceMs is unchanged across a single faces == 0 sample

@Test fun anHourAtTheDeskIsWorthMentioning()
    // arrive at 0; sample(1, 3_600_000) → StillHere(60); again at 7_200_000 → StillHere(120)

@Test fun leavingAndComingBackArrivesAgain()
    // Arrived → Left → Arrived, and presentSinceMs restarts
```

The third and fifth are the flapping guard: the cost of getting them wrong is two LLM calls and two interruptions every time he looks at his keyboard.

- [ ] **Step 2: Run them and watch them fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*PresenceTest*'` — Expected: FAIL, unresolved `Presence`.

- [ ] **Step 3: Implement `Presence.sample`**

Asymmetric thresholds: a face must be seen continuously for `arriveAfterMs` before `Arrived`; no face must be seen for `leaveAfterMs` before `Left`. While present, emit `StillHere(minutes)` each time another `stillHereEveryMs` of unbroken presence has passed. Returns at most one `Change` per call.

- [ ] **Step 4: Run them and watch them pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*PresenceTest*'` — Expected: PASS, 7 tests.

- [ ] **Step 5: Write the failing reporter test**

```kotlin
// MockWebServer. This is the test that pins the privacy rule in place.
@Test fun onlyAFlagAndATimeEverLeaveTheTablet() {
    reporter.report(present = true, atMs = 1_700_000_000_000, durationS = 42)
    val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
    assertEquals(setOf("present", "at", "duration_s"), body.keys)
}

@Test fun itPostsToPresenceEvents()   // path == "/rest/v1/presence_events"
@Test fun aRevokedTabletReportsNothing()
```

- [ ] **Step 6: Run it, watch it fail, implement `PresenceReporter`, watch it pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*PresenceReporterTest*'`
Expected: FAIL (unresolved), then PASS, 3 tests. A PostgREST insert with `Prefer: return=minimal` — RLS grants the device insert on `presence_events` and no select, so asking for a representation back would be denied and would look like a failed insert.

- [ ] **Step 7: Add the camera dependencies**

In `gradle/libs.versions.toml`: `camerax = "1.3.4"` (the last line that does not demand `compileSdk 35`), `mlkitFace = "16.1.7"`, with `androidx-camera-core`, `androidx-camera-camera2`, `androidx-camera-lifecycle` and `com.google.mlkit:face-detection`. Add them to `app/build.gradle.kts` and `<uses-permission android:name="android.permission.CAMERA" />` to the manifest.

- [ ] **Step 8: Verify the ABI before writing a line of camera code**

Run: `./gradlew :app:assembleDebug` then list the APK's native libraries:
```bash
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep 'lib/'
```
Expected: entries under `lib/armeabi-v7a/` for the ML Kit face detector, and **no other ABI**. If `armeabi-v7a` is absent, the bundled model cannot run on this device: stop, record it in the ledger, and fall back to `play-services-mlkit-face-detection` — which needs Play Services, which the spec was avoiding. This is a Global Constraint, and it is cheaper to find out here than on the device.

- [ ] **Step 9: Implement `CameraEye`**

CameraX, `DEFAULT_FRONT_CAMERA`, `ImageAnalysis` with `STRATEGY_KEEP_ONLY_LATEST` and a small target resolution. Bind once and keep it bound — reopening the camera every few seconds costs more than it saves — but inside the analyzer, close any frame arriving less than `everyMs` after the last analysed one without touching it. ML Kit `FaceDetectorOptions` in `PERFORMANCE_MODE_FAST`, no landmarks, no classification, no tracking, `setMinFaceSize(0.2f)`. Call `onFaces(faces.size, System.currentTimeMillis())`. The `ImageProxy` is closed on every path including failure, and no frame, bitmap or descriptor is stored, logged or sent.

- [ ] **Step 10: Add `Brain.onStillHere` and wire it all up in `MainActivity`**

`onStillHere(minutes)` is `ask { agent.event("still_here", mapOf("minutes" to minutes)) }` — the backend already has that kind and buckets it by the hour. In `MainActivity`: grant `CAMERA` via `Kiosk.grantOwnPermissions`; if it is still denied, do not start the eye and leave presence unknown (Review Focus 1 and 4 — the brightness ticker keeps its current clock-only behaviour rather than dimming on a false absence). Otherwise start `CameraEye`, feed every sample into `Presence`, and on each `Change` call the matching `Brain` method and `PresenceReporter.report`. Replace the brightness ticker's placeholder arguments with `presence.presentSinceMs` and `presence.lastSeenMs`.

- [ ] **Step 11: Write and run the device-side test**

```kotlin
// app/src/androidTest/java/dev/toffee/companion/CameraEyeTest.kt
@Test fun thereIsAFrontCamera()
@Test fun theCameraPermissionCanBeGrantedWithoutSettings()
@Test fun itDeliversSamplesAtRoughlyTheRequestedRate()
    // start(); collect for 20s; expect 4..7 callbacks at everyMs = 4_000
```

Run: `./gradlew :app:connectedDebugAndroidTest --tests '*CameraEyeTest*'` — Expected: PASS. Then leave it running for ten minutes and read `dumpsys battery` and the thermal zone, and **record the temperature in the ledger**: the spec's stated risk is that continuous capture cooks a 2016 SoC, and `everyMs` is the dial that answers it. **If no device is attached, record both as owed.**

- [ ] **Step 12: Commit**

```bash
git add app/src/main/java/dev/toffee/companion/presence app/src/main/java/dev/toffee/companion/Brain.kt app/src/main/java/dev/toffee/companion/MainActivity.kt app/src/main/AndroidManifest.xml app/build.gradle.kts gradle/libs.versions.toml app/src/test app/src/androidTest
git commit -m "feat: it notices when he sits down"
```

---

### Task 5: A way in without a cable

Device Owner can install silently. That is the only thing standing between a change and a factory reset once the tablet lives on a shelf.

**Files:**
- Create: `supabase/migrations/0006_app_release.sql`
- Create: `app/src/main/java/dev/toffee/companion/update/Release.kt`
- Create: `app/src/main/java/dev/toffee/companion/update/Updater.kt`
- Modify: `app/src/main/java/dev/toffee/companion/kiosk/MaintenanceActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/dev/toffee/companion/update/ReleaseTest.kt`

**Interfaces:**
- Consumes: `Session`, `ToffeeConfig`, `Kiosk.isDeviceOwner`.
- Produces:
  - `data class Release(val versionCode: Int, val url: String, val sha256: String)`
  - `object Install { fun refuse(current: Int, release: Release, downloadedSha256: String, bytes: Long): String? }` — the reason, or null when it may be installed
  - `class Updater(http: OkHttpClient, baseUrl: String, apiKey: String, session: Session, context: Context)` with `suspend fun check(): String` returning a line fit to show on the maintenance screen

- [ ] **Step 1: Write the failing install-gate tests**

```kotlin
private val good = Release(2, "https://x/app.apk", "ab12")

@Test fun aNewerSignedCorrectBuildIsAllowed()      // refuse(1, good, "ab12", 9_000_000) == null
@Test fun theSameVersionIsRefused()                // refuse(2, good, "ab12", 9_000_000) != null
@Test fun anOlderVersionIsRefused()                // refuse(3, ...) != null
@Test fun aMismatchedHashIsRefused()               // refuse(1, good, "cd34", 9_000_000) != null
@Test fun theHashComparisonIgnoresCase()           // refuse(1, good, "AB12", 9_000_000) == null
@Test fun anEmptyDownloadIsRefused()               // bytes == 0 → != null
@Test fun aPlainHttpUrlIsRefused()                 // url = "http://..." → != null
```

This is Review Focus 5 in full. Each rule gets its own test, so mutating any one of them turns a test red.

- [ ] **Step 2: Run them and watch them fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*ReleaseTest*'` — Expected: FAIL, unresolved `Install`.

- [ ] **Step 3: Implement `Install.refuse`**

Four rules, each returning its own sentence: `versionCode` must be strictly greater than `current`; `url` must start with `https://`; `bytes` must be greater than zero; `downloadedSha256` must equal `release.sha256`, compared case-insensitively.

- [ ] **Step 4: Run them and watch them pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*ReleaseTest*'` — Expected: PASS, 7 tests.

- [ ] **Step 5: Write and apply the migration**

```sql
-- supabase/migrations/0006_app_release.sql
create table app_release (
  id           serial primary key,
  version_code integer not null,
  url          text not null,
  sha256       text not null,
  notes        text,
  active       boolean not null default false,
  created_at   timestamptz not null default now()
);
create unique index app_release_one_active on app_release (active) where active;
alter table app_release enable row level security;
create policy device_select_app_release on app_release
  for select to authenticated
  using ((select auth.jwt()) -> 'app_metadata' ->> 'device' = 'true' and active);
```

The partial unique index is what makes "which build is live" a single fact rather than a race. The device may read only the active row and may never write.

Run: the repo's migration script. Expected: `0006_app_release` applied, and `select` as the device identity returns zero rows (nothing is active yet) rather than an error.

- [ ] **Step 6: Implement `Updater.check`**

Reads the active `app_release` row through PostgREST. If there is none, or `Install.refuse` rejects it on `versionCode` alone, answer "Nothing new." Otherwise download to `context.cacheDir`, hash with `MessageDigest.getInstance("SHA-256")`, call `Install.refuse` with the real hash and byte count, and on refusal delete the file and return the reason verbatim. Only on `null` open a `PackageInstaller.Session`, write the APK, and commit it — which Device Owner performs without a prompt. If not Device Owner, return "Not device owner; install it over USB." Never throws.

- [ ] **Step 7: Add the button and the permission**

A "Check for update" button in `MaintenanceActivity`'s tools block, behind the existing PIN, putting `Updater.check()`'s answer into the `status` TextView. Add `<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />`. Manual-only for now: an unattended auto-update on a device with no USB access can brick it, and the row's `active` flag is the remote switch that makes a scheduled check safe to add later.

- [ ] **Step 8: Run the whole suite**

Run: `./gradlew :app:testDebugUnitTest && deno test -A` — Expected: PASS, both suites green.

- [ ] **Step 9: Verify on the device**

Build, install, open Maintenance, press Check for update. Expected: "Nothing new." Then insert an `app_release` row pointing at a build with a higher `versionCode` and press it again. Expected: the tablet installs it silently and relaunches on the new build. **If no device is attached, record as owed** — this is the one task whose value is entirely in the device half.

- [ ] **Step 10: Commit**

```bash
git add supabase/migrations/0006_app_release.sql app/src/main/java/dev/toffee/companion/update app/src/main/java/dev/toffee/companion/kiosk/MaintenanceActivity.kt app/src/main/AndroidManifest.xml app/src/test
git commit -m "feat: it can update itself"
```

---

## Verification that needs the tablet

Several steps above are marked **owed** if no device is attached. Collected here so none is lost: Task 1 step 12 (a voice exists and speaks), Task 2 steps 9–10 (whether `SpeechRecognizer` exists on this ROM, and the tap-to-first-word measurement), Task 4 step 11 (front camera, sample rate, temperature after ten minutes), Task 5 step 9 (a silent install actually lands). Plan 3 is not verified until every one of them has run on the tablet.
