package com.example.locationsenderapp.service

import com.example.locationsenderapp.BuildConfig
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

interface SendGridApi {
    @POST("v3/mail/send")
    suspend fun sendEmail(@Body request: SendGridRequest)
}

data class SendGridEmail(val email: String)
data class Personalization(val to: List<SendGridEmail>)
data class Content(val type: String = "text/plain", val value: String)
data class SendGridRequest(
    val personalizations: List<Personalization>,
    val from: SendGridEmail,
    val subject: String,
    val content: List<Content>
)

object EmailApiService {
    fun create(): SendGridApi {
        val authInterceptor = Interceptor { chain ->
            val newReq = chain.request().newBuilder()
                .addHeader("Authorization", "Bearer ${BuildConfig.SENDGRID_API_KEY}")
                .addHeader("Content-Type", "application/json")
                .build()
            chain.proceed(newReq)
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl("https://api.sendgrid.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .client(client)
            .build()
        return retrofit.create(SendGridApi::class.java)
    }
}