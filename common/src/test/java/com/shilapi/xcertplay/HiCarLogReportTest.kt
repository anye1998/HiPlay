package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiCarLogReportTest {
    private fun environment(
        connectionCode: String = "042917",
        running: Boolean = true,
        audit: List<String> = listOf("--- device audit ---", "--- end of device audit ---"),
    ) = HiCarLogEnvironment(
        generated = "2026-11-13 14:30:00 +0800",
        applicationId = "com.anye1998.hiplay",
        versionName = "1.4.2",
        versionCode = 142L,
        androidRelease = "13",
        sdkInt = 33,
        manufacturer = "BYD",
        deviceModel = "DiLink 4.0",
        advertisedModel = "A1B2C3D4",
        identity = "0f1e2d3c4b5a6978",
        connectionCode = connectionCode,
        running = running,
        status = "blocked: HiChain native engine is not bundled",
        audit = audit,
    )

    @Test
    fun reportStatesTheBuildDeviceAndAdvertisedModel() {
        val report = hicarLogReport(environment(), listOf("line one"), droppedLines = 0)
        assertTrue(report.startsWith("HiCar receiver bring-up log"))
        assertTrue(report.contains("app: com.anye1998.hiplay 1.4.2 (142)"))
        assertTrue(report.contains("android: 13 (SDK 33), BYD DiLink 4.0"))
        assertTrue(report.contains("advertised model: A1B2C3D4"))
        assertTrue(report.contains("identity: 0f1e2d3c4b5a6978"))
        assertTrue(report.contains("status: blocked: HiChain native engine is not bundled"))
    }

    @Test
    fun connectionCodeIsIncludedAndMarkedSensitive() {
        val report = hicarLogReport(environment(connectionCode = "042917"), emptyList(), droppedLines = 0)
        assertTrue(report.contains("connection code: 042917"))
        assertTrue(report.contains("treat this file as sensitive"))
    }

    @Test
    fun emptyCodeIsLabelledRatherThanPrintedAsBlank() {
        val report = hicarLogReport(environment(connectionCode = ""), emptyList(), droppedLines = 0)
        assertTrue(report.contains("connection code: (none)"))
    }

    @Test
    fun stoppedReceiverIsNotDescribedAsRunning() {
        assertTrue(hicarLogReport(environment(running = false), emptyList(), 0).contains("receiver: stopped"))
        assertTrue(hicarLogReport(environment(running = true), emptyList(), 0).contains("receiver: running"))
    }

    @Test
    fun logLinesKeepTheirOrderOldestFirst() {
        val report = hicarLogReport(environment(), listOf("first", "second", "third"), droppedLines = 0)
        assertTrue(report.indexOf("first") < report.indexOf("second"))
        assertTrue(report.indexOf("second") < report.indexOf("third"))
        assertTrue(report.contains("--- log (3 lines, oldest first) ---"))
        assertTrue(report.trimEnd().endsWith("--- end of log ---"))
    }

    @Test
    fun droppedLinesAreReportedSoAShortLogIsNotReadAsAShortSession() {
        val truncated = hicarLogReport(environment(), listOf("tail"), droppedLines = 812)
        assertTrue(truncated.contains("note: the oldest 812 lines were dropped by the in-memory buffer"))
        // No note at all when nothing was dropped, so the common case stays uncluttered.
        assertFalse(hicarLogReport(environment(), listOf("tail"), droppedLines = 0).contains("were dropped"))
    }

    @Test
    fun anEmptySessionStillProducesAReadableReport() {
        val report = hicarLogReport(environment(), emptyList(), droppedLines = 0)
        assertTrue(report.contains("--- log (0 lines, oldest first) ---"))
        assertEquals(1, Regex("--- log \\(0 lines").findAll(report).count())
    }

    @Test
    fun auditComesBeforeTheLogSoTheReaderSeesTheDeviceFirst() {
        val report = hicarLogReport(
            environment(audit = listOf("--- device audit ---", "build: test", "--- end of device audit ---")),
            listOf("first"),
            droppedLines = 0,
        )
        assertTrue(report.contains("build: test"))
        assertTrue(report.indexOf("build: test") < report.indexOf("first"))
    }

    @Test
    fun aFailedAuditSaysSoInsteadOfLookingComplete() {
        val report = hicarLogReport(environment(audit = emptyList()), listOf("first"), droppedLines = 0)
        assertTrue(report.contains("unavailable: the audit threw before producing a line"))
    }
}
