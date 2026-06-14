package com.example.crowds

import org.junit.Assert.assertEquals
import org.junit.Test

class DuplicateDetectorTest {
    @Test
    fun findsPostsInsideDistanceThreshold() {
        val posts = listOf(
            Post(id = "near", latitude = 55.7559, longitude = 37.6173),
            Post(id = "far", latitude = 55.7658, longitude = 37.6173)
        )

        val ids = DuplicateDetector.findDuplicateCandidateIds(55.7558, 37.6173, posts)

        assertEquals(listOf("near"), ids)
    }
}
