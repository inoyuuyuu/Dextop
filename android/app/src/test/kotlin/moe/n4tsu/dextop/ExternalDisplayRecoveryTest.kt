package moe.n4tsu.dextop

import java.nio.file.Files
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExternalDisplayRecoveryTest {
    private fun simulatedDisplay(script: (File) -> String, identity: String = "local:123",
        id: Int = 2, confirmed: Boolean = false, failSize: Boolean = false): Pair<Int, List<String>> {
        val directory = Files.createTempDirectory("dextop-recovery-test").toFile()
        try {
            File(directory, "dumpsys").apply {
                writeText("#!/bin/sh\nprintf '%s\\n' 'mBaseDisplayInfo=DisplayInfo{displayId $id, uniqueId \"$identity\"}'\n")
                setExecutable(true)
            }
            val output = File(directory, "calls")
            File(directory, "wm").apply {
                writeText("#!/bin/sh\nprintf '%s\\n' \"\$*\" >> \"\$CALL_LOG\"\n" +
                    if (failSize) "[ \"\$1\" != size ]\n" else "exit 0\n")
                setExecutable(true)
            }
            if (confirmed) File(directory, "decision").mkdir()
            val process = ProcessBuilder("sh", "-c", script(directory)).apply {
                environment()["PATH"] = directory.absolutePath + ":" + System.getenv("PATH")
                environment()["CALL_LOG"] = output.absolutePath
                redirectErrorStream(true)
            }.start()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Recovery script hung")
            return process.exitValue() to if (output.exists()) output.readLines() else emptyList()
        } finally { directory.deleteRecursively() }
    }

    private fun rollback(size: Boolean = true) = ExternalDisplayRecovery.restore(
        2, "local:123", size, true, 1600, 900, 200
    )

    @Test fun expiryRestoresBothExistingOverrides() {
        val result = simulatedDisplay({ ExternalDisplayRecovery.watchdog(it.path, rollback(), 0) })
        assertEquals(0, result.first)
        assertEquals(listOf("size 1600x900 -d 2", "density 200 -d 2"), result.second)
    }

    @Test fun confirmedTrialIsNotRolledBack() {
        val result = simulatedDisplay({ ExternalDisplayRecovery.watchdog(it.path, rollback(), 0) }, confirmed = true)
        assertTrue(result.second.isEmpty())
    }

    @Test fun recycledDisplayIdCannotChangeAnotherMonitor() {
        val result = simulatedDisplay({ rollback() }, identity = "local:999")
        assertTrue(result.second.isEmpty())
    }

    @Test fun displayTwoNeverMatchesDisplayTwenty() {
        val result = simulatedDisplay({ rollback() }, id = 20)
        assertTrue(result.second.isEmpty())
    }

    @Test fun dpiOnlyTrialDoesNotSendASizeChange() {
        val result = simulatedDisplay({ rollback(size = false) })
        assertEquals(listOf("density 200 -d 2"), result.second)
    }

    @Test fun densityRestorationStillRunsIfSizeRestorationFails() {
        val result = simulatedDisplay({ rollback() }, failSize = true)
        assertEquals(1, result.first)
        assertEquals(2, result.second.size)
    }
}
