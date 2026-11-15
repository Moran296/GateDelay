package app.gatedelay

import app.gatedelay.data.GateRepository
import app.gatedelay.model.GateRole
import app.gatedelay.model.LinkedCredentials
import app.gatedelay.model.SequenceConfig
import app.gatedelay.model.SequenceKind
import app.gatedelay.palgate.PalgateApi
import app.gatedelay.sequence.SequenceRunner
import app.gatedelay.sequence.StepStatus
import app.gatedelay.storage.InMemoryStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SequenceRunnerTest {

    private val devicesJson = """
        {"devices":[
          {"id":"MAIN1","name1":"Main gate","address":"Herzl 1"},
          {"id":"INOUT","name1":"Entry","customName2":"Exit"}
        ],"err":"","status":"ok"}
    """.trimIndent()

    /** Records opened gates; `failDevice` answers with PalGate's "not authorized" error. */
    private class Fixture(devicesJson: String, scheduler: TestCoroutineScheduler, failDevice: String? = null) {
        val opened = mutableListOf<String>()
        val tokens = mutableListOf<String?>()
        val engine = MockEngine.create {
          dispatcher = StandardTestDispatcher(scheduler)
          addHandler { req ->
            tokens += req.headers["x-bt-token"]
            val path = req.url.encodedPath
            when {
                path.endsWith("/devices") -> respond(devicesJson, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                path.contains("/open-gate") -> {
                    val id = path.substringAfter("/device/").substringBefore("/")
                    if (id == failDevice) {
                        respond("""{"err":"not authorized","msg":"Secondary device not authorized!","status":"failed"}""", HttpStatusCode.BadRequest)
                    } else {
                        opened += "$id:${req.url.parameters["outputNum"]}"
                        respond("""{"err":"","status":"ok"}""", HttpStatusCode.OK)
                    }
                }
                else -> respond("{}", HttpStatusCode.NotFound)
            }
          }
        }
        val store = InMemoryStore()
        val repo = GateRepository(store, store, PalgateApi(HttpClient(engine) { install(HttpTimeout); expectSuccess = false }))
    }

    private suspend fun Fixture.setUpGates() {
        repo.addAccount(LinkedCredentials("972501234567", "00112233445566778899aabbccddeeff", 1))
        val gates = repo.listAllGates()
        assertEquals(3, gates.options.size, "devices + second output: ${gates.options}")
        repo.assignGate(GateRole.MAIN, gates.options.first { it.ref.deviceId == "MAIN1" }.ref)
        repo.assignGate(GateRole.ENTRY, gates.options.first { it.ref.deviceId == "INOUT" && it.ref.outputNum == 1 }.ref)
        repo.assignGate(GateRole.EXIT, gates.options.first { it.ref.deviceId == "INOUT" && it.ref.outputNum == 2 }.ref)
    }

    @Test
    fun enterOpensMainThenWaitsThenEntry() = runTest {
        val f = Fixture(devicesJson, testScheduler)
        f.setUpGates()
        val runner = SequenceRunner(f.repo, this)

        runner.start(SequenceKind.ENTER)
        waitFor { f.opened.size == 1 }
        assertEquals(listOf("MAIN1:1"), f.opened)

        advanceTimeBy(9_000); runCurrent()
        assertEquals(1, f.opened.size, "second gate must wait for the delay")
        assertTrue(runner.state.value.secondsLeft in 1..2)

        advanceTimeBy(1_500); runCurrent()
        waitFor { f.opened.size == 2 }
        assertEquals(listOf("MAIN1:1", "INOUT:1"), f.opened)
        advanceUntilIdle()
        assertTrue(runner.state.value.finishedOk, runner.state.value.toString())
        assertTrue(f.tokens.all { it != null && it.length == 46 })
    }

    @Test
    fun exitUses27SecondsAndSkipWaitOpensImmediately() = runTest {
        val f = Fixture(devicesJson, testScheduler)
        f.setUpGates()
        val runner = SequenceRunner(f.repo, this)

        runner.start(SequenceKind.EXIT)
        waitFor { f.opened.size == 1 }
        assertEquals(listOf("INOUT:2"), f.opened)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(26, runner.state.value.secondsLeft)

        runner.skipWait(); runCurrent()
        waitFor { f.opened.size == 2 }
        assertEquals(listOf("INOUT:2", "MAIN1:1"), f.opened)
    }

    @Test
    fun firstGateFailureStopsSequence() = runTest {
        val f = Fixture(devicesJson, testScheduler, failDevice = "MAIN1")
        f.setUpGates()
        val runner = SequenceRunner(f.repo, this)

        runner.start(SequenceKind.ENTER)
        waitFor { !runner.state.value.running }
        advanceUntilIdle()
        val s = runner.state.value
        assertTrue(f.opened.isEmpty())
        assertEquals(StepStatus.FAILED, s.steps[0].status)
        assertEquals(StepStatus.SKIPPED, s.steps[1].status)
        assertNotNull(s.error)
        assertTrue(s.error!!.contains("Linked Device"), s.error)
    }

    @Test
    fun cancelDuringWaitDoesNotOpenSecondGate() = runTest {
        val f = Fixture(devicesJson, testScheduler)
        f.setUpGates()
        val runner = SequenceRunner(f.repo, this)

        runner.start(SequenceKind.ENTER)
        waitFor { f.opened.size == 1 }
        advanceTimeBy(3_000); runCurrent()
        runner.cancel()
        advanceUntilIdle()
        assertEquals(1, f.opened.size)
        assertFalse(runner.state.value.running)
        assertEquals(StepStatus.SKIPPED, runner.state.value.steps[1].status)
    }

    @Test
    fun customSequenceAndDoubleStartIgnored() = runTest {
        val f = Fixture(devicesJson, testScheduler)
        f.setUpGates()
        f.repo.setSequence(SequenceKind.ENTER, SequenceConfig(GateRole.ENTRY, 0, GateRole.EXIT))
        val runner = SequenceRunner(f.repo, this)

        assertNotNull(runner.start(SequenceKind.ENTER))
        assertEquals(null, runner.start(SequenceKind.EXIT))
        waitFor { f.opened.size == 2 }
        assertEquals(listOf("INOUT:1", "INOUT:2"), f.opened)
    }

    @Test
    fun singleGateOpen() = runTest {
        val f = Fixture(devicesJson, testScheduler)
        f.setUpGates()
        val runner = SequenceRunner(f.repo, this)
        runner.openSingle(GateRole.EXIT)
        waitFor { f.opened.size == 1 }
        assertEquals(listOf("INOUT:2"), f.opened)
        advanceUntilIdle()
    }

    @Test
    fun configPersistsAcrossRepositoryInstances() = runTest {
        val f = Fixture(devicesJson, testScheduler)
        f.setUpGates()
        val again = GateRepository(f.store, f.store, PalgateApi(HttpClient(f.engine)))
        assertEquals(f.repo.config.value, again.config.value)
        again.removeAccount("972501234567")
        assertTrue(again.config.value.gates.isEmpty())
    }

    /** Runs everything due at the current virtual time, then checks [cond]. */
    private suspend fun TestScope.waitFor(cond: () -> Boolean) {
        repeat(50) {
            runCurrent()
            if (cond()) return
            yield()
        }
        throw AssertionError("condition not met")
    }
}
