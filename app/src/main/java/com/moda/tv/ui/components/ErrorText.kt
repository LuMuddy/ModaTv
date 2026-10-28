package com.moda.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.moda.tv.R
import com.moda.tv.model.AppError
import com.moda.tv.model.PlayerError

/**
 * 错误码 → 界面文案的**唯一出口**。
 *
 * 全应用只有这里能把失败翻译成人话：数据层（[AppError]）与播放内核（[PlayerError]）
 * 只传错误码，这里取 string 资源（values/strings*.xml）。
 * 不要在别处另写一份「错误码 → 文案」的映射，否则同一个错误会长出第二套说法；
 * 也不要在这里拼 error.detail——那是给日志看的，不是给用户看的。
 */
@Composable
fun appErrorText(error: AppError): String = when (error) {
    is AppError.Http -> stringResource(R.string.err_http, error.code)
    AppError.EmptyPayload -> stringResource(R.string.err_empty_payload)
    AppError.NoVideo -> stringResource(R.string.err_no_video)
    AppError.EmptyPlayUrl -> stringResource(R.string.err_empty_play_url)
    is AppError.Upstream -> stringResource(R.string.err_upstream)
    AppError.Network -> stringResource(R.string.err_network)
    // 未授权与授权码失效是两件事：前者用户能做的是「去设置填一个码」，
    // 后者是「手里这个码不能用了」，混成一句就说不出用户下一步该做什么
    AppError.Unauthorized -> stringResource(R.string.err_unauthorized)
    AppError.AuthFailed -> stringResource(R.string.err_auth_failed)
    AppError.Storage -> stringResource(R.string.err_storage)
    AppError.Unknown -> stringResource(R.string.err_unknown)
}

@Composable
fun playerErrorText(error: PlayerError): String = when (error) {
    is PlayerError.Codec -> stringResource(R.string.err_player_codec)
    is PlayerError.Source -> stringResource(R.string.err_player_src)
    is PlayerError.Unknown -> stringResource(R.string.err_player_unknown)
}
