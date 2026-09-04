package com.rr.client.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class LabModelsTest {
    @Test
    fun calculateMetricStats_returnsStableSummary() {
        val stats = calculateMetricStats(listOf(10L, 20L, 30L, 40L))
        assertNotNull(stats)
        stats!!
        assertEquals(4, stats.count)
        assertEquals(25.0, stats.average, 0.0001)
        assertEquals(25.0, stats.median, 0.0001)
        assertEquals(40.0, stats.p95, 0.0001)
        assertEquals(11.1803, stats.stdDev, 0.001)
    }

    @Test
    fun summarizeBenchmarkHistory_ignoresLegacyRecords() {
        val sample = EngineBenchmarkSample(
            engine = "SYSTEM",
            restartMillis = 100L,
            rawIcmpMillis = 10L,
            httpsRounds = listOf(
                HttpsProbeRound(
                    attempt = 1,
                    success = true,
                    firstByteMillis = 80L,
                    bytesReceived = 2L * 1024L * 1024L,
                    downloadBps = 4L * 1024L * 1024L,
                    proxyAccountedDownloadBytes = 2L * 1024L * 1024L,
                    proxyPathVerified = true
                )
            ),
            udpRounds = listOf(UdpProbeRound(attempt = 1, success = true, rttMillis = 90L)),
            processCpuMillis = 50L,
            processPssKb = 200_000,
            downloadBytesPerRound = 2L * 1024L * 1024L
        )
        val v2 = EngineBenchmarkReport(
            benchmarkVersion = 2,
            nodeTag = "node",
            nodeServerMasked = "1.***.***.1",
            originalEngine = "SYSTEM",
            system = sample,
            hev = sample.copy(engine = "HEV")
        )
        val legacy = v2.copy(benchmarkVersion = 1)

        val summary = summarizeBenchmarkHistory(listOf(legacy, v2))
        assertNotNull(summary)
        assertEquals(1, summary!!.runs)
        assertEquals(1, summary.system.httpsSuccessRounds)
        assertEquals(1, summary.system.proxyVerifiedRounds)
    }
}
