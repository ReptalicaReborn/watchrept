package com.watchrept.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.watchrept.app.ui.DiscordApp

class MainActivity : ComponentActivity() {
    private val viewModel: DiscordViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DiscordApp(viewModel) }
    }
}
