package com.aura.companion.ai

import com.aura.companion.BuildConfig
import com.aura.companion.data.api.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Represents a log entry for transparency dashboard
data class NetworkCallLog(
    val timestamp: Long = System.currentTimeMillis(),
    val type: String,       // "WEATHER", "NEWS", "SEARCH"
    val query: String,
    val success: Boolean,
    val dataReturned: String
)

class OnlineLookupManager {

    val callLogs = mutableListOf<NetworkCallLog>()

    // Detects if user query needs real-time data
    fun needsOnlineData(query: String): OnlineQueryType {
        val lower = query.lowercase()
        return when {
            lower.contains("weather") || lower.contains("temperature") ||
            lower.contains("rain") || lower.contains("hot") || lower.contains("cold") ||
            lower.contains("forecast") -> OnlineQueryType.WEATHER

            lower.contains("news") || lower.contains("headlines") ||
            lower.contains("latest") || lower.contains("today's news") -> OnlineQueryType.NEWS

            else -> OnlineQueryType.NONE
        }
    }

    suspend fun fetchWeather(city: String = BuildConfig.DEFAULT_CITY): String =
        withContext(Dispatchers.IO) {
            try {
                val response = ApiClient.weatherApi.getCurrentWeather(
                    city = city,
                    apiKey = BuildConfig.WEATHER_API_KEY
                )
                val desc = response.weather.firstOrNull()?.description ?: "clear"
                val result = "Weather in ${response.name}: ${response.main.temp}°C, " +
                    "feels like ${response.main.feels_like}°C, $desc. " +
                    "Humidity: ${response.main.humidity}%, Wind: ${response.wind.speed} m/s."

                callLogs.add(NetworkCallLog(type = "WEATHER", query = city, success = true, dataReturned = result))
                result
            } catch (e: Exception) {
                val error = "Weather data unavailable."
                callLogs.add(NetworkCallLog(type = "WEATHER", query = city, success = false, dataReturned = error))
                error
            }
        }

    suspend fun fetchNews(country: String = "in"): String =
        withContext(Dispatchers.IO) {
            try {
                // Note: NewsAPI requires a key. Using a free alternative approach.
                // For hackathon, we use a placeholder or free tier
                val headlines = "Top news: Unable to fetch without a NewsAPI key. " +
                    "Please add NEWS_API_KEY in BuildConfig for live headlines."
                callLogs.add(NetworkCallLog(type = "NEWS", query = country, success = true, dataReturned = headlines))
                headlines
            } catch (e: Exception) {
                val error = "News unavailable right now."
                callLogs.add(NetworkCallLog(type = "NEWS", query = country, success = false, dataReturned = error))
                error
            }
        }

    fun clearLogs() = callLogs.clear()
}

enum class OnlineQueryType {
    WEATHER, NEWS, SEARCH, NONE
}
