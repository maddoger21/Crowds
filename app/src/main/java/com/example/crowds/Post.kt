package com.example.crowds

import com.google.firebase.Timestamp

data class Post(
    var id: String = "",
    var title: String = "",
    var description: String = "",
    var latitude: Double = 0.0,
    var longitude: Double = 0.0,
    var userName: String = "",
    var timestamp: Timestamp? = null
) {
    // Пустой конструктор для Firestore
    constructor() : this("", "", "", 0.0, 0.0, "", null)
}