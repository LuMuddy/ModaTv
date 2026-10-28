package com.moda.tv

import android.app.Application
import com.moda.tv.data.Api
import com.moda.tv.data.Auth
import com.moda.tv.data.local.AuthSessionStoreImpl
import com.moda.tv.data.local.DeviceFingerprint
import kotlinx.coroutines.runBlocking

class ModaApp : Application() {

    /**
     * token 与指纹都是持久化/派生身份，必须在任何请求发出之前注入。
     * TvViewModel.init 一构造就 loadHome()，所以"异步读完再赋值"会留下一个
     * 空令牌抢跑的窗口——冷启动首页的第一批请求会全部短路成 Unauthorized。
     *
     * 因此这里同步读：DataStore Preferences 是一个几十字节的小文件，
     * 首帧之前的这一次读远低于一帧的预算，换来的是"启动即可靠"。
     */
    override fun onCreate() {
        super.onCreate()
        // 指纹先就位：兑换授权码时它是请求里唯一的身份，晚一步就是一个空指纹的请求
        Api.FP = DeviceFingerprint.of(this)
        val store = AuthSessionStoreImpl.create(this)
        Api.TOKEN = try {
            runBlocking { store.get()?.token.orEmpty() }
        } catch (e: Exception) {
            // 读不出来就退回空串（= 未授权）：此时请求会短路而不是带着错令牌打后端，
            // 用户看到的是"去授权"，比拿一个不存在的凭据去换一串 401 更好排错。
            // 本地存储坏了不该让应用起不来。
            ""
        }
        // Auth 的公开签名不带 Context，注入点收在这一处
        Auth.install(this)
    }
}
