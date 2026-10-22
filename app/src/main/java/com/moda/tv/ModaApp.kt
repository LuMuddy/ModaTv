package com.moda.tv

import android.app.Application
import com.moda.tv.data.Api
import com.moda.tv.data.local.ApiSettingsStoreImpl
import kotlinx.coroutines.runBlocking

class ModaApp : Application() {

    /**
     * 代理网址是持久化的，必须在任何请求发出之前注入 [Api.BASE]。
     * TvViewModel.init 一构造就 loadHome()，所以"异步读完再赋值"会留下一个
     * 用出厂地址抢跑的窗口——用户改过代理之后，每次冷启动首页都可能先打错地址再失败。
     *
     * 因此这里同步读：DataStore Preferences 是一个几十字节的小文件，
     * 首帧之前的这一次读远低于一帧的预算，换来的是"启动即可靠"。
     */
    override fun onCreate() {
        super.onCreate()
        Api.BASE = try {
            runBlocking { ApiSettingsStoreImpl.create(this@ModaApp).get() }
        } catch (e: Exception) {
            // 读不出来就退回默认地址：本地存储坏了不该让应用起不来
            Api.DEFAULT_BASE
        }
    }
}
