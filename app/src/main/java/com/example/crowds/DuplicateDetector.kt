package com.example.crowds

object DuplicateDetector {
    const val DUPLICATE_DISTANCE_KM = 0.2
    const val DUPLICATE_WINDOW_HOURS = 3L

    fun findDuplicateCandidateIds(
        latitude: Double,
        longitude: Double,
        posts: List<Post>,
        distanceThresholdKm: Double = DUPLICATE_DISTANCE_KM
    ): List<String> =
        posts.filter { post ->
            post.id.isNotBlank() &&
                    Utils.distanceKm(latitude, longitude, post.latitude, post.longitude) < distanceThresholdKm
        }.map { it.id }
}
