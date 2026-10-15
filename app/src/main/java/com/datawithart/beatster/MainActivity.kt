package com.datawithart.beatster

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.datawithart.beatster.ui.StudioScreen
import com.datawithart.beatster.ui.theme.BeatsterTheme

class MainActivity : ComponentActivity() {
    private val studioViewModel: StudioViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BeatsterTheme(dynamicColor = false) {
                StudioScreen(viewModel = studioViewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        studioViewModel.onVisibilityChanged(true)
    }

    override fun onStop() {
        studioViewModel.onVisibilityChanged(false)
        super.onStop()
    }
}
