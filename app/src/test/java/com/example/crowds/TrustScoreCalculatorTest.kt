package com.example.crowds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustScoreCalculatorTest {
    private val now = System.currentTimeMillis()

    @Test
    fun scoreGrowsWithConfirmations() {
        val base = TrustScoreCalculator.calculateTrustScore(PostStatus.PENDING, 0, 0, 0, 5.0, now)
        val confirmed = TrustScoreCalculator.calculateTrustScore(PostStatus.PENDING, 3, 0, 0, 5.0, now)

        assertTrue(confirmed.score > base.score)
    }

    @Test
    fun scoreFallsWithReports() {
        val base = TrustScoreCalculator.calculateTrustScore(PostStatus.PENDING, 0, 0, 0, 5.0, now)
        val reported = TrustScoreCalculator.calculateTrustScore(PostStatus.PENDING, 0, 2, 0, 5.0, now)

        assertTrue(reported.score < base.score)
    }

    @Test
    fun scoreFallsWithDuplicates() {
        val base = TrustScoreCalculator.calculateTrustScore(PostStatus.PENDING, 0, 0, 0, 5.0, now)
        val duplicate = TrustScoreCalculator.calculateTrustScore(PostStatus.PENDING, 0, 0, 2, 5.0, now)

        assertTrue(duplicate.score < base.score)
    }

    @Test
    fun scoreIsClampedToRange() {
        val high = TrustScoreCalculator.calculateTrustScore(PostStatus.APPROVED, 100, 0, 0, 100.0, now)
        val low = TrustScoreCalculator.calculateTrustScore(PostStatus.REJECTED, 0, 100, 100, 0.0, now)

        assertEquals(100.0, high.score, 0.0)
        assertEquals(0.0, low.score, 0.0)
    }

    @Test
    fun trustLevelIsResolvedByScore() {
        assertEquals("Высокая", TrustScoreCalculator.trustLevel(75.0))
        assertEquals("Средняя", TrustScoreCalculator.trustLevel(50.0))
        assertEquals("Низкая", TrustScoreCalculator.trustLevel(30.0))
        assertEquals("Сомнительная", TrustScoreCalculator.trustLevel(29.9))
    }
}
