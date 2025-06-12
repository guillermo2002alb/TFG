package com.example.locationsenderapp.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.locationsenderapp.data.StationDatabase
import com.example.locationsenderapp.data.StationRepository
import java.util.concurrent.TimeUnit

class StatsWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_EMAIL = "user_email"
    }

    override suspend fun doWork(): Result {
        val email = inputData.getString(KEY_EMAIL) ?: return Result.failure()
        val db = StationDatabase.getDatabase(applicationContext)
        val repo = StationRepository(db.stationDataDao())

        val end = System.currentTimeMillis()
        val start = end - TimeUnit.DAYS.toMillis(1)

        val stations = repo.getStationNames()
        val summary = StringBuilder()
        summary.append("Resumen diario de calidad del aire\n\n")

        for (station in stations) {
            val stats = repo.getAverageStats(station, start, end)
            summary.append("$station -> PM2.5: ${"%.1f".format(stats.pm25)}, PM10: ${"%.1f".format(stats.pm10)}, NO2: ${"%.1f".format(stats.no2)}\n")
        }

        return try {
            val service = EmailApiService.create()
            val request = SendGridRequest(
                personalizations = listOf(Personalization(listOf(SendGridEmail(email)))),
                from = SendGridEmail(email),
                subject = "Resumen diario BreatheSafe",
                content = listOf(Content(value = summary.toString()))
            )
            service.sendEmail(request)
            Result.success()
        } catch (e: Exception) {
            Log.e("StatsWorker", "Error enviando email", e)
            Result.retry()
        }
    }
}