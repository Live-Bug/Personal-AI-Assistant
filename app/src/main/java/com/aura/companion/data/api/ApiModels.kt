package com.aura.companion.data.api

import retrofit2.http.GET
import retrofit2.http.Query

data class WeatherResponse(
    val name: String,
    val main: Main,
    val weather: List<WeatherDesc>,
    val wind: Wind
) {
    data class Main(val temp: Double, val feels_like: Double, val humidity: Int)
    data class WeatherDesc(val description: String, val icon: String)
    data class Wind(val speed: Double)
}

data class NewsResponse(
    val articles: List<Article>
) {
    data class Article(val title: String, val description: String?, val source: Source)
    data class Source(val name: String)
}

interface WeatherApi {
    @GET("weather")
    suspend fun getCurrentWeather(
        @Query("q") city: String,
        @Query("appid") apiKey: String,
        @Query("units") units: String = "metric"
    ): WeatherResponse
}

interface NewsApi {
    @GET("v2/top-headlines")
    suspend fun getTopHeadlines(
        @Query("country") country: String = "in",
        @Query("pageSize") pageSize: Int = 5,
        @Query("apiKey") apiKey: String
    ): NewsResponse
}
