package dev.lvtd.healthledger

import android.app.job.JobScheduler
import android.health.connect.HealthConnectManager
import android.health.connect.datatypes.*
import android.health.connect.datatypes.units.Pressure
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class LedgerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val at = Instant.parse("2025-01-05T08:00:00Z")
    private val metadata = Metadata.Builder().setId("test-record").setDataOrigin(DataOrigin.Builder().setPackageName(SAMSUNG).build()).build()
    private fun steps(count: Long) = StepsRecord.Builder(metadata, at, at.plusSeconds(600), count).build()

    @Test fun mapsSamsungRecordsWithReplayableIdentifiersAndUnits() {
        val before = steps(45).ledgerRows().single()
        val after = steps(50).ledgerRows().single()
        assertEquals(before.getString("id"), after.getString("id"))
        assertEquals(50, after.getInt("value"))
        assertEquals("steps", after.getString("unit"))
        assertEquals("Samsung Health", after.getString("source"))
        assertEquals(at.toString(), after.getString("timestamp"))
        val pressure = BloodPressureRecord.Builder(metadata, at, 0, Pressure.fromMillimetersOfMercury(120.0),
            Pressure.fromMillimetersOfMercury(80.0), 0).build().ledgerRows().toList()
        assertEquals(2, pressure.map { it.getString("id") }.distinct().size)
    }

    @Test fun preservesSourceAndCheckpointsAcrossTheAndroidUpgrade() {
        val origin = "org.example.tracker"
        val metadata = Metadata.Builder().setId("other-record").setDataOrigin(DataOrigin.Builder().setPackageName(origin).build()).build()
        val entry = StepsRecord.Builder(metadata, at, at.plusSeconds(60), 10).build().ledgerRows().single()
        assertEquals(origin, entry.getString("source"))
        assertEquals("test/Steps", checkpointKey("test", Reading.Steps, false))
        assertNotEquals(checkpointKey("test", Reading.Steps, true), checkpointKey("test", Reading.Steps, false))
    }

    @Test fun journalWorksWithBothServerRepresentations() {
        val json = """[{"time":"$at","title":"Weight","value":"65","unit":"kg","kind":"Measurement","source":"Scale","note":"Morning"}]"""
        val html = """<section id="timeline"><article class="ledger-row"><h3>Weight</h3>
            <strong class="record-metric">65 <span>kg</span></strong><span class="kind-badge">Measurement</span>
            <span class="source-pill">Scale</span><p class="record-note">Morning</p><time datetime="$at"></time></article></section>"""
        assertEquals(decodeJournal(json), decodeWebJournal(html))
        assertEquals(emptyList<JournalEntry>(), decodeWebJournal("<section id='timeline'></section>"))
        assertThrows(java.io.IOException::class.java) { decodeWebJournal("<h1>Sign in</h1>") }
    }

    @Test fun onlyOpensTheLedgerOriginInsideTheJournal() {
        val origin = serverOrigin("Ledger.Example:8443/")
        assertEquals("https://ledger.example:8443", origin)
        assertEquals("https://ledger.example", serverOrigin("https://Ledger.example:443/"))
        assertTrue(isLedgerUrl("$origin/dashboard", origin))
        assertFalse(isLedgerUrl("http://ledger.example:8443/", origin))
        assertFalse(isLedgerUrl("https://ledger.example/", origin))
        assertFalse(isLedgerUrl("https://ledger.example.evil.invalid/", origin))
        assertFalse(isLedgerUrl("https://ledger.example@other.invalid/", origin))
        for (invalid in listOf("http://a.test", "https://user@a.test", "a.test/path", "a.test?x", "a.test:0")) {
            assertTrue(runCatching { serverOrigin(invalid) }.isFailure)
        }
        assertNotEquals(LedgerSession("session=test", origin).binding,
            LedgerSession("session=test", "https://other.example").binding)
    }

    @Test fun verifiesServerIdentityBeforeSignIn() {
        val origin = "https://ledger.example:8443"
        val descriptor = """{"application":"health-ledger","mobile_api":1,"site_url":"$origin"}"""
        verifyDescriptor(origin, descriptor)
        for (invalid in listOf(descriptor.replace("8443", "8000"), descriptor.replace("health-ledger", "other"),
            descriptor.replace(":1,", ":2,"), "{}")) {
            assertTrue(runCatching { verifyDescriptor(origin, invalid) }.isFailure)
        }
    }

    @Test fun deepLinkPrefillsTheServerAndWaitsForConfirmation() {
        val intent = android.content.Intent(context, ServerActivity::class.java)
            .setData(android.net.Uri.parse("healthledger://connect?server=https%3A%2F%2Fjournal.example%3A8443"))
        androidx.test.core.app.ActivityScenario.launch<ServerActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val views = ArrayList<android.view.View>()
                activity.window.decorView.findViewsWithText(views, "https://journal.example:8443", android.view.View.FIND_VIEWS_WITH_TEXT)
                val address = views.filterIsInstance<android.widget.EditText>().single()
                address.setText("https://journal.example/bad-path")
                address.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_GO)
                assertNotNull(address.error)
            }
        }
    }

    @Test fun registersAndCancelsAPersistedDailyJob() {
        assertNotNull(context.getSystemService(HealthConnectManager::class.java))
        try {
            assertTrue(schedule(context, true, 24))
            val job = context.getSystemService(JobScheduler::class.java).allPendingJobs.single()
            assertEquals(86400000L, job.intervalMillis)
            assertTrue(job.isPersisted)
        } finally { schedule(context, false) }
        assertTrue(context.getSystemService(JobScheduler::class.java).allPendingJobs.isEmpty())
    }

    @Test fun importsToTheDisposableLedgerThroughTheRealUploadRoute() {
        val token = InstrumentationRegistry.getArguments().getString("localSession")
        assumeTrue("Optional USB loopback fixture", token?.matches(Regex("[0-9a-f]{64}")) == true)
        val origin = "http://127.0.0.1:38651"
        val local = Ledger()
        verifyServer(origin)
        val session = LedgerSession("session=$token", origin)
        local.verify(session)
        val days = mapOf(java.time.LocalDate.parse("2018-04-21") to 1234L)
        local.sendSamsungDays(session, days)
        local.sendSamsungDays(session, days)
        assertEquals("1234 steps", JournalRepository(local).page(session, "Daily steps", 0).single().metric)
        local.addEntry(session,mapOf("kind" to "Note","title" to "Mobile entry test","value" to "",
            "unit" to "", "note" to "Probe", "at" to at.toString()))
        val result = JournalRepository(local).page(session,"Mobile entry test",0).single()
        assertEquals("Manual", result.source)
        assertEquals("Probe", result.note)
        for (count in listOf(45L, 50L)) local.upload(session, steps(count).ledgerRows().toList())
        local.addEntry(session, mapOf("kind" to "Measurement", "title" to "Fixture mass", "value" to "23",
            "unit" to "kg", "note" to "", "at" to at.toString()))
        val trends = TrendsRepository(local)
        val all = trends.load(session, null, TrendRange.All, true)
        val fixture = all.metrics.single { it.label == "Fixture mass (kg)" }
        val series = trends.load(session, fixture.id, TrendRange.All, false)
        assertEquals(23.0, series.mean!!, 0.0)
        assertEquals(1L, series.count)
        val expired = LedgerSession("session=" + "0".repeat(64), origin)
        assertThrows(SignInRequired::class.java) { local.upload(expired, steps(42).ledgerRows().toList()) }
    }
}
