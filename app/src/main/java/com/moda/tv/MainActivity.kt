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

    /**
     * 整屏沉浸。
     *
     * 只在 onCreate 里 hide 一次是不够的：下拉通知栏、切后台再回来，系统栏都会被放出来，
     * 顶部那行状态栏直接压住工具栏、底部导航条压住海报。
     * 所以窗口每次重新获得焦点都再收一次。
     */
    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // TV 上系统栏只会挡住海报墙，整屏沉浸由这里统一关掉
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

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
