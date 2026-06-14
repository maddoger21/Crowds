package com.example.crowds

import com.google.firebase.Timestamp


enum class PostStatus {
    PENDING,
    APPROVED,
    REJECTED,
    ARCHIVED
}

enum class PostCategory(val displayName: String, val markerColor: Int, val markerIcon: Int) {
    FIRE(
        "\u041f\u043e\u0436\u0430\u0440",
        0xFFE53935.toInt(),
        android.R.drawable.ic_dialog_alert
    ),
    ROAD_ACCIDENT(
        "\u0414\u0422\u041f",
        0xFFFF9800.toInt(),
        android.R.drawable.ic_menu_directions
    ),
    UTILITY_ACCIDENT(
        "\u041a\u043e\u043c\u043c\u0443\u043d\u0430\u043b\u044c\u043d\u0430\u044f \u0430\u0432\u0430\u0440\u0438\u044f",
        0xFF1976D2.toInt(),
        android.R.drawable.ic_menu_manage
    ),
    DANGER_ZONE(
        "\u041e\u043f\u0430\u0441\u043d\u0430\u044f \u0437\u043e\u043d\u0430",
        0xFF7B1FA2.toInt(),
        android.R.drawable.stat_notify_error
    ),
    HOUSEHOLD(
        "\u0411\u044b\u0442\u043e\u0432\u0430\u044f \u0441\u0438\u0442\u0443\u0430\u0446\u0438\u044f",
        0xFF388E3C.toInt(),
        android.R.drawable.ic_menu_info_details
    ),
    OTHER(
        "\u0414\u0440\u0443\u0433\u043e\u0435",
        0xFF616161.toInt(),
        android.R.drawable.ic_menu_help
    );

    companion object {
        fun fromPosition(position: Int): PostCategory =
            entries.getOrElse(position) { OTHER }
    }
}

data class Post(
    var id: String = "",
    var title: String = "",
    var description: String = "",
    var category: PostCategory = PostCategory.OTHER,
    var latitude: Double = 0.0,
    var longitude: Double = 0.0,
    var authorUid: String = "",
    var userName: String = "",
    var timestamp: Timestamp? = null,
    var status: PostStatus = PostStatus.PENDING
)
