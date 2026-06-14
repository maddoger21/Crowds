package com.example.crowds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UtilsTest {
    @Test
    fun distanceKmIsZeroForSamePoint() {
        val distance = Utils.distanceKm(55.7558, 37.6173, 55.7558, 37.6173)

        assertEquals(0.0, distance, 0.0001)
    }

    @Test
    fun distanceKmCalculatesKnownNearbyDistance() {
        val distance = Utils.distanceKm(55.7558, 37.6173, 55.7568, 37.6173)

        assertTrue(distance in 0.10..0.12)
    }
}
