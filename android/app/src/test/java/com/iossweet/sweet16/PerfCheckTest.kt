package com.iossweet.sweet16

import org.junit.Assert.assertTrue
import org.junit.Test

class PerfCheckTest {
    @Test
    fun averagesTheSamples() {
        val log = "x\nSWEET TOP 10s: backboardd 90% SpringBoard 30% total 150%\nfoo\n" +
            "SWEET TOP 10s: backboardd 70% mds 50% total 170%\n"
        val r = PerfCheck.report(log, 6)
        assertTrue(r, r.contains("  80%  backboardd"))
        assertTrue(r, r.contains("  25%  mds"))
        assertTrue(r, r.contains("160% of 600%"))
    }
}
