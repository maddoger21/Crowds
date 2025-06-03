package com.example.crowds

import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

object Utils {
    // Форматирование миллисекунд в строку "dd.MM.yyyy HH:mm"
    fun formatDate(timeInMillis: Long): String {
        val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        return sdf.format(Date(timeInMillis))
    }

    // Расчёт расстояния по формуле Хаверсина (в километрах)
    fun distanceKm(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val R = 6371.0 // радиус Земли в км
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) *
                cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }
}