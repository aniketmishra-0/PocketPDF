package com.renameapk.pdfzip.reader.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.renameapk.pdfzip.reader.ui.theme.ReaderTheme
import com.renameapk.pdfzip.reader.viewmodel.LibraryViewModel
import com.renameapk.pdfzip.reader.viewmodel.ReaderViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ReaderActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val readerViewModel: ReaderViewModel = hiltViewModel()
            val libraryViewModel: LibraryViewModel = hiltViewModel()
            val state by readerViewModel.uiState.collectAsStateWithLifecycle()
            ReaderTheme(darkTheme = state.settings.darkMode) {
                SystemBarsEffect(fullscreen = state.settings.fullscreen && !state.chromeVisible)
                ReaderApp(
                    initialUri = initialPdfUri(),
                    readerViewModel = readerViewModel,
                    libraryViewModel = libraryViewModel,
                )
            }
        }
    }

    private fun initialPdfUri(): Uri? =
        intent?.data
            ?: intent?.getParcelableExtra(Intent.EXTRA_STREAM)
            ?: intent?.getStringExtra("pdf_uri")?.let(Uri::parse)
}

@Composable
private fun SystemBarsEffect(fullscreen: Boolean) {
    val activity = LocalContext.current as? ComponentActivity ?: return
    DisposableEffect(fullscreen) {
        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        if (fullscreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

