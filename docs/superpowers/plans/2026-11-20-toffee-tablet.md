# Toffee tablet shell — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the Galaxy Tab A (SM-T585) into a single-purpose device that
shows Toffee's face, talks to the deployed Supabase backend, and never
shows Android again.

**Architecture:** One Kotlin Android app. A custom `View` draws the face —
two pill eyes on a complement-hued field — and a small HTTP layer posts to
the already-deployed `/turn` and `/event` Edge Functions using the device's
own Supabase Auth identity. Reminders arrive by two independent paths (a
20-second poll and a local `AlarmManager` alarm) and are de-duplicated by
id. Device Owner plus lock task plus a HOME intent-filter makes the app the
only thing the tablet can be.

**Tech Stack:** Kotlin, Android Views (no Compose), OkHttp 4.12,
kotlinx.serialization, Room, AndroidX. JUnit 4 + OkHttp MockWebServer for
unit tests. Deno for the provisioning script, matching the backend's
existing `scripts/`.

**Spec:** `docs/superpowers/specs/2026-11-20-toffee-design.md`
**Visual spec:** `docs/design/face-style.html`, `docs/design/face-background.html`, `docs/design/README.md`

---

## Global Constraints

- **Package:** `dev.toffee.companion`. Application id the same.
- **`minSdk = 27`, `targetSdk = 34`, `compileSdk = 34`.** The device is
  Android 8.1.0, API 27. API 26+ has `java.time`, so no core-library
  desugaring is needed; do not add it.
- **ABI: `armeabi-v7a` only.** No dependency that ships `arm64-v8a`-only
  native code. The device has 1.86 GB RAM, ~780 MB free.
- **Java 17 toolchain.** AGP 8.5.x, Gradle 8.7.
- **Views, not Compose.** The face is a custom `View` drawing to a `Canvas`.
- **No Compose, no supabase-kt, no Ktor.** The dependency set is exactly:
  `androidx.core:core-ktx`, `androidx.appcompat:appcompat`,
  `androidx.security:security-crypto`, `androidx.room:room-runtime` +
  `room-ktx` + `room-compiler` (ksp),
  `org.jetbrains.kotlinx:kotlinx-coroutines-android`,
  `org.jetbrains.kotlinx:kotlinx-serialization-json`,
  `com.squareup.okhttp3:okhttp:4.12.0`. Tests add
  `com.squareup.okhttp3:mockwebserver:4.12.0`, `junit:junit:4.13.2`,
  `kotlinx-coroutines-test`. Adding anything else is a ruling that goes in
  the ledger.
- **Secrets:** the APK may compile in exactly two values, both read from the
  repo-root `.env` by `app/build.gradle.kts`: `SUPABASE_URL` and
  `SUPABASE_PUBLISHABLE_KEY`. No provider key, no `sb_secret_*`, no
  service-role key, no `SUPABASE_ACCESS_TOKEN`, no `GEMINI_*` may ever reach
  a `BuildConfig` field or a resource. The device's own credentials are
  typed in once at setup and stored encrypted; they are never in the APK.
- **The face must match the approved visual spec.** `docs/design/` is the
  spec, not a sketch. Mood is **shape plus colour, never colour alone**. The
  two eyes **never blink in sync**.
- **Zero budget.** Free tiers only. No paid service, no paid Android
  tooling.
- **Timezone `Africa/Cairo`**, matching the backend.
- **The tablet is connected over USB.** adb lives at
  `C:\Users\ahmed\AppData\Local\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe`.
  It is not on the shell PATH; call it by full path or set `ANDROID_HOME`.
- **No factory reset and no `dpm set-device-owner` without Ahmed's explicit
  go-ahead in conversation.** Task 6 stops at that line.

### Rulings already made, carried into this plan

- **No Realtime in Plan 2.** The backend broadcasts on channel `toffee`, but
  speaking the Phoenix channel protocol by hand, or pulling in supabase-kt
  for it, is the largest dependency risk on an API-27 device. The spec's
  actual requirement is that reminders are unmissable via two independent
  paths; a 20-second poll plus the local alarm satisfies that. `ReminderInbox`
  is the de-duplication seam, so a Realtime source can be added later as a
  third `Source` with no other change. *Cost if wrong:* up to 20 seconds of
  latency on a server-fired reminder.
- **The mirror holds reminders only, and is read-only.** The spec asks for a
  Room mirror of tasks *and* reminders. Reminders earn it immediately: the
  local alarm backstop cannot schedule what it has not stored. Tasks do not —
  nothing in this plan displays a task list, because the agent speaks tasks
  rather than showing them, so a task cache would be written and never read.
  It arrives with the first screen that needs it. No write queue either: the
  tablet mutates nothing here; the agent does all of it server-side through
  its tools. *Cost if wrong:* a task screen in Plan 3 adds the table and the
  queue then, which is where its tests would live anyway.
- **Self-update is deferred to the provisioning session.** The spec wants the
  APK pulled from Supabase Storage and installed silently, which only Device
  Owner permits and only matters once the device is locked down. Task 6 stops
  at the one-way door, so this lands with it, not before. Until then the
  update path is `adb install -r`, which is what every task here uses.
  *Cost if wrong:* if Ahmed locks the device down and self-update is not
  built, a new build needs the maintenance screen's exit-kiosk toggle.
- **The face is a custom Canvas `View`, not a WebView.** Lighter for an
  always-on screen, exact control of the wake and blink timing, and pixel
  drift is a translate. The HTML files remain the visual spec.
- **Voice is Plan 3.** This plan renders the reply as text under the face.
  No TTS, no STT, no camera.

---

## Review Focus

Five things the spec implies that no single task's happy path exercises.
Each has a test, named below, in the task that owns the code.

1. **Wi-Fi drops mid-turn.** The app must show the offline face and a canned
   line, must not hang or crash, and must recover on the next request
   without a restart. → Task 4, `turn returns the offline reply when the network is gone`
   and `the client recovers on the next request after an outage`.
2. **The device identity is revoked** — Ahmed disables the auth user, which
   is the spec's answer to "someone picked it up". The app must go inert
   with a clear state, not retry forever. → Task 4,
   `a refresh rejected with 400 puts the session in Revoked and stops retrying`.
3. **Both paths deliver the same reminder.** Poll and alarm both fire for
   one id; it must be announced exactly once, and that must survive a
   process restart. → Task 5, `the same reminder from both sources is announced once`
   and `an announced reminder stays announced across a restart`.
4. **The tablet reboots at 3am on mains.** It must come back into the kiosk,
   awake, signed in, with nobody touching it. → Task 6, Step 6's `adb reboot`
   verification. This one has no unit test on purpose: a `BroadcastReceiver`
   firing on real `BOOT_COMPLETED` cannot be proven in a JVM test without
   pulling in Robolectric, and a test that stubs the receiver would prove
   only that the stub works. The device is connected; reboot it and look.
5. **The backend returns its degraded fallback.** Observed in production:
   when the model is unavailable the reply has no `mood`, or a mood string
   the app does not know. The face must render something sane. → Task 4,
   `an unknown or missing mood parses as NEUTRAL`.

---

## File Structure

```
settings.gradle.kts
build.gradle.kts
gradle/libs.versions.toml
gradle.properties
app/
  build.gradle.kts                      reads .env, emits the two safe BuildConfig fields
  src/main/AndroidManifest.xml
  src/main/res/xml/device_admin.xml
  src/main/java/dev/toffee/companion/
    ToffeeConfig.kt                     base URL, functions URL, publishable key
    MainActivity.kt                     the one screen: face + reply line
    SetupActivity.kt                    one-time device sign-in
    face/
      Mood.kt                           the enum and its geometry table
      Palette.kt                        mood colour, complement background
      Wake.kt                           the wake curve
      Blink.kt                          the two blink schedulers
      Drift.kt                          burn-in drift
      FaceView.kt                       the custom View
    net/
      Session.kt                        sign-in, refresh, SessionState
      TokenStore.kt                     interface + EncryptedSharedPreferences impl
      ToffeeClient.kt                   /turn and /event
      Reply.kt                          the parsed response
      Offline.kt                        canned offline lines
    reminders/
      Reminder.kt
      ReminderInbox.kt                  de-duplication across sources (pure)
      ReminderPoller.kt                 the 20-second poll
      AlarmScheduler.kt                 AlarmManager + the receiver
      Mirror.kt                         Room database, entities, DAO
    kiosk/
      Kiosk.kt                          lock task, keep-awake, brightness
      BrightnessPolicy.kt               present/absent/night → level (pure)
      ToffeeDeviceAdmin.kt              DeviceAdminReceiver
      BootReceiver.kt
      CornerHold.kt                     the five-second corner hold (pure)
      Pin.kt                            salted hash, verify (pure)
      MaintenanceActivity.kt
  src/test/java/dev/toffee/companion/   JVM unit tests, mirroring the above
scripts/provision-device.ts             creates the device auth identity
tests/provision.test.ts                 live RLS proof for that identity
```

---

### Task 1: Toolchain, skeleton, and an APK that runs on the tablet

The highest-risk task, so it is first: it proves JDK, Android SDK, Gradle
and adb all work on an ARM64 laptop under x64 emulation with Smart App
Control on, and that nothing secret can leak into the APK.

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`, `.gitignore` additions
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/dev/toffee/companion/ToffeeConfig.kt`
- Create: `app/src/main/java/dev/toffee/companion/MainActivity.kt`
- Test: `app/src/test/java/dev/toffee/companion/ToffeeConfigTest.kt`

**Interfaces:**
- Consumes: the repo-root `.env` (already present, gitignored).
- Produces:
  - `object ToffeeConfig { val baseUrl: String; val publishableKey: String; fun functionUrl(slug: String): String; fun restUrl(path: String): String; internal fun join(base: String, path: String): String }`
  - `functionUrl("turn")` → `"<baseUrl>/functions/v1/turn"`; `restUrl("reminders")` → `"<baseUrl>/rest/v1/reminders"`. `baseUrl` never has a trailing slash, whatever `.env` holds.
  - A Gradle build that `./gradlew test` and `./gradlew assembleDebug` both succeed on.

- [ ] **Step 1: Install the toolchain**

```powershell
winget install --id Microsoft.OpenJDK.17 --accept-package-agreements --accept-source-agreements
```

Then install the Android SDK command-line tools (no Android Studio; it is
not needed and is slow under emulation), and with `sdkmanager` accept the
licences and install `platform-tools`, `platforms;android-34`,
`build-tools;34.0.0`. Set `JAVA_HOME` and `ANDROID_HOME` for the session.

Expected: `java -version` prints 17.x; `sdkmanager --list_installed` shows
all three packages.

- [ ] **Step 2: Confirm the tablet is reachable**

Run: `<adb> devices -l`
Expected: one device, state `device` (not `unauthorized`, not `offline`).
If it is not connected, stop and say so — Steps 8–10 cannot run without it.

- [ ] **Step 3: Write the failing test**

`app/src/test/java/dev/toffee/companion/ToffeeConfigTest.kt`:

```kotlin
@Test fun `functionUrl builds the edge function path`() {
    assertEquals("https://gzwgczqktudzorotryva.supabase.co/functions/v1/turn",
                 ToffeeConfig.functionUrl("turn"))
}

@Test fun `a trailing slash in the base url does not double up`() {
    assertEquals("https://x.supabase.co/rest/v1/reminders",
                 ToffeeConfig.join("https://x.supabase.co/", "rest/v1/reminders"))
}

// The anti-goal is "no secrets on the device". This fails the build if a
// secret-shaped value is ever compiled in.
@Test fun `no secret is compiled into the apk`() {
    assertFalse(ToffeeConfig.publishableKey.startsWith("sb_secret_"))
    assertTrue(ToffeeConfig.publishableKey.startsWith("sb_publishable_"))
    val fields = BuildConfig::class.java.declaredFields.map { it.name }
    for (banned in listOf("SERVICE_ROLE", "SECRET", "GEMINI", "ACCESS_TOKEN")) {
        assertFalse("BuildConfig must not carry $banned",
                    fields.any { it.contains(banned) })
    }
}
```

- [ ] **Step 4: Run it to watch it fail**

Run: `./gradlew :app:testDebugUnitTest`
Expected: FAIL — `ToffeeConfig` does not exist. (If Gradle itself fails to
configure, that is this task's real work; fix it and re-run until the
failure is the missing class.)

- [ ] **Step 5: Write the Gradle build**

`app/build.gradle.kts` reads the repo-root `.env`, and for each of
`SUPABASE_URL` and `SUPABASE_PUBLISHABLE_KEY` emits a
`buildConfigField("String", name, "\"$value\"")`. A missing value fails the
build with a message naming it. No other `.env` key is read — not
conditionally, not in a loop over the file. `buildFeatures { buildConfig = true }`.

Set the Global Constraints' sdk levels, the Java 17 toolchain, and
`ndk { abiFilters += "armeabi-v7a" }`.

- [ ] **Step 6: Implement `ToffeeConfig`**

`object ToffeeConfig` exposing `baseUrl` (BuildConfig value with any
trailing slash stripped), `publishableKey`, `fun functionUrl(slug: String): String`,
`fun restUrl(path: String): String`, and the internal
`fun join(base: String, path: String): String` the test calls.

- [ ] **Step 7: Run the test to watch it pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, 3 tests.

- [ ] **Step 8: Build and install on the tablet**

`MainActivity` for this task is a single `TextView` reading
`Toffee — build ok` on a black background, no title bar.

Run: `./gradlew :app:assembleDebug` then
`<adb> install -r app/build/outputs/apk/debug/app-debug.apk` then
`<adb> shell am start -n dev.toffee.companion/.MainActivity`

Expected: `Success`, then `Starting: Intent...` with no `Error`.

- [ ] **Step 9: Screenshot it**

Run: `<adb> exec-out screencap -p > build-check.png` and look at it.
Expected: the tablet shows `Toffee — build ok` on black.

Record the wall-clock time of the clean `assembleDebug` in the ledger — the
spec names "build toolchain under emulation too slow to iterate" as a risk,
and this is the measurement that settles it.

- [ ] **Step 10: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle gradle.properties app .gitignore
git commit -m "feat: android skeleton that builds, installs and keeps secrets out of the apk"
```

---

### Task 2: The device's own Supabase identity

**Files:**
- Create: `scripts/provision-device.ts`
- Test: `tests/provision.test.ts`

**Interfaces:**
- Consumes: `.env` (`SUPABASE_URL`, `SUPABASE_SECRET_KEY`), read the same
  way `scripts/deploy.ts` reads it.
- Produces: a Supabase Auth user whose `app_metadata.device` is the string
  `"true"` — the exact claim migration `0003_rls.sql` gates on. The script
  prints its email and password once, to be typed into the tablet at setup.
  Nothing else consumes this from code.

- [ ] **Step 1: Write the failing test**

`tests/provision.test.ts`, following the live-database style of the existing
backend tests:

```ts
Deno.test("the provisioned identity carries the device claim", async () => {
  const { email, password } = await provision("test");
  const { data } = await anonClient().auth.signInWithPassword({ email, password });
  const claims = decodeJwt(data.session!.access_token);
  assertEquals(claims.app_metadata.device, "true");
});

Deno.test("the device identity can read tasks", /* select returns no error */);

// The spec: "It cannot read raw memories, cannot read said_log".
Deno.test("the device identity cannot read memories", /* data is empty, or error */);
Deno.test("the device identity cannot read said_log", /* data is empty, or error */);

// Provisioning twice must not create a second identity.
Deno.test("provisioning is idempotent", /* same user id both times */);
```

- [ ] **Step 2: Run them to watch them fail**

Run: `deno test -A tests/provision.test.ts`
Expected: FAIL — `provision` is not exported.

- [ ] **Step 3: Implement the script**

`scripts/provision-device.ts` exporting
`async function provision(label = "tablet"): Promise<{ id: string; email: string; password: string }>`
and running it when invoked directly. It uses the service-role client's
`auth.admin` API: look up an existing user by email first and return it
unchanged if found, otherwise create one with
`email_confirm: true` and `app_metadata: { device: "true" }`. The email is
`${label}@toffee.local`; the password is 32 bytes of `crypto.getRandomValues`
base64url-encoded. The header comment states that the printed password is
shown once and is not stored anywhere in the repo.

- [ ] **Step 4: Run the tests to watch them pass**

Run: `deno test -A tests/provision.test.ts`
Expected: PASS, 5 tests.

- [ ] **Step 5: Run the full backend suite**

Run: `deno test -A tests/`
Expected: the 93 existing tests plus these 5, all passing. Any failure goes
in the report by name.

- [ ] **Step 6: Provision the real identity and record it**

Run: `deno run -A scripts/provision-device.ts tablet`
Hand the printed email and password to Ahmed to type into the tablet at
setup. Do not write them to any file in the repo.

- [ ] **Step 7: Commit**

```bash
git add scripts/provision-device.ts tests/provision.test.ts
git commit -m "feat: the tablet gets its own narrow, revocable identity"
```

---

### Task 3: The face

Everything here is a pure function of time and mood except `FaceView`
itself, which does nothing but call them and draw.

**Files:**
- Create: `app/src/main/java/dev/toffee/companion/face/Mood.kt`, `Palette.kt`, `Wake.kt`, `Blink.kt`, `Drift.kt`, `FaceView.kt`
- Test: `app/src/test/java/dev/toffee/companion/face/MoodTest.kt`, `PaletteTest.kt`, `WakeTest.kt`, `BlinkTest.kt`, `DriftTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `enum class Mood(val blinks: Boolean = true) { NEUTRAL, HAPPY, SAD, FRUSTRATED, LOVING, SMUG, SLEEPY, LISTENING(blinks = false), OFFLINE; companion object { fun parse(s: String?): Mood } }` — `LISTENING` is the only mood that does not blink, which is half of what makes the mic state unmistakable.
  - `data class EyeShape(val heightRatio: Float, val topRadiusRatio: Float, val bottomRadiusRatio: Float, val offsetYRatio: Float, val rotationDeg: Float, val alpha: Float)` — all ratios are of the eye's **width**.
  - `fun Mood.left(): EyeShape` and `fun Mood.right(): EyeShape` (they differ only for SMUG and FRUSTRATED).
  - `object Palette { fun eyeColor(m: Mood): Int; fun backgroundColor(m: Mood): Int; fun glowRadiusDp(m: Mood, tMs: Long): Float }`
  - `object Wake { const val DURATION_MS = 1900L; const val RIGHT_DELAY_MS = 120L; fun scaleYAt(tMs: Long): Float; fun brightnessAt(tMs: Long): Float }`
  - `class Blink(seed: Long) { fun isBlinkingAt(tMs: Long, eye: Eye): Boolean; enum class Eye { LEFT, RIGHT } }`
  - `object Drift { const val AMPLITUDE_DP = 12f; fun offsetAt(tMs: Long): PointF }`
  - `class FaceView(context, attrs) : View` with `var mood: Mood`, `fun wake()`, `fun sleep()`.

- [ ] **Step 1: Write the failing tests**

`MoodTest.kt` — the geometry table, taken from `docs/design/face-style.html`
where the neutral eye is 22 × 50 and each mood overrides it. Ratios are of
the eye's width:

```kotlin
@Test fun `neutral is a tall pill`() {
    val e = Mood.NEUTRAL.left()
    assertEquals(2.27f, e.heightRatio, 0.01f)
    assertEquals(0.5f, e.topRadiusRatio, 0.01f)
    assertEquals(0.5f, e.bottomRadiusRatio, 0.01f)
}

// The README's warning, as a test: colour alone cannot tell sad from sleepy.
@Test fun `every mood is distinguishable by shape alone`() {
    val shapes = Mood.values().map { it.left() to it.right() }
    assertEquals("two moods share a silhouette", Mood.values().size, shapes.toSet().size)
}

@Test fun `happy rounds the tops and flattens the bottoms`() {
    val e = Mood.HAPPY.left()
    assertEquals(1.18f, e.heightRatio, 0.01f)
    assertTrue(e.topRadiusRatio > e.bottomRadiusRatio)
    assertTrue("happy sits high", e.offsetYRatio < 0f)
}

@Test fun `sad inverts happy`() {
    val e = Mood.SAD.left()
    assertEquals(1.36f, e.heightRatio, 0.01f)
    assertTrue(e.bottomRadiusRatio > e.topRadiusRatio)
    assertTrue("sad sits low", e.offsetYRatio > 0f)
}

@Test fun `frustrated tilts the two eyes in opposite directions`() {
    assertEquals(14f, Mood.FRUSTRATED.left().rotationDeg, 0.01f)
    assertEquals(-14f, Mood.FRUSTRATED.right().rotationDeg, 0.01f)
}

@Test fun `smug raises one eye and not the other`() {
    assertTrue(Mood.SMUG.left().heightRatio > Mood.SMUG.right().heightRatio)
}

@Test fun `sleepy is a slit, dimmed`() {
    assertEquals(0.36f, Mood.SLEEPY.left().heightRatio, 0.01f)
    assertEquals(0.65f, Mood.SLEEPY.left().alpha, 0.01f)
}

// Review Focus 5.
@Test fun `an unknown or missing mood parses as NEUTRAL`() {
    assertEquals(Mood.NEUTRAL, Mood.parse(null))
    assertEquals(Mood.NEUTRAL, Mood.parse(""))
    assertEquals(Mood.NEUTRAL, Mood.parse("apoplectic"))
    assertEquals(Mood.SMUG, Mood.parse("smug"))
    assertEquals(Mood.SMUG, Mood.parse("SMUG"))
}

@Test fun `listening is unmistakable`() {
    // It is the only mood whose eyes are taller than neutral and that never blinks.
    assertTrue(Mood.LISTENING.left().heightRatio > Mood.NEUTRAL.left().heightRatio)
    assertFalse(Mood.LISTENING.blinks)
}
```

`PaletteTest.kt` — the approved rule is "the field behind the eyes is the
opposite hue at 6–10% lightness":

```kotlin
@Test fun `the background is the complement of the eyes`() {
    for (m in Mood.values()) {
        val eye = hsl(Palette.eyeColor(m))
        val bg = hsl(Palette.backgroundColor(m))
        assertEquals("hue must be rotated 180 for $m", 180f, hueDistance(eye[0], bg[0]), 3f)
    }
}

@Test fun `the background never lights up a dark room`() {
    for (m in Mood.values()) {
        val l = hsl(Palette.backgroundColor(m))[2]
        assertTrue("$m background lightness $l outside 6-10%", l in 0.06f..0.10f)
    }
}

@Test fun `the approved mood colours are exactly the ones rendered`() {
    assertEquals(0xFF4AD96B.toInt(), Palette.eyeColor(Mood.HAPPY))
    assertEquals(0xFF5B8DD6.toInt(), Palette.eyeColor(Mood.SAD))
    assertEquals(0xFFFF7043.toInt(), Palette.eyeColor(Mood.FRUSTRATED))
    assertEquals(0xFFFF6FAE.toInt(), Palette.eyeColor(Mood.LOVING))
    assertEquals(0xFFE8B63C.toInt(), Palette.eyeColor(Mood.SMUG))
    assertEquals(0xFF8A7FD4.toInt(), Palette.eyeColor(Mood.SLEEPY))
    assertEquals(0xFF4FD4E4.toInt(), Palette.eyeColor(Mood.NEUTRAL))
}

@Test fun `only listening pulses its glow`() {
    val still = (0..2000 step 100).map { Palette.glowRadiusDp(Mood.NEUTRAL, it.toLong()) }.toSet()
    assertEquals(1, still.size)
    val pulsing = (0..2000 step 100).map { Palette.glowRadiusDp(Mood.LISTENING, it.toLong()) }.toSet()
    assertTrue(pulsing.size > 5)
}
```

`WakeTest.kt` — the curve from the spec's `@keyframes cf-wake`:

```kotlin
@Test fun `the eyes start shut`() {
    assertEquals(0.025f, Wake.scaleYAt(0), 0.001f)
    assertEquals(0.025f, Wake.scaleYAt(798), 0.001f)   // 42%
}

// "roughly 2 seconds, with a slight overshoot before settling"
@Test fun `the wake overshoots before settling`() {
    assertEquals(1.06f, Wake.scaleYAt(1102), 0.01f)    // 58%
    assertEquals(0.88f, Wake.scaleYAt(1368), 0.01f)    // 72%
    assertEquals(1.0f, Wake.scaleYAt(1900), 0.001f)
    val peak = (0..1900 step 10).maxOf { Wake.scaleYAt(it.toLong()) }
    assertTrue("there must be an overshoot above 1.0", peak > 1.0f)
}

@Test fun `the wake is unhurried`() {
    assertEquals(1900L, Wake.DURATION_MS)
}

@Test fun `brightness rises past full and settles`() {
    assertEquals(0.35f, Wake.brightnessAt(0), 0.01f)
    assertTrue((0..1900 step 10).maxOf { Wake.brightnessAt(it.toLong()) } > 1.0f)
    assertEquals(1.0f, Wake.brightnessAt(1900), 0.01f)
}
```

`BlinkTest.kt` — the README: "Synchronised blinking reads as mechanical."

```kotlin
@Test fun `the two eyes do not blink together`() {
    val b = Blink(seed = 1)
    val span = 30 * 60 * 1000L
    var both = 0; var either = 0
    for (t in 0 until span step 20) {
        val l = b.isBlinkingAt(t, LEFT); val r = b.isBlinkingAt(t, RIGHT)
        if (l || r) either++
        if (l && r) both++
    }
    assertTrue("eyes blinked together $both of $either samples", both < either / 50)
}

@Test fun `the interval is not fixed`() {
    val starts = blinkStarts(Blink(seed = 2), LEFT, 30 * 60 * 1000L)
    val gaps = starts.zipWithNext { a, b -> b - a }.toSet()
    assertTrue("a fixed interval reads as a metronome", gaps.size > 10)
}

@Test fun `it blinks about every six seconds`() {
    val starts = blinkStarts(Blink(seed = 3), LEFT, 60 * 60 * 1000L)
    val mean = starts.zipWithNext { a, b -> b - a }.average()
    assertTrue("mean gap was $mean", mean in 4_500.0..7_500.0)
}

@Test fun `the same seed replays identically`() {
    assertEquals(blinkStarts(Blink(4), LEFT, 60_000),
                 blinkStarts(Blink(4), LEFT, 60_000))
}
```

`DriftTest.kt` — the spec's burn-in mitigation, "the whole face drifts a few
pixels on a slow cycle":

```kotlin
@Test fun `the drift stays within a few pixels`() {
    for (t in 0 until 24L * 3600_000 step 60_000) {
        val p = Drift.offsetAt(t)
        assertTrue(abs(p.x) <= Drift.AMPLITUDE_DP && abs(p.y) <= Drift.AMPLITUDE_DP)
    }
}

@Test fun `nothing sits on the same pixel for hours`() {
    var run = 0; var worst = 0; var prev: Pair<Int, Int>? = null
    for (t in 0 until 24L * 3600_000 step 60_000) {
        val p = Drift.offsetAt(t); val k = p.x.roundToInt() to p.y.roundToInt()
        run = if (k == prev) run + 1 else 0
        worst = max(worst, run); prev = k
    }
    assertTrue("held one position for $worst minutes", worst < 15)
}

@Test fun `the drift spreads the load around`() {
    val seen = (0 until 24L * 3600_000 step 60_000)
        .map { Drift.offsetAt(it).let { p -> p.x.roundToInt() to p.y.roundToInt() } }.toSet()
    assertTrue("only ${seen.size} distinct positions in a day", seen.size > 150)
}
```

- [ ] **Step 2: Run them to watch them fail**

Run: `./gradlew :app:testDebugUnitTest --tests "*.face.*"`
Expected: FAIL — the `face` package does not exist.

- [ ] **Step 3: Implement the pure pieces**

`Mood.kt`, `Palette.kt`, `Wake.kt`, `Blink.kt`, `Drift.kt`, to the
signatures in Interfaces.

Notes the signatures do not determine:
- `Palette.backgroundColor` computes, it does not tabulate: convert the eye
  colour to HSL, add 180° to the hue, set saturation 0.45 and lightness
  0.075, convert back. That reproduces the three rendered swatches
  (`#190a17`, `#06181d`, `#07190d`) to within a few units and makes the rule
  the thing that is implemented.
- `Wake` interpolates the five keyframes with the spec's
  `cubic-bezier(.2,.9,.25,1)` easing between them.
- `Blink` is deterministic from its seed: a `java.util.Random(seed)` per eye,
  left base period 5400 ms and right 6100 ms, each gap jittered by
  `random.nextInt(900)`, a blink lasting 110 ms. `Mood.LISTENING.blinks` is
  false and `isBlinkingAt` is not consulted for it.
- `Drift` is a Lissajous: `x = A·sin(2πt/661s)`, `y = A·sin(2πt/787s)`. The
  two periods are coprime primes in seconds so the pattern does not repeat
  within a day.

- [ ] **Step 4: Run them to watch them pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*.face.*"`
Expected: PASS.

- [ ] **Step 5: Mutation-check the two tests that could pass vacuously**

The project's standing practice. Break each, confirm red, revert, confirm
green — as separate commands, never a revert and a suite run in one:

1. Make both eyes share one `Blink` instance → `the two eyes do not blink together` must FAIL.
2. Return a constant from `Drift.offsetAt` → `nothing sits on the same pixel for hours` must FAIL.

Record both in the ledger.

- [ ] **Step 6: Implement `FaceView`**

A `View` that holds a start timestamp, invalidates on `Choreographer`
frames, and draws: the radial-gradient vignette over
`Palette.backgroundColor(mood)`, then two round-rect eyes at
`Palette.eyeColor(mood)` with a `BlurMaskFilter` glow of
`Palette.glowRadiusDp(mood, t)`, scaled by `Wake.scaleYAt` during the first
1900 ms (right eye offset by `Wake.RIGHT_DELAY_MS`), squashed to
`Blink`'s 0.06 while blinking, translated by `Drift.offsetAt`, and shaped by
`mood.left()` / `mood.right()`. Eye width is 8% of the view's shorter side;
the gap between them is 2.2× the eye width, matching the rendered spec.
`mood` setter animates colour and geometry over 1000 ms, matching the
spec's `transition: background 1s ease`.

- [ ] **Step 7: See it on the tablet**

`MainActivity` becomes the `FaceView`, full screen, no title bar, with a
temporary tap handler cycling through the nine moods.

Run: build, install, launch, then for each mood
`<adb> exec-out screencap -p > face-<mood>.png` and look at every one.

Expected: nine distinguishable faces; the wake plays shut → open with a
visible overshoot on first launch; sad and sleepy are clearly different
without reference to colour.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/dev/toffee/companion/face app/src/test/java/dev/toffee/companion/face app/src/main/java/dev/toffee/companion/MainActivity.kt
git commit -m "feat: the face — pill eyes, complement field, and a wake worth watching"
```

---

### Task 4: Talking to the backend

**Files:**
- Create: `app/src/main/java/dev/toffee/companion/net/Session.kt`, `TokenStore.kt`, `ToffeeClient.kt`, `Reply.kt`, `Offline.kt`
- Create: `app/src/main/java/dev/toffee/companion/SetupActivity.kt`
- Test: `app/src/test/java/dev/toffee/companion/net/SessionTest.kt`, `ToffeeClientTest.kt`

**Interfaces:**
- Consumes: `ToffeeConfig` (Task 1); the identity from Task 2; `Mood` (Task 3).
- Produces:
  - `interface TokenStore { fun save(t: Tokens); fun load(): Tokens?; fun clear() }`
  - `data class Tokens(val access: String, val refresh: String, val expiresAtMs: Long)`
  - `class KeystoreTokenStore(context: Context) : TokenStore` — `EncryptedSharedPreferences`, master key from the Android Keystore.
  - `sealed interface SessionState { object SignedOut : SessionState; object Active : SessionState; object Revoked : SessionState }`
  - `class Session(http: OkHttpClient, baseUrl: String, apiKey: String, store: TokenStore, clock: () -> Long = System::currentTimeMillis)` with `suspend fun signIn(email: String, password: String): Result<Unit>`, `suspend fun accessToken(): String?`, `fun state(): SessionState`, `fun clear()`.
  - `data class Reply(val speak: String?, val mood: Mood, val offline: Boolean = false)`
  - `class ToffeeClient(http: OkHttpClient, config: ToffeeConfig, session: Session)` with `suspend fun turn(text: String): Reply` and `suspend fun event(kind: String, payload: Map<String, Any?> = emptyMap()): Reply`.
  - `object Offline { fun line(): String }`

- [ ] **Step 1: Write the failing tests**

`SessionTest.kt`, driven by `MockWebServer` — real HTTP, not a mocked client:

```kotlin
@Test fun `sign in stores the refresh token`()
@Test fun `a valid token is reused without a network call`()        // server gets 0 requests
@Test fun `a token within 60s of expiry is refreshed first`()
@Test fun `signing in with the wrong password returns a failure and stays SignedOut`()

// Review Focus 2: the spec's answer to "someone picked it up" is disabling
// the auth user. The tablet must go inert, not hammer the endpoint.
@Test fun `a refresh rejected with 400 puts the session in Revoked and stops retrying`() {
    server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
    assertNull(session.accessToken())
    assertEquals(SessionState.Revoked, session.state())
    assertNull(session.accessToken())                 // second call
    assertEquals(1, server.requestCount)              // it did not ask again
    assertNull(store.load())                          // and it threw the token away
}

@Test fun `a refresh that fails on a network error does not revoke`() {
    // An outage must not look like revocation; state stays Active and it retries later.
}
```

`ToffeeClientTest.kt`:

```kotlin
@Test fun `turn posts the text and the bearer token to the turn function`() {
    // asserts path /functions/v1/turn, Authorization: Bearer <access>, apikey header, body {"text":...}
}
@Test fun `event posts kind and payload to the event function`()
@Test fun `a reply is parsed into speak and mood`()
@Test fun `a null speak means there is nothing to say`()

// Review Focus 5 — observed in production when the model is unavailable.
@Test fun `a reply with no mood field parses as NEUTRAL`()
@Test fun `a reply with an unknown mood parses as NEUTRAL`()
@Test fun `a reply that is not json returns the offline reply rather than throwing`()

// Review Focus 1.
@Test fun `turn returns the offline reply when the network is gone`() {
    server.shutdown()
    val r = client.turn("hello")
    assertTrue(r.offline)
    assertEquals(Mood.OFFLINE, r.mood)
    assertTrue(r.speak!!.isNotBlank())
}

@Test fun `the client recovers on the next request after an outage`() {
    // one failing request, then a normal 200 — the second must succeed with no restart
}

@Test fun `a 401 refreshes once and retries once`() {
    // 401, then the refresh 200, then the retried call 200 — exactly 3 requests
}

@Test fun `a 401 that survives the retry does not loop`() {
    assertEquals(3, server.requestCount)
}

// The spec: comic timing matters, and a hung request is worse than a late one.
@Test fun `a request that stalls gives up and returns the offline reply`() {
    server.enqueue(MockResponse().setBodyDelay(40, SECONDS))
    assertTrue(client.turn("hello").offline)
}

@Test fun `the offline lines do not pretend to be thinking`() {
    repeat(50) { assertFalse(Offline.line().contains("thinking", ignoreCase = true)) }
    assertTrue(Offline.lines.size >= 4)
}
```

- [ ] **Step 2: Run them to watch them fail**

Run: `./gradlew :app:testDebugUnitTest --tests "*.net.*"`
Expected: FAIL — the `net` package does not exist.

- [ ] **Step 3: Implement the net package**

To the signatures above. What the signatures leave open:
- The OkHttp client gets a 25-second call timeout, matching the backend's
  own `DEFAULT_TIMEOUT_MS`.
- Auth endpoints are `POST <base>/auth/v1/token?grant_type=password` and
  `?grant_type=refresh_token`, both carrying the `apikey` header.
- `Session.accessToken()` is serialised behind a `Mutex` so two concurrent
  callers cannot both refresh.
- `Reply` parsing is lenient by construction: unknown fields ignored,
  `mood` through `Mood.parse`, any parse failure becoming the offline reply.
- `Offline.lines` is at least four lines in character — mean to Ahmed, honest
  that it cannot reach the agent, never pretending to think.

- [ ] **Step 4: Run them to watch them pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*.net.*"`
Expected: PASS.

- [ ] **Step 5: Mutation-check the revocation gate**

Make `accessToken()` retry after a 400. Confirm
`a refresh rejected with 400 puts the session in Revoked and stops retrying`
FAILS. Revert. Re-run, confirm green. Record in the ledger.

- [ ] **Step 6: The setup screen**

`SetupActivity`: two fields and a button, shown only when
`session.state() == SignedOut`. On success it finishes to `MainActivity`.
The password field is `inputType="textPassword"`; neither value is logged.

- [ ] **Step 7: Prove it against the real backend**

Install, launch, type the identity from Task 2, and confirm the app gets a
real reply.

Run: `<adb> logcat -s Toffee:D` while tapping.
Expected: a `/turn` round trip logged with a non-empty `speak` and a mood —
the same shape the backend was observed returning
(*"You have exactly one task..."*, mood `smug`). Screenshot it.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/dev/toffee/companion/net app/src/main/java/dev/toffee/companion/SetupActivity.kt app/src/test/java/dev/toffee/companion/net
git commit -m "feat: the tablet talks to the agent, and goes quiet when it cannot"
```

---

### Task 5: Reminders that cannot be missed

**Files:**
- Create: `app/src/main/java/dev/toffee/companion/reminders/Reminder.kt`, `ReminderInbox.kt`, `ReminderPoller.kt`, `AlarmScheduler.kt`, `Mirror.kt`
- Test: `app/src/test/java/dev/toffee/companion/reminders/ReminderInboxTest.kt`, `ReminderPollerTest.kt`

**Interfaces:**
- Consumes: `Session`, `ToffeeConfig` (Tasks 1, 4).
- Produces:
  - `data class Reminder(val id: String, val text: String, val dueAtMs: Long, val firedAtMs: Long?)`
  - `class ReminderInbox(private val seen: AnnouncedStore)` with `fun offer(source: Source, rs: List<Reminder>): List<Reminder>`, `fun markAnnounced(id: String)`, `enum class Source { POLL, ALARM }`
  - `interface AnnouncedStore { fun contains(id: String): Boolean; fun add(id: String) }`, with a Room-backed implementation and an in-memory one for tests.
  - `class ReminderPoller(http, config, session, intervalMs: Long = 20_000, clock)` with `fun flow(): Flow<List<Reminder>>` emitting only reminders whose `firedAtMs` is newer than the last seen.
  - `class AlarmScheduler(context)` with `fun schedule(r: Reminder)`, `fun cancel(id: String)`, and `ReminderAlarmReceiver`.
  - `Mirror` (Room, version 1): entities `ReminderRow` and `AnnouncedRow` only — see the ruling on the mirror. `MirrorDao` with upsert-all, read-pending, and the two `AnnouncedStore` operations.

- [ ] **Step 1: Write the failing tests**

`ReminderInboxTest.kt`:

```kotlin
// Review Focus 3 — the whole point of having two paths.
@Test fun `the same reminder from both sources is announced once`() {
    val r = reminder("abc")
    assertEquals(listOf(r), inbox.offer(POLL, listOf(r)))
    inbox.markAnnounced("abc")
    assertEquals(emptyList(), inbox.offer(ALARM, listOf(r)))
}

@Test fun `the alarm wins when it arrives first`() {
    assertEquals(listOf(r), inbox.offer(ALARM, listOf(r)))
    inbox.markAnnounced("abc")
    assertEquals(emptyList(), inbox.offer(POLL, listOf(r)))
}

@Test fun `an announced reminder stays announced across a restart`() {
    inbox.offer(POLL, listOf(r)); inbox.markAnnounced("abc")
    val reborn = ReminderInbox(store)        // same store, new inbox
    assertEquals(emptyList(), reborn.offer(POLL, listOf(r)))
}

@Test fun `two different reminders are both announced`()

@Test fun `offering a reminder twice before it is marked still returns it`() {
    // marking is the app's acknowledgement that it spoke; offering is not.
}

// The backend's sweep has no lower bound, so the tablet must not add one.
@Test fun `a reminder that fired four hours ago is still announced`()
```

`ReminderPollerTest.kt`, with `MockWebServer` and a test dispatcher:

```kotlin
@Test fun `the poller asks for reminders fired since the last one it saw`()
@Test fun `it emits only newly fired reminders`()
@Test fun `it polls every twenty seconds`()
@Test fun `a failed poll does not stop the flow`()      // one 500, then a 200 that emits
@Test fun `a revoked session stops the poller`()        // Session.Revoked → flow completes
```

- [ ] **Step 2: Run them to watch them fail**

Run: `./gradlew :app:testDebugUnitTest --tests "*.reminders.*"`
Expected: FAIL — the `reminders` package does not exist.

- [ ] **Step 3: Implement the reminders package**

To the signatures above. The poll is
`GET <base>/rest/v1/reminders?fired_at=gt.<last>&select=id,text,due_at,fired_at&order=fired_at.asc`
with the device bearer token. `AlarmScheduler` uses
`setExactAndAllowWhileIdle` with a `PendingIntent` keyed by reminder id so
rescheduling replaces rather than duplicates.

- [ ] **Step 4: Run them to watch them pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*.reminders.*"`
Expected: PASS.

- [ ] **Step 5: Mutation-check the de-duplication**

Make `offer` ignore `seen.contains`. Confirm
`the same reminder from both sources is announced once` FAILS. Revert.
Re-run, confirm green. Record in the ledger.

- [ ] **Step 6: Prove both paths on the device**

Set a reminder through the backend due in two minutes
(`scripts/talk.sh "set a reminder for two minutes from now to stand up"`),
then watch the tablet with `<adb> logcat -s Toffee:D`.

Expected: the reminder is announced exactly once, with the log naming which
source got there first. Then put the tablet in airplane mode, set another
through the laptop, and confirm the local alarm announces it with no
network.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/dev/toffee/companion/reminders app/src/test/java/dev/toffee/companion/reminders
git commit -m "feat: two paths to a reminder, one announcement"
```

---

### Task 6: Kiosk

Everything except the final provisioning, which is Ahmed's call.

**Files:**
- Create: `app/src/main/java/dev/toffee/companion/kiosk/Kiosk.kt`, `BrightnessPolicy.kt`, `ToffeeDeviceAdmin.kt`, `BootReceiver.kt`, `CornerHold.kt`, `Pin.kt`, `MaintenanceActivity.kt`
- Create: `app/src/main/res/xml/device_admin.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/dev/toffee/companion/kiosk/BrightnessPolicyTest.kt`, `CornerHoldTest.kt`, `PinTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `object BrightnessPolicy { fun levelFor(presentSinceMs: Long?, lastSeenMs: Long?, nowMs: Long, localHour: Int): Level; enum class Level { FULL, DIM, NIGHT } }`
  - `object Kiosk { fun isDeviceOwner(c: Context): Boolean; fun enter(a: Activity); fun exit(a: Activity); fun apply(a: Activity, l: BrightnessPolicy.Level) }`
  - `object CornerHold { const val HOLD_MS = 5000L; const val BOX_DP = 80f; fun held(xDp: Float, yDp: Float, downAtMs: Long, nowMs: Long): Boolean }`
  - `object Pin { fun hash(pin: String, salt: ByteArray): String; fun verify(entered: String, salt: ByteArray, stored: String): Boolean }`
  - `MaintenanceActivity` — Wi-Fi configuration, log export, exit-kiosk toggle.

- [ ] **Step 1: Write the failing tests**

`BrightnessPolicyTest.kt`, from the spec's screen rules:

```kotlin
@Test fun `present in the day is full brightness`()
@Test fun `absent for under ten minutes stays full`()
@Test fun `absent for over ten minutes dims`()
@Test fun `present at night is dim, not full`()          // he is at the desk at 2am
@Test fun `absent at night is the dimmest`()
@Test fun `the night window is 23 to 6 inclusive of 23 and 6`()

// The screen is never fully off while powered: presence detection and the
// arrival ambush both depend on it being awake.
@Test fun `no combination of inputs ever returns a level meaning off`() {
    // Level has no OFF member, and this test documents why.
}
```

`CornerHoldTest.kt`:

```kotlin
@Test fun `a five second hold in the top left corner opens maintenance`()
@Test fun `four and a half seconds is not enough`()
@Test fun `a long hold outside the corner box does nothing`()
@Test fun `a hold that started outside the box does nothing even if it ends inside`()
```

`PinTest.kt`:

```kotlin
@Test fun `the right pin verifies`()
@Test fun `the wrong pin does not`()
@Test fun `the same pin with a different salt hashes differently`()
@Test fun `the stored value is not the pin`()      // hash does not contain the digits
```

- [ ] **Step 2: Run them to watch them fail**

Run: `./gradlew :app:testDebugUnitTest --tests "*.kiosk.*"`
Expected: FAIL — the `kiosk` package does not exist.

- [ ] **Step 3: Implement the pure pieces**

`BrightnessPolicy`, `CornerHold`, `Pin`. `Pin.hash` is
SHA-256 over `salt + pin`, hex-encoded; the salt is 16 random bytes stored
beside the hash in `EncryptedSharedPreferences`.

- [ ] **Step 4: Run them to watch them pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*.kiosk.*"`
Expected: PASS.

- [ ] **Step 5: Implement the Android pieces**

- `ToffeeDeviceAdmin : DeviceAdminReceiver` plus `res/xml/device_admin.xml`.
- Manifest: `MainActivity` gets `android.intent.category.HOME` and
  `DEFAULT` alongside `LAUNCHER`, `launchMode="singleTask"`,
  `stateNotNeeded="true"`; `BootReceiver` registers for
  `RECEIVE_BOOT_COMPLETED`; `ReminderAlarmReceiver` from Task 5 is declared.
- `Kiosk.enter` calls `setLockTaskPackages` when device owner, then
  `startLockTask`, sets `FLAG_KEEP_SCREEN_ON`, and hides the system UI.
  When the app is **not** device owner it logs that and does everything
  else, so the app is fully usable before provisioning.
- `MaintenanceActivity`: Wi-Fi through `DevicePolicyManager`, a log export
  writing to external storage, and an exit-kiosk toggle. Reached only
  through `CornerHold` then `Pin`.

- [ ] **Step 6: Verify everything that does not need Device Owner**

Install and check on the tablet:
- The app relaunches itself as HOME when Home is pressed (it is now a
  launcher choice; pick it as default for the test).
- Five seconds on the top-left corner opens the PIN prompt; a wrong PIN is
  refused; the right one opens maintenance.
- `<adb> reboot`, hands off. Expected: the tablet comes back and the
  companion is on screen with no interaction — Review Focus 4. Screenshot it.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/dev/toffee/companion/kiosk app/src/main/res/xml app/src/main/AndroidManifest.xml app/src/test/java/dev/toffee/companion/kiosk
git commit -m "feat: kiosk — launcher, lock task, boot, and a way back in"
```

- [ ] **Step 8: STOP. The factory reset is Ahmed's call.**

Device Owner can only be set on a device with no accounts, so the tablet
must be wiped, and undoing Device Owner means another wipe. Before anything
here:

1. Report that everything else is built and verified, and that this is the
   one-way door.
2. Ask him to physically check the ten-year-old battery for swelling, which
   the spec flags as worth catching before the device lives on mains.
3. Only on his explicit go-ahead: back up what he wants off the tablet,
   factory reset, skip all account setup, enable USB debugging, then
   `<adb> shell dpm set-device-owner dev.toffee.companion/.kiosk.ToffeeDeviceAdmin`,
   and verify `Kiosk.isDeviceOwner` returns true and `startLockTask` holds.

Expected if he declines or defers: the task is still complete. The app runs
without Device Owner; it is simply escapable.

---

### Task 7: Wire it together

**Files:**
- Modify: `app/src/main/java/dev/toffee/companion/MainActivity.kt`
- Create: `app/src/main/java/dev/toffee/companion/Brain.kt`
- Test: `app/src/test/java/dev/toffee/companion/BrainTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 3–6.
- Produces: `class Brain(client: ToffeeClient, inbox: ReminderInbox, session: Session, scope: CoroutineScope)` exposing `val state: StateFlow<FaceState>` and `suspend fun say(text: String)`, `suspend fun onReminders(rs: List<Reminder>)`, `suspend fun onArrival()`.
  `data class FaceState(val mood: Mood, val line: String?, val listening: Boolean)`.
  All of the decision-making lives here, in a class with no Android types, so
  the integration is testable; `MainActivity` only renders `FaceState` into
  `FaceView` and a `TextView`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `on arrival it posts an arrived event and takes the mood it is given`()
@Test fun `a reply with no line leaves the face alone and says nothing`()
@Test fun `a reminder becomes a reminder_due event and is marked announced`()
@Test fun `tapping shows the listening face before the request goes out`()
@Test fun `the listening face is dropped when the reply arrives`()
@Test fun `an offline reply shows the sulking face and the canned line`()
@Test fun `a revoked session shows a fixed inert state and stops asking`()
@Test fun `the line clears itself after twenty seconds`()
@Test fun `a second reminder arriving mid-sentence is queued, not dropped`()
```

- [ ] **Step 2: Run them to watch them fail**

Run: `./gradlew :app:testDebugUnitTest --tests "*.BrainTest"`
Expected: FAIL — `Brain` does not exist.

- [ ] **Step 3: Implement `Brain` and rewire `MainActivity`**

`MainActivity` on create: show the asleep face, run the wake, then — if
signed out, go to `SetupActivity`; otherwise collect `Brain.state` into
the view, post `onArrival()`, start `ReminderPoller.flow()` into
`onReminders`, and apply `BrightnessPolicy` on a one-minute tick. Tapping
the face calls `say()` with text from a small input, standing in for the STT
that Plan 3 brings.

- [ ] **Step 4: Run them to watch them pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*.BrainTest"`
Expected: PASS.

- [ ] **Step 5: Run the whole suite, both sides**

Run: `./gradlew :app:testDebugUnitTest` and `deno test -A tests/`
Expected: both green. Any failure, including one you did not cause, is named
in the report.

- [ ] **Step 6: Watch it be itself**

On the tablet, end to end: launch cold and watch it wake; type something and
get a reply in character; let a reminder fire and watch the face change;
pull the Wi-Fi and watch it sulk honestly; restore it and watch it recover
without a restart. Screenshot each.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/dev/toffee/companion
git commit -m "feat: it wakes up, it listens, it answers, it remembers to nag"
```

---

## Notes for the final review

- The face is verified by screenshot, not by assertion — `FaceView` itself
  has no unit tests, by design, because everything it draws is a pure
  function that does. A reviewer should check that nothing testable slid
  into `FaceView`.
- `MaintenanceActivity`, `SetupActivity` and the Room DAO are thin shells
  over tested logic. Same check applies.
- The spec's "What is left to the builder" list is deliberately untouched
  here: the mood → geometry table is now pinned (Task 3), and STT, TTS and
  the governor's thresholds remain Plan 3's.
