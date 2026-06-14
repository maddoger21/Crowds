package com.example.crowds

import kotlin.math.min

data class TrustResult(
    val score: Double,
    val level: String
)

object TrustScoreCalculator {
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    fun calculateTrustScore(
        status: PostStatus,
        confirmationsCount: Int,
        reportsCount: Int,
        duplicateCount: Int,
        authorReputation: Double,
        createdAt: Long
    ): TrustResult {
        val baseScore = when (status) {
            PostStatus.PENDING -> 40.0
            PostStatus.APPROVED -> 60.0
            PostStatus.REJECTED -> 10.0
            PostStatus.ARCHIVED -> 30.0
        }
        val confirmationScore = min(confirmationsCount * 5.0, 25.0)
        val reportPenalty = min(reportsCount * 10.0, 35.0)
        val duplicatePenalty = min(duplicateCount * 10.0, 20.0)
        val authorScore = authorReputation.coerceIn(0.0, 20.0)
        val ageMs = (System.currentTimeMillis() - createdAt).coerceAtLeast(0L)
        val recencyPenalty = when {
            ageMs < DAY_MS -> 0.0
            ageMs <= 3 * DAY_MS -> 5.0
            else -> 10.0
        }

        val score = (baseScore + confirmationScore + authorScore -
                reportPenalty - duplicatePenalty - recencyPenalty)
            .coerceIn(0.0, 100.0)

        return TrustResult(score, trustLevel(score))
    }

    fun trustLevel(score: Double): String = when {
        score >= 75.0 -> "Высокая"
        score >= 50.0 -> "Средняя"
        score >= 30.0 -> "Низкая"
        else -> "Сомнительная"
    }
}
