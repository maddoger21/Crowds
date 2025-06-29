package com.example.crowds

import com.google.firebase.Timestamp


enum class PostStatus { PENDING, APPROVED }

data class Post(
    var id: String = "",
    var title: String = "",
    var description: String = "",
    var latitude: Double = 0.0,
    var longitude: Double = 0.0,
    var userName: String = "",
    var timestamp: Timestamp? = null,
    var status: PostStatus = PostStatus.PENDING
)