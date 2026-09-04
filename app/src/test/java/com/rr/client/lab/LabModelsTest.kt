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
    fun summarizeBenchmarkHistory_usesOnlyV25VerifiedRecords() {
        val system = verifiedSample("SYSTEM")
        val hev = verifiedSample("HEV")
        val v25 = EngineBenchmarkReport(
            benchmarkVersion = 7,
            nodeTag = "node",
            nodeServerMasked = "1.***.***.1",
            originalEngine = "SYSTEM",
            helperPackage = "RRBOX UID natural VPN routing + fixed IPv4 bootstrap",
            system = system,
            hev = hev
        )
        val oldV24 = v25.copy(benchmarkVersion = 6)

        val summary = summarizeBenchmarkHistory(listOf(oldV24, v25))
        assertNotNull(summary)
        assertEquals(1, summary!!.runs)
        assertEquals(3, summary.system.httpsSuccessRounds)
        assertEquals(3, summary.system.proxyVerifiedRounds)
        assertEquals(3, summary.hev.nativeVerifiedRounds)
    }

    @Test
    fun summarizeBenchmarkHistory_rejectsHevWithoutNativeTunValidation() {
        val system = verifiedSample("SYSTEM")
        val hevBase = verifiedSample("HEV")
        val hev = hevBase.copy(
            httpsRounds = hevBase.httpsRounds.orEmpty().map {
                it.copy(nativePathVerified = false)
            }
        )
        val report = EngineBenchmarkReport(
            benchmarkVersion = 7,
            nodeTag = "node",
            nodeServerMasked = "1.***.***.1",
            originalEngine = "HEV",
            helperPackage = "RRBOX UID natural VPN routing + fixed IPv4 bootstrap",
            system = system,
            hev = hev
        )

        assertNull(summarizeBenchmarkHistory(listOf(report)))
    }

    @Test
    fun sampleStatistics_ignoreUnverifiedRounds() {
        val sample = verifiedSample("SYSTEM").copy(
            httpsRounds = verifiedSample("SYSTEM").httpsRounds.orEmpty() +
                HttpsProbeRound(
                    attempt = 4,
                    success = true,
                    firstByteMillis = 9_999L,
                    downloadBps = 1L,
                    proxyPathVerified = false
                )
        )

        assertEquals(3, sample.proxyPathVerifiedCount)
        assertEquals(85L, sample.httpsFirstByteMedianMillis)
        assertEquals(4L * 1024L * 1024L, sample.httpsDownloadMedianBps)
    }

    @Test
    fun v25TimingMetrics_excludeDnsAndUseOnlyVerifiedRounds() {
        val sample = verifiedSample("SYSTEM")
        assertNull(sample.httpsDnsMedianMillis)
        assertEquals(6L, sample.httpsTcpMedianMillis)
        assertEquals(210L, sample.httpsTlsMedianMillis)
        assertEquals(85L, sample.httpsFirstByteMedianMillis)
    }

    private fun verifiedSample(engine: String): EngineBenchmarkSample {
        val hev = engine == "HEV"
        fun round(
            attempt: Int,
            tcp: Long,
            tls: Long,
            first: Long,
            rate: Long
        ): HttpsProbeRound = HttpsProbeRound(
            attempt = attempt,
            success = true,
            dnsMillis = null,
            tcpConnectMillis = tcp,
            tlsMillis = tls,
            firstByteMillis = first,
            bytesReceived = 2L * 1024L * 1024L,
            downloadBps = rate,
            proxyAccountedDownloadBytes = 2L * 1024L * 1024L,
            nativeAccountedDownloadBytes = if (hev) 2L * 1024L * 1024L else 0L,
            nativePathVerified = hev,
            proxyPathVerified = true,
            protocol = "VPN-UID[tun0#123]/fixed=104.16.1.1/http/1.1"
        )

        return EngineBenchmarkSample(
            engine = engine,
            restartMillis = 100L,
            rawIcmpMillis = null,
            httpsRounds = listOf(
                round(1, 5L, 200L, 80L, 4L * 1024L * 1024L),
                round(2, 6L, 210L, 90L, 3L * 1024L * 1024L),
                round(3, 7L, 220L, 85L, 5L * 1024L * 1024L)
            ),
            udpRounds = emptyList(),
            processCpuMillis = 50L,
            processPssKb = 200_000,
            downloadBytesPerRound = 2L * 1024L * 1024L
        )
    }
}
