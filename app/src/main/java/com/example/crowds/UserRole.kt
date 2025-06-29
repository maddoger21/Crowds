package com.example.crowds

// Новый enum для ролей пользователей
enum class UserRole { USER, ADMIN }

// Модель профиля пользователя для хранения в Firestore
data class AppUser(
    val uid: String = "",
    val name: String = "",
    val role: UserRole = UserRole.USER
)