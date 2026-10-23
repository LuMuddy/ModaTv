package com.moda.tv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moda.tv.data.LanConfigServer
import com.moda.tv.data.LanNet
import com.moda.tv.R
import com.moda.tv.data.SaveOutcome
import com.moda.tv.ui.components.ActionButton
import com.moda.tv.ui.theme.ModaColors
import com.moda.tv.ui.theme.TvBody
import com.moda.tv.ui.theme.TvCaption
import com.moda.tv.ui.theme.TvHeading
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 局域网扫码配置弹窗：左边二维码、右边地址与说明。
 *
 * **生命周期是本组件的核心约束**（用户明确要求"只有打开这个弹窗时才能用"）：
 *   - 进入组合就 [LanConfigServer.start]，离开组合（关弹窗 / 退出设置页 / 返回上一层）
 *     无条件 [LanConfigServer.stop]，端口立刻归还；
 *   - 应用进入后台（ON_STOP）同样 stop，回到前台（ON_START）再重新 start。
 * 服务不注册前台服务、也没有任何静态持有，弹窗消失即无监听端口。
 *
 * 拿不到局域网 IPv4 时降级：不启动服务，只显示提示并给出手动编辑入口。
 */
@Composable
fun LanConfigDialog(
    /** 当前生效的代理网址，用于预填手机端输入框 */
    url: String,
    /** 校验 + 探活 + 落盘；返回 Ok 表示已生效 */
    save: suspend (String) -> SaveOutcome,
    onDismiss: () -> Unit,
    /** 无法扫码时的兜底：关弹窗并进入设置页的手动编辑（走系统输入法） */
    onManualEdit: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    // 局域网地址在一轮弹窗里固定：网络切换要重开弹窗才生效（与端口绑定一起重来）
    val ip = remember { LanNet.lanIpv4() }

    // StateFlow 而不是 mutableStateOf：onSaved 是在服务端的 IO 协程里回调的，
    // 走 Flow 收集天然线程安全，电视端不用等 DataStore 那次回环就能立刻看到新值
    val live = remember { MutableStateFlow(url) }
    val current by live.collectAsStateWithLifecycle()

    // 设置页自己的 url 变了（例如刚保存完）也要把弹窗里的展示同步过去
    LaunchedEffect(url) { live.value = url }

    var port by remember { mutableStateOf(-1) }
    var startFailed by remember { mutableStateOf(false) }

    // 页面模板放在 assets/index.html，跟着弹窗一起读一次；纯文本几 KB，不必专门开线程
    val context = LocalContext.current
    val pageTemplate = remember(context) { LanConfigServer.loadPageTemplate(context) }

    val server = remember(pageTemplate) {
        LanConfigServer(
            context = context.applicationContext,
            currentBase = { live.value },
            save = save,
            // 服务端在 IO 线程回调，写 Flow 天然线程安全
            onSaved = { saved -> live.value = saved },
            pageTemplate = pageTemplate,
        )
    }

    // 服务只在弹窗存活期间存在：进入即启动，离开即关闭
    DisposableEffect(lifecycleOwner, server) {
        if (ip == null) {
            // 没有局域网地址就没必要监听端口
            return@DisposableEffect onDispose { }
        }
        port = server.start()
        startFailed = port < 0
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    // 应用进入后台：立刻释放端口，绝不允许后台常驻
                    server.stop()
                    port = -1
                }
                Lifecycle.Event.ON_START -> {
                    if (port < 0) {
                        port = server.start()
                        startFailed = port < 0
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // 关弹窗 / 退出设置页的唯一收尾点：关 socket、取消协程、端口归还
            server.stop()
            port = -1
        }
    }

    val lanAddress = if (ip != null && port > 0) "http://$ip:$port" else null
    val qrContent = lanAddress
    val qrBitmap = remember(qrContent) {
        qrContent?.let { LanNet.qrBitmap(it, QR_PIXELS) }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val closeFocus = remember { FocusRequester() }
        val manualFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            withFrameNanos { }
            // 有二维码时焦点落在「关闭」，降级时落在唯一可点的「手动编辑」
            runCatching {
                if (ip == null || lanAddress == null) manualFocus.requestFocus() else closeFocus.requestFocus()
            }
        }

        Box(
            Modifier
                .width(900.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(ModaColors.Surface)
                .padding(28.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                // ---------- 左：二维码 ----------
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(300.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(androidx.compose.ui.graphics.Color.White),
                        contentAlignment = Alignment.Center,
                    ) {
                        val bmp = qrBitmap
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = stringResource(R.string.lan_qr_content_desc),
                                filterQuality = FilterQuality.None,
                                modifier = Modifier.size(276.dp),
                            )
                        } else {
                            Text(
                                stringResource(
                                    if (ip == null) R.string.lan_no_lan else R.string.lan_qr_failed,
                                ),
                                style = TvBody,
                                color = ModaColors.Bg,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(
                            when {
                                ip == null -> R.string.lan_status_no_lan
                                startFailed -> R.string.lan_status_start_failed
                                lanAddress == null -> R.string.lan_status_not_running
                                else -> R.string.lan_status_running
                            },
                        ),
                        style = TvCaption,
                        color = when {
                            ip == null || startFailed -> ModaColors.Danger
                            else -> ModaColors.TextDim
                        },
                    )
                }

                Spacer(Modifier.width(28.dp))

                // ---------- 右：地址与说明 ----------
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.lan_title), style = TvHeading)
                    Spacer(Modifier.height(14.dp))

                    if (lanAddress != null) {
                        // 便于手输兜底：原始地址单独一行等宽展示
                        InfoLine(stringResource(R.string.lan_phone_url), lanAddress)
                        Spacer(Modifier.height(6.dp))
                    } else if (ip != null) {
                        InfoLine(
                            stringResource(R.string.lan_lan_address),
                            stringResource(R.string.lan_service_not_running),
                        )
                        Spacer(Modifier.height(6.dp))
                    }

                    InfoLine(
                        stringResource(R.string.lan_current_api_base),
                        current.ifBlank { stringResource(R.string.lan_not_set) },
                    )
                    Spacer(Modifier.height(14.dp))

                    Text(
                        text = stringResource(
                            if (ip == null) R.string.lan_hint_no_lan else R.string.lan_hint_scan,
                        ),
                        style = TvBody.copy(fontSize = 17.sp),
                        color = ModaColors.TextDim,
                    )

                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ActionButton(
                            text = stringResource(R.string.common_close),
                            onClick = onDismiss,
                            primary = true,
                            modifier = Modifier.focusRequester(closeFocus),
                        )
                        if (ip == null) {
                            ActionButton(
                                text = stringResource(R.string.lan_manual_edit),
                                onClick = onManualEdit,
                                modifier = Modifier.focusRequester(manualFocus),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 单行「标签 + 值」，值可折行以容纳长地址 */
@Composable
private fun InfoLine(label: String, value: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = TvCaption.copy(fontSize = 14.sp), color = ModaColors.TextDim)
        Spacer(Modifier.height(3.dp))
        Text(
            value,
            style = TvBody.copy(fontSize = 17.sp),
            color = ModaColors.Text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 二维码按像素生成后由 Compose 缩放；固定值避免随 dp 变化重复编码 */
private const val QR_PIXELS = 640
