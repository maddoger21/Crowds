package com.example.crowds

import com.google.firebase.Timestamp

data class PostReport(
    var userUid: String = "",
    var userEmail: String = "",
    var userName: String = "",
    var reason: String = "",
    var timestamp: Timestamp? = null
)
