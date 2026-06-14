package com.example.crowds

data class UserReputation(
    var uid: String = "",
    var email: String = "",
    var displayName: String = "",
    var reputationScore: Double = 5.0,
    var postsCreated: Int = 0,
    var confirmationsMade: Int = 0,
    var reportsMade: Int = 0
)
