package com.example.crowds

import com.google.firebase.Timestamp

data class PostConfirmation(
    var userUid: String = "",
    var userEmail: String = "",
    var userName: String = "",
    var timestamp: Timestamp? = null
)
