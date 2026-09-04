package com.rr.client.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun summarizeBenchmarkHistory_usesOnlyV21VerifiedRecords() {
        val sample = verifiedSample("SYSTEM")
        val v21 = EngineBenchmarkReport(
            benchmarkVersion = 3,
            nodeTag = "node",
            nodeServerMasked = "1.***.***.1",
            originalEngine = "SYSTEM",
            system = sample,
            hev = sample.copy(engine = "HEV")
        )
        val oldV2 = v21.copy(benchmarkVersion = 2)

        val summary = summarizeBenchmarkHistory(listOf(oldV2, v21))
        assertNotNull(summary)
        assertEquals(1, summary!!.runs)
        assertEquals(3, summary.system.httpsSuccessRounds)
        assertEquals(3, summary.system.proxyVerifiedRounds)
    }

    @Test
    fun summarizeBenchmarkHistory_rejectsUnverifiedV21Pair() {
        val verified = verifiedSample("SYSTEM")
        val unverified = verified.copy(
            engine = "HEV",
            httpsRounds = verified.httpsRounds.orEmpty().map { it.copy(proxyPathVerified = false) }
        )
        val report = EngineBenchmarkReport(
            benchmarkVersion = 3,
            nodeTag = "node",
            nodeServerMasked = "1.***.***.1",
            originalEngine = "HEV",
            system = verified,
            hev = unverified
        )

        assertNull(summarizeBenchmarkHistory(listOf(report)))
    }

    private fun verifiedSample(engine: String): EngineBenchmarkSample = EngineBenchmarkSample(
        engine = engine,
        restartMillis = 100L,
        rawIcmpMillis = null,
        httpsRounds = listOf(
            HttpsProbeRound(
                attempt = 1,
                success = true,
                firstByteMillis = 80L,
                bytesReceived = 2L * 1024L * 1024L,
                downloadBps = 4L * 1024L * 1024L,
                proxyAccountedDownloadBytes = 2L * 1024L * 1024L,
                proxyPathVerified = true
            ),
            HttpsProbeRound(
                attempt = 2,
                success = true,
                firstByteMillis = 90L,
                bytesReceived = 2L * 1024L * 1024L,
                downloadBps = 3L * 1024L * 1024L,
                proxyAccountedDownloadBytes = 2L * 1024L * 1024L,
                proxyPathVerified = true
            ),
            HttpsProbeRound(
                attempt = 3,
                success = true,
                firstByteMillis = 85L,
                bytesReceived = 2L * 1024L * 1024L,
                downloadBps = 5L * 1024L * 1024L,
                proxyAccountedDownloadBytes = 2L * 1024L * 1024L,
                proxyPathVerified = true
            )
        ),
        udpRounds = listOf(
            UdpProbeRound(attempt = 1, success = true, rttMillis = 90L),
            UdpProbeRound(attempt = 2, success = true, rttMillis = 92L),
            UdpProbeRound(attempt = 3, success = false, error = "timeout")
        ),
        processCpuMillis = 50L,
        processPssKb = 200_000,
        downloadBytesPerRound = 2L * 1024L * 1024L
    )
}
