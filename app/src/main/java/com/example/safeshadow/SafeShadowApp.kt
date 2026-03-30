package com.example.safeshadow

import android.app.Application

class SafeShadowApp : Application() {

    companion object {
        const val CHANNEL_ID = "safety_channel_id"
        const val CHANNEL_NAME = "Safety Service"
    }

    override fun onCreate() {
        super.onCreate()
    }
}