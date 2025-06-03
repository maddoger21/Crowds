package com.example.crowds

import android.app.Application
import com.google.firebase.FirebaseApp

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Явная инициализация, обычно не обязательна
        FirebaseApp.initializeApp(this)
    }
}