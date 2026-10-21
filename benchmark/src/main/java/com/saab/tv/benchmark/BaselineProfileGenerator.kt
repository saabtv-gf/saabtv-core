package com.saab.tv.benchmark

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE_NAME = "com.saab.tv.test"

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineRule = BaselineProfileRule()

    @Test
    fun generate() {
        // The API 36 TV emulator can crash its own SystemUI before the target app
        // starts, leaving BaselineProfileRule with no process to collect. Profiles
        // and performance evidence are meaningful only on physical hardware anyway.
        assumeTrue(
            "Baseline profiles must be collected on a physical Android TV device",
            !isEmulator()
        )

        baselineRule.collect(
            packageName = PACKAGE_NAME,
            includeInStartupProfile = true
        ) {
            pressHome()
            startActivityAndWait()
            ensureHomeWhenLocalRecoveryIsOffered(device)
            device.waitForIdle()

            // Warm each high-traffic shell destination through its real TV navigation
            // entry. The macrobenchmark owns performance measurement; this profile
            // pass should stay tolerant of empty catalogs and optional destinations.
            listOf("Movies", "Series", "My Library", "Settings", "Home", "Search")
                .forEach { destination ->
                    device.wait(Until.findObject(By.desc(destination)), 3_000L)?.click()
                    device.waitForIdle(500)
                }

            // Exercise the D-pad path after entering Search so the profile also
            // includes the focus and keyboard code used by remote-first browsing.
            repeat(4) {
                device.pressDPadRight()
                device.pressDPadDown()
            }
            device.waitForIdle()
        }
    }
}

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMetricApi::class)
class TvStartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun firstUseAccountEntryStartupAndRemoteFocus() {
        // Unlike Home-shelf measurements, first-use onboarding needs no seeded account
        // or remote service. Keep it measurable on a clean install and exercise the
        // same focus traversal a customer uses before they can reach Home.
        assumeTrue(
            "Macrobenchmark is skipped on emulators unless -PsaabAllowEmulatorBenchmark=true is set",
            !isEmulator() || emulatorBenchmarkOptedIn()
        )

        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(
                StartupTimingMetric(),
                FrameTimingMetric(),
                peakTvMemoryMetric()
            ),
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.COLD,
            iterations = 5,
            setupBlock = { pressHome() }
        ) {
            startActivityAndWait()
            check(device.wait(Until.hasObject(By.text("Create Account")), 5_000L)) {
                "First-use account entry did not render the Create Account action"
            }
            device.pressDPadDown()
            repeat(3) { device.pressDPadRight() }
            repeat(2) { device.pressDPadLeft() }
            device.pressDPadUp()
            device.waitForIdle()
        }
    }

    @Test
    fun deterministicFixtureFocusTravelFrameAndMemoryBaseline() {
        assumeTrue(
            "U02 reference results require a physical Android TV; emulator runs are smoke-only",
            !isEmulator()
        )

        val samples = mutableListOf<U02InputSample>()
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(FrameTimingMetric(), peakTvMemoryMetric()),
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.WARM,
            iterations = 10,
            setupBlock = {
                device.executeShellCommand("am force-stop $PACKAGE_NAME")
                device.executeShellCommand(
                    "am start -n $PACKAGE_NAME/.benchmark.U02BaselineFixtureActivity"
                )
                check(device.wait(Until.hasObject(By.desc(U02_FIXTURE_DESCRIPTION)), 10_000L)) {
                    "The deterministic U02 fixture did not open"
                }
                check(device.wait(Until.hasObject(By.focused(true).desc("U02-M1-01")), 3_000L)) {
                    "The first fixture card did not receive initial focus"
                }
            }
        ) {
            var expectedRow = 0
            var expectedCard = 0
            U02_REMOTE_SEQUENCE.forEach { direction ->
                when (direction) {
                    "right" -> expectedCard++
                    "left" -> expectedCard--
                    "down" -> expectedRow++
                    "up" -> expectedRow--
                }
                val expectedTitle = u02FixtureTitle(expectedRow, expectedCard)
                val before = focusedU02Title(device)
                val startedAt = SystemClock.elapsedRealtimeNanos()
                when (direction) {
                    "right" -> device.pressDPadRight()
                    "left" -> device.pressDPadLeft()
                    "down" -> device.pressDPadDown()
                    "up" -> device.pressDPadUp()
                }
                val acknowledged = device.wait(
                    Until.hasObject(By.focused(true).desc(expectedTitle)),
                    U02_INPUT_ACK_TIMEOUT_MS
                )
                val acknowledgedAt = SystemClock.elapsedRealtimeNanos()
                device.waitForIdle(U02_FOCUS_SETTLE_TIMEOUT_MS)
                val settledTitle = focusedU02Title(device)
                samples += U02InputSample(
                    direction = direction,
                    before = before,
                    expected = expectedTitle,
                    actual = settledTitle,
                    acknowledged = acknowledged,
                    settled = settledTitle == expectedTitle,
                    inputAckMs = nanosToMillis(acknowledgedAt - startedAt),
                    inputToSettleMs = nanosToMillis(SystemClock.elapsedRealtimeNanos() - startedAt)
                )
            }
        }

        // Macrobenchmark emits FrameTimingMetric, MemoryUsageMetric and Perfetto traces.
        // These compact JSONL records add per-input acknowledgement, settle and focus-loss data.
        emitU02Report(samples)
        check(samples.isNotEmpty()) { "U02 did not capture any remote input samples" }
        check(samples.all { it.acknowledged && it.settled }) {
            "U02 fixture lost or misrouted D-pad input; see U02_BASELINE report"
        }
    }

    @Test
    fun coldStartupAndFirstNavigation() {
        // Emulator timings are not representative. Skip by default so ordinary connected
        // test runs remain green; an explicit Gradle opt-in also suppresses Macrobenchmark's
        // EMULATOR accuracy error, but the resulting timing must still be treated as smoke-only.
        assumeTrue(
            "Macrobenchmark is skipped on emulators unless -PsaabAllowEmulatorBenchmark=true is set",
            !isEmulator() || emulatorBenchmarkOptedIn()
        )

        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(
                StartupTimingMetric(),
                FrameTimingMetric(),
                peakTvMemoryMetric()
            ),
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.COLD,
            iterations = 5,
            setupBlock = { pressHome() }
        ) {
            startActivityAndWait()
            assumeTrue(
                "Could not reach Home from the account entry screen",
                ensureHomeWhenLocalRecoveryIsOffered(device)
            )
            device.pressDPadRight()
            device.pressDPadDown()
            device.waitForIdle()
        }
    }

    @Test
    fun homeShelfFocusTravelFrameTiming() {
        assumeTrue(
            "Macrobenchmark is skipped on emulators unless -PsaabAllowEmulatorBenchmark=true is set",
            !isEmulator() || emulatorBenchmarkOptedIn()
        )

        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(FrameTimingMetric(), peakTvMemoryMetric()),
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.WARM,
            iterations = 10,
            setupBlock = {
                pressHome()
                startActivityAndWait()
                assumeTrue(
                    "Could not reach Home from the account entry screen",
                    ensureHomeWhenLocalRecoveryIsOffered(device)
                )
                device.waitForIdle()
            }
        ) {
            // A rapid reversal exposes queued/lagging focus travel that a single key press hides.
            device.pressDPadDown()
            repeat(8) { device.pressDPadRight() }
            repeat(4) { device.pressDPadLeft() }
            repeat(3) { device.pressDPadDown() }
            repeat(3) { device.pressDPadUp() }
            device.waitForIdle()
        }
    }

    @Test
    fun searchEntryAndRemoteFocusFrameTiming() {
        assumeTrue(
            "Macrobenchmark is skipped on emulators unless -PsaabAllowEmulatorBenchmark=true is set",
            !isEmulator() || emulatorBenchmarkOptedIn()
        )

        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(FrameTimingMetric(), peakTvMemoryMetric()),
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.WARM,
            iterations = 10,
            setupBlock = {
                pressHome()
                startActivityAndWait()
                assumeTrue(
                    "Could not reach Home from the account entry screen",
                    ensureHomeWhenLocalRecoveryIsOffered(device)
                )
                val search = device.wait(Until.findObject(By.desc("Search")), 3_000L)
                assumeTrue("Search navigation entry is not accessible", search != null)
                search!!.click()
                assumeTrue(
                    "Search screen did not open",
                    device.wait(Until.hasObject(By.text("Search Movies And Series")), 5_000L)
                )
                device.waitForIdle()
            }
        ) {
            // Type "bat" through the TV keyboard (q → a → z/b, then reverse to a/t).
            // This exercises actual query publication and cancellation/debounce, not just focus.
            device.pressDPadDown()
            device.pressDPadDown()
            repeat(4) { device.pressDPadRight() }
            device.pressDPadCenter() // b
            device.pressDPadUp()
            repeat(4) { device.pressDPadLeft() }
            device.pressDPadCenter() // a
            device.pressDPadUp()
            repeat(4) { device.pressDPadRight() }
            device.pressDPadCenter() // t
            check(device.wait(Until.hasObject(By.text("bat")), 3_000L)) {
                "Search did not publish the typed query"
            }
            device.waitForIdle()
        }
    }

    @Test
    fun detailsEntryAndReturnFrameTiming() {
        assumeTrue(
            "Macrobenchmark is skipped on emulators unless -PsaabAllowEmulatorBenchmark=true is set",
            !isEmulator() || emulatorBenchmarkOptedIn()
        )

        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(FrameTimingMetric(), peakTvMemoryMetric()),
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.WARM,
            iterations = 10,
            setupBlock = {
                pressHome()
                startActivityAndWait()
                assumeTrue(
                    "Could not reach Home from the account entry screen",
                    ensureHomeWhenLocalRecoveryIsOffered(device)
                )
                val movies = device.wait(Until.findObject(By.desc("Movies")), 3_000L)
                assumeTrue("Movies navigation entry is not accessible", movies != null)
                movies!!.click()
                device.waitForIdle()
            }
        ) {
            // Open a title from Movies so Continue Watching cannot route straight to playback.
            // A populated account is required; otherwise this is skipped rather than scored.
            device.pressDPadRight()
            device.pressDPadCenter()
            val detailAction = device.wait(
                Until.findObject(By.descContains("Play")),
                5_000L
            ) ?: device.wait(Until.findObject(By.descContains("Resume")), 0L)
            assumeTrue("Home did not open a populated title details page", detailAction != null)

            repeat(3) { device.pressDPadRight() }
            repeat(2) { device.pressDPadLeft() }
            device.pressBack()
            check(device.wait(Until.gone(By.descContains("Play")), 5_000L)) {
                "Back did not return from title details to Home"
            }
        }
    }

    @Test
    fun discoveryAndSettingsEndToEndFrameTiming() {
        assumeTrue(
            "Macrobenchmark is skipped on emulators unless -PsaabAllowEmulatorBenchmark=true is set",
            !isEmulator() || emulatorBenchmarkOptedIn()
        )

        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(FrameTimingMetric(), peakTvMemoryMetric()),
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.WARM,
            iterations = 5,
            setupBlock = {
                pressHome()
                startActivityAndWait()
                assumeTrue(
                    "Could not reach Home from the account entry screen",
                    ensureHomeWhenLocalRecoveryIsOffered(device)
                )
            }
        ) {
            // Measure the complete remote-first discovery loop rather than one isolated
            // destination at a time. Each destination is asserted before moving on so a
            // route that silently fails cannot produce deceptively good frame numbers.
            navigateAndAssert(device, "Movies")
            device.pressDPadDown()
            repeat(4) { device.pressDPadRight() }
            device.pressDPadLeft()

            navigateAndAssert(device, "Series")
            device.pressDPadDown()
            repeat(4) { device.pressDPadRight() }
            device.pressDPadLeft()

            navigateAndAssert(device, "Search", "Search Movies And Series")
            device.pressDPadDown()
            device.pressDPadRight()
            device.pressDPadLeft()

            navigateAndAssert(device, "My Library", "My Library")
            device.pressDPadDown()
            device.pressDPadRight()
            device.pressDPadLeft()

            navigateAndAssert(device, "Settings", "Home Screen")
            val playback = device.wait(Until.findObject(By.text("Playback")), 3_000L)
            check(playback != null) { "Playback settings section was not visible" }
            playback!!.click()
            check(
                device.wait(
                    Until.hasObject(By.text("Configure video decoding and display settings.")),
                    5_000L
                )
            ) { "Playback settings content did not open" }
            device.pressBack()

            navigateAndAssert(device, "Home")
            device.waitForIdle()
        }
    }

    @Test
    fun detailsToPlaybackSeekAndBackEndToEndFrameTiming() {
        assumeTrue(
            "Macrobenchmark is skipped on emulators unless -PsaabAllowEmulatorBenchmark=true is set",
            !isEmulator() || emulatorBenchmarkOptedIn()
        )

        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(FrameTimingMetric(), peakTvMemoryMetric()),
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.WARM,
            iterations = 5,
            setupBlock = {
                pressHome()
                startActivityAndWait()
                assumeTrue(
                    "Could not reach Home from the account entry screen",
                    ensureHomeWhenLocalRecoveryIsOffered(device)
                )
                navigateAndAssert(device, "Home")
                val movies = device.wait(Until.findObject(By.desc("Movies")), 3_000L)
                assumeTrue("Movies navigation entry is not accessible", movies != null)
                movies!!.click()
                device.pressDPadRight()
                device.pressDPadCenter()
                val playAction = waitForPlayAction(device, 10_000L)
                assumeTrue("No movie details are available for playback benchmarking", playAction != null)
            }
        ) {
            val playAction = waitForPlayAction(device, 1_000L)
            assumeTrue("Movie play action disappeared before playback", playAction != null)
            playAction!!.click()

            var seekBar = device.wait(Until.findObject(By.desc("Playback progress")), 25_000L)
            if (seekBar == null && device.wait(Until.hasObject(By.text("Select Source")), 5_000L)) {
                val noStreams = device.wait(Until.hasObject(By.text("No streams found.")), 5_000L)
                assumeTrue("No playable source is available for the selected title", !noStreams)

                // Some profiles expose language shortcuts above the stream list; move
                // through those controls to the initially selected playable source.
                if (device.wait(Until.hasObject(By.text("Source Language")), 0L)) {
                    device.pressDPadDown()
                    device.pressDPadDown()
                }
                device.pressDPadCenter()
                seekBar = device.wait(Until.findObject(By.desc("Playback progress")), 30_000L)
            }
            assumeTrue(
                "The selected source did not start a player session",
                seekBar != null
            )

            // Exercise seeking through the same accessible timeline and D-pad events
            // as a remote. Committing before Back validates the end-to-end save/exit path.
            seekBar!!.click()
            repeat(3) { device.pressDPadRight() }
            device.pressDPadCenter()
            device.waitForIdle(500)
            device.pressBack()
            check(
                device.wait(
                    Until.hasObject(By.descContains("Play")),
                    10_000L
                ) || device.wait(Until.hasObject(By.descContains("Resume")), 0L)
            ) { "Back from the player did not return to the title details page" }
        }
    }

    @Test
    fun myLibraryRemoteFocusFrameTiming() {
        assumeTrue(
            "Macrobenchmark is skipped on emulators unless -PsaabAllowEmulatorBenchmark=true is set",
            !isEmulator() || emulatorBenchmarkOptedIn()
        )

        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(FrameTimingMetric(), peakTvMemoryMetric()),
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.WARM,
            iterations = 10,
            setupBlock = {
                pressHome()
                startActivityAndWait()
                assumeTrue(
                    "Could not reach Home from the account entry screen",
                    ensureHomeWhenLocalRecoveryIsOffered(device)
                )
                val library = device.wait(Until.findObject(By.desc("My Library")), 3_000L)
                assumeTrue("My Library navigation entry is not accessible", library != null)
                library!!.click()
                assumeTrue(
                    "My Library screen did not open",
                    device.wait(Until.hasObject(By.text("My Library")), 5_000L)
                )
                device.waitForIdle()
            }
        ) {
            // Empty libraries focus the empty state below the filters; move into the filter rows first.
            device.pressDPadUp()
            repeat(3) { device.pressDPadRight() }
            repeat(2) { device.pressDPadLeft() }
            device.pressDPadDown()
            device.pressDPadRight()
            device.pressDPadLeft()
            device.pressBack()
            device.waitForIdle()
        }
    }

    @Test
    fun inlineTrailerFullscreenPromotionFrameTiming() {
        assumeTrue(
            "Macrobenchmark is skipped on emulators unless -PsaabAllowEmulatorBenchmark=true is set",
            !isEmulator() || emulatorBenchmarkOptedIn()
        )

        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(FrameTimingMetric(), peakTvMemoryMetric()),
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.WARM,
            iterations = 10,
            setupBlock = {
                pressHome()
                startActivityAndWait()
                assumeTrue(
                    "Could not reach Home from the account entry screen",
                    ensureHomeWhenLocalRecoveryIsOffered(device)
                )
                val movies = device.wait(Until.findObject(By.desc("Movies")), 3_000L)
                assumeTrue("Movies navigation entry is not accessible", movies != null)
                movies!!.click()
                assumeTrue(
                    "Inline autoplay is disabled, no catalog is available, or no trailer resolved",
                    device.wait(Until.hasObject(By.desc("Fullscreen")), 15_000L)
                )
                device.waitForIdle()
            }
        ) {
            // This measures promotion of the already-playing inline session, not trailer
            // lookup or startup. Fullscreen must also unmute the same preview session.
            val fullscreen = device.findObject(By.desc("Fullscreen"))
            assumeTrue("Inline trailer controls disappeared before promotion", fullscreen != null)
            fullscreen!!.click()
            check(device.wait(Until.hasObject(By.desc("Mute")), 5_000L)) {
                "Fullscreen promotion did not expose the unmuted trailer control state"
            }
            device.pressBack()
            device.waitForIdle()
        }
    }

    @Test
    fun settingsSectionRemoteFocusFrameTiming() {
        assumeTrue(
            "Macrobenchmark is skipped on emulators unless -PsaabAllowEmulatorBenchmark=true is set",
            !isEmulator() || emulatorBenchmarkOptedIn()
        )

        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(FrameTimingMetric(), peakTvMemoryMetric()),
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.WARM,
            iterations = 10,
            setupBlock = {
                pressHome()
                startActivityAndWait()
                assumeTrue(
                    "Could not reach Home from the account entry screen",
                    ensureHomeWhenLocalRecoveryIsOffered(device)
                )
                val settings = device.wait(Until.findObject(By.desc("Settings")), 3_000L)
                assumeTrue("Settings navigation entry is not accessible", settings != null)
                settings!!.click()
                assumeTrue(
                    "Settings screen did not open",
                    device.wait(Until.hasObject(By.text("Home Screen")), 5_000L)
                )
                device.waitForIdle()
            }
        ) {
            device.pressDPadDown()
            device.pressDPadCenter()
            repeat(5) { device.pressDPadDown() }
            repeat(3) { device.pressDPadUp() }
            device.pressBack()
            device.waitForIdle()
        }
    }

    @Test
    fun settingsSectionReentryFrameTiming() {
        assumeTrue(
            "Macrobenchmark is skipped on emulators unless -PsaabAllowEmulatorBenchmark=true is set",
            !isEmulator() || emulatorBenchmarkOptedIn()
        )

        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(FrameTimingMetric(), peakTvMemoryMetric()),
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.WARM,
            iterations = 10,
            setupBlock = {
                pressHome()
                startActivityAndWait()
                assumeTrue(
                    "Could not reach Home from the account entry screen",
                    ensureHomeWhenLocalRecoveryIsOffered(device)
                )
                val settings = device.wait(Until.findObject(By.desc("Settings")), 3_000L)
                assumeTrue("Settings navigation entry is not accessible", settings != null)
                settings!!.click()
                assumeTrue(
                    "Settings screen did not open",
                    device.wait(Until.hasObject(By.text("Home Screen")), 5_000L)
                )
                device.waitForIdle()
            }
        ) {
            fun enterSection(label: String, content: String) {
                val item = device.wait(Until.findObject(By.text(label)), 3_000L)
                check(item != null) { "Settings section '$label' was not visible" }
                item!!.click()
                check(device.wait(Until.hasObject(By.text(content)), 5_000L)) {
                    "Settings section '$label' did not render its content"
                }
                device.pressDPadLeft()
            }

            // Re-entering sections repeatedly measures focus handoff, content animation,
            // and memory while guarding against a transition lock that can strand focus.
            enterSection("Playback", "Configure video decoding and display settings.")
            enterSection("Sort & Filter", "Configure how sources are sorted and filtered.")
            enterSection("Playback", "Configure video decoding and display settings.")
            device.waitForIdle()
        }
    }

    @Test
    fun moviesHubFocusFrameTiming() = catalogDestinationFocusFrameTiming("Movies")

    @Test
    fun seriesHubFocusFrameTiming() = catalogDestinationFocusFrameTiming("Series")

    private fun catalogDestinationFocusFrameTiming(destination: String) {
        assumeTrue(
            "Macrobenchmark is skipped on emulators unless -PsaabAllowEmulatorBenchmark=true is set",
            !isEmulator() || emulatorBenchmarkOptedIn()
        )

        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(FrameTimingMetric(), peakTvMemoryMetric()),
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.WARM,
            iterations = 10,
            setupBlock = {
                pressHome()
                startActivityAndWait()
                assumeTrue(
                    "Could not reach Home from the account entry screen",
                    ensureHomeWhenLocalRecoveryIsOffered(device)
                )
                val navigationItem = device.wait(
                    Until.findObject(By.desc(destination)),
                    3_000L
                )
                assumeTrue("$destination navigation entry is not accessible", navigationItem != null)
                navigationItem!!.click()
                device.waitForIdle()
            }
        ) {
            // Include both a long sweep and a reversal to expose focus travel that
            // queues movement instead of retargeting to the latest remote input.
            device.pressDPadDown()
            repeat(8) { device.pressDPadRight() }
            repeat(4) { device.pressDPadLeft() }
            repeat(3) { device.pressDPadDown() }
            repeat(3) { device.pressDPadUp() }
            device.waitForIdle()
        }
    }
}

private const val U02_FIXTURE_DESCRIPTION = "U02 Fixed Fixture u02-tv-browse-v1"
private const val U02_INPUT_ACK_TIMEOUT_MS = 1_500L
private const val U02_FOCUS_SETTLE_TIMEOUT_MS = 2_000L
private const val U02_BASELINE_TAG = "U02_BASELINE"
private const val U02_SAMPLE_TAG = "U02_INPUT_SAMPLE"

private val U02_REMOTE_SEQUENCE = buildList {
    repeat(8) { add("right") }
    repeat(4) { add("left") }
    add("down")
    repeat(5) { add("right") }
    add("down")
    repeat(3) { add("left") }
    add("up")
    repeat(2) { add("right") }
    add("up")
    repeat(2) { add("left") }
}

private data class U02InputSample(
    val direction: String,
    val before: String?,
    val expected: String,
    val actual: String?,
    val acknowledged: Boolean,
    val settled: Boolean,
    val inputAckMs: Long,
    val inputToSettleMs: Long
) {
    fun toJson() = JSONObject()
        .put("direction", direction)
        .put("before", before)
        .put("expected", expected)
        .put("actual", actual)
        .put("acknowledged", acknowledged)
        .put("settled", settled)
        .put("inputAckMs", inputAckMs)
        .put("inputToSettleMs", inputToSettleMs)
}

private fun focusedU02Title(device: UiDevice): String? =
    device.findObject(By.focused(true))?.contentDescription?.toString()
        ?.takeIf { it.startsWith("U02-") }

private fun u02FixtureTitle(row: Int, card: Int): String {
    val type = if (row % 2 == 0) "M" else "S"
    return "U02-$type${row + 1}-${(card + 1).toString().padStart(2, '0')}"
}

private fun nanosToMillis(nanos: Long): Long = nanos.coerceAtLeast(0L) / 1_000_000L

private fun emitU02Report(samples: List<U02InputSample>) {
    val ackTimes = samples.map(U02InputSample::inputAckMs).sorted()
    val settleTimes = samples.map(U02InputSample::inputToSettleMs).sorted()
    val missed = samples.count { !it.acknowledged || !it.settled }
    val noMovement = samples.count { sample ->
        val before = fixturePosition(sample.before)
        val actual = fixturePosition(sample.actual)
        before != null && before == actual
    }
    val multiStepMovement = samples.count { sample ->
        val before = fixturePosition(sample.before)
        val actual = fixturePosition(sample.actual)
        if (before == null || actual == null) false
        else when (sample.direction) {
            "left", "right" -> kotlin.math.abs(actual.second - before.second) > 1
            "up", "down" -> kotlin.math.abs(actual.first - before.first) > 1
            else -> false
        }
    }
    val summary = JSONObject()
        .put("fixtureId", "u02-tv-browse-v1")
        .put("sampleCount", samples.size)
        .put("missedOrMisroutedInputs", missed)
        .put("noMovementInputs", noMovement)
        .put("multiStepInputs", multiStepMovement)
        .put("inputAckP50Ms", percentile(ackTimes, 0.50))
        .put("inputAckP95Ms", percentile(ackTimes, 0.95))
        .put("inputToSettleP50Ms", percentile(settleTimes, 0.50))
        .put("inputToSettleP95Ms", percentile(settleTimes, 0.95))
    Log.i(U02_BASELINE_TAG, summary.toString())
    samples.chunked(12).forEachIndexed { index, chunk ->
        val records = JSONArray().apply { chunk.forEach { put(it.toJson()) } }
        Log.i(U02_SAMPLE_TAG, "$index:${records}")
    }
}

private fun fixturePosition(title: String?): Pair<Int, Int>? {
    val pieces = title?.removePrefix("U02-")?.split('-') ?: return null
    if (pieces.size != 2) return null
    val row = pieces[0].drop(1).toIntOrNull()?.minus(1) ?: return null
    val card = pieces[1].toIntOrNull()?.minus(1) ?: return null
    return row to card
}

private fun percentile(sortedValues: List<Long>, fraction: Double): Long {
    if (sortedValues.isEmpty()) return 0L
    val index = (kotlin.math.ceil(sortedValues.lastIndex * fraction).toInt())
        .coerceIn(0, sortedValues.lastIndex)
    return sortedValues[index]
}

@OptIn(ExperimentalMetricApi::class)
private fun peakTvMemoryMetric() = MemoryUsageMetric(
    mode = MemoryUsageMetric.Mode.Max,
    subMetrics = listOf(
        MemoryUsageMetric.SubMetric.HeapSize,
        MemoryUsageMetric.SubMetric.RssAnon,
        MemoryUsageMetric.SubMetric.RssFile
    )
)

private fun navigateAndAssert(device: UiDevice, destination: String, expectedText: String = destination) {
    val navigationItem = device.wait(Until.findObject(By.desc(destination)), 3_000L)
    check(navigationItem != null) { "$destination navigation entry was not available" }
    navigationItem!!.click()
    check(device.wait(Until.hasObject(By.desc(destination).selected(true)), 5_000L)) {
        "Selecting $destination did not mark its navigation entry as selected"
    }
    if (expectedText != destination) {
        check(device.wait(Until.hasObject(By.text(expectedText)), 5_000L)) {
            "Selecting $destination did not render expected text '$expectedText'"
        }
    }
    device.waitForIdle(300)
}

private fun waitForPlayAction(device: UiDevice, timeoutMs: Long): UiObject2? =
    device.wait(Until.findObject(By.descContains("Play")), timeoutMs)
        ?: device.wait(Until.findObject(By.descContains("Resume")), 0L)

/** Let the benchmark exercise Home when a persisted test account opens at its recovery gate. */
private fun ensureHomeWhenLocalRecoveryIsOffered(device: UiDevice): Boolean {
    val search = By.desc("Search")
    if (device.wait(Until.hasObject(search), 750L)) return true

    val continueOffline = By.text("Continue Offline")
    if (device.wait(Until.hasObject(continueOffline), 750L)) {
        device.findObject(continueOffline)?.click()
    }
    return device.wait(Until.hasObject(search), 10_000L)
}

private fun emulatorBenchmarkOptedIn(): Boolean =
    InstrumentationRegistry.getArguments()
        .getString("androidx.benchmark.suppressErrors")
        ?.split(',')
        ?.any { it.trim() == "EMULATOR" }
        ?: false

private fun isEmulator(): Boolean =
    Build.FINGERPRINT.startsWith("generic") ||
        Build.FINGERPRINT.startsWith("unknown") ||
        Build.MODEL.contains("google_sdk", ignoreCase = true) ||
        Build.MODEL.contains("Emulator", ignoreCase = true) ||
        Build.MODEL.contains("Android SDK built for", ignoreCase = true) ||
        Build.MANUFACTURER.contains("Genymotion", ignoreCase = true) ||
        (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")) ||
        Build.HARDWARE.contains("ranchu") ||
        Build.HARDWARE.contains("goldfish")
