package com.moda.tv.data

import okhttp3.Interceptor
import okhttp3.logging.HttpLoggingInterceptor

/** okhttp 官方拦截器，BODY 级：方法 / 头 / 请求体 / 响应头 / 响应体全部按真实值打（含令牌与指纹） */
private val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY }

/**
 * 只把「发往后端的接口请求」交给官方日志拦截器，其余请求原样透传。
 *
 * 为什么需要这层过滤：[Api.http] 是全应用共用的 client，BODY 级会把整个响应体读进内存再打印，
 * 拿它去读一段视频分片或一个几十 MB 的 APK 会直接 OOM、日志也被刷满；而且这些请求不属于
 * 「接口调试」的范围。过滤掉它们的同时保留了单 tag 过滤（tag 是 okhttp 自己的 okhttp.OkHttpClient）。
 *
 * 挂载条件见 [Api.http]：仅 BuildConfig.DEBUG，release 包里这个拦截器不在调用链上——
 * 因为日志里是明文会话令牌。
 */
internal val apiLogInterceptor: Interceptor = Interceptor { chain ->
    val req = chain.request()
    if (req.url.toString().startsWith(Api.BASE)) logging.intercept(chain) else chain.proceed(req)
}
