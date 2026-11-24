package com.abrai.zengate

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

/** Pure alarm-time math for the next midnight reset (moved from PoolEngineTest). */
class GateAlarmsTest {
    @Test
    fun `next reset is next HH MM occurrence UTC`() {
        val zone = ZoneId.of("UTC")
        // 2001-09-09T01:46:40Z, reset 00:00 -> 2001-09-10T00:00:00Z.
        assertEquals(
            1_000_080_000_000L,
            GateAlarms.nextResetMs(1_000_000_000_000L, 0, 0, zone),
        )
        // Same instant, reset 06:00 -> today 2001-09-09T06:00:00Z.
        assertEquals(
            1_000_015_200_000L,
            GateAlarms.nextResetMs(1_000_000_000_000L, 6, 0, zone),
        )
    }
}
