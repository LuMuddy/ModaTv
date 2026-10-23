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
    AppError.Storage -> stringResource(R.string.err_storage)
    AppError.Unknown -> stringResource(R.string.err_unknown)
}

@Composable
fun playerErrorText(error: PlayerError): String = when (error) {
    is PlayerError.Codec -> stringResource(R.string.err_player_codec)
    is PlayerError.Source -> stringResource(R.string.err_player_src)
    is PlayerError.Unknown -> stringResource(R.string.err_player_unknown)
}
