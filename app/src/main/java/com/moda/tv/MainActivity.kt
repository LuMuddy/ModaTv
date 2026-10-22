package com.moda.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.moda.tv.ui.AppRoot
import com.moda.tv.ui.theme.ModaColors
import com.moda.tv.ui.theme.ModaTvTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // TV 上系统栏只会挡住海报墙，整屏沉浸由这里统一关掉
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            ModaTvTheme {
                // 先铺底色，避免首帧组合前出现白闪
                Box(Modifier.fillMaxSize().background(ModaColors.Bg)) {
                    AppRoot()
                }
            }
        }
    }
}
