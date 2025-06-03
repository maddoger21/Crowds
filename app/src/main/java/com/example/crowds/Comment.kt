package com.example.crowds

import com.google.firebase.Timestamp

data class Comment(
    var id: String = "",
    var userName: String = "",
    var text: String = "",
    var timestamp: Timestamp? = null
) {
    constructor() : this("", "", "", null)
}