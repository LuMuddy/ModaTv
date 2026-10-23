package com.moda.tv.model

/**
 * 数据层的失败原因（结构化错误码）。
 *
 * **这里不许出现任何中文文案**，理由不是洁癖：错误值一旦携带成品句子，
 * 数据层就被那份文案绑死了——UI 拿到它只能原样上屏，改词只能满仓搜索替换，
 * 同一个错误迟早出现两套说法。所以只表达「哪一类失败」，
 * 全部文案统一由 ui/components/ErrorText.kt 用 string 资源渲染。
 *
 * [Http.code]、[Upstream.detail] 属于技术信息，只进日志，不得拼进任何界面文本。
 */
sealed interface AppError {

    /** 接口返回非 2xx；code 只用于日志与排错，不上屏 */
    data class Http(val code: Int) : AppError

    /** 响应体为空 */
    data object EmptyPayload : AppError

    /** 剧集当前没有可取用的视频（单片 / 已下架） */
    data object NoVideo : AppError

    /** vkey 成功但直链为空，说明该档位实测取不到（调用方通常会顺手拉黑档位） */
    data object EmptyPlayUrl : AppError

    /** 上游业务失败；[detail] 只允许技术信息（简单类名等），只进日志 */
    data class Upstream(val detail: String? = null) : AppError

    /** 网络层失败：IOException / 超时 / 断网 */
    data object Network : AppError

    /**
     * 本地存储不可用：DataStore 读写失败（恢复默认、落盘都走这里）。
     * 与 [Unknown] 分开，是因为用户能做的事不同——存储坏了「稍后重试」是错的提示。
     */
    data object Storage : AppError

    /** 其余情况。宁可说「未知」，也不把原始异常文本甩给用户 */
    data object Unknown : AppError
}

/**
 * 播放内核的失败原因。
 *
 * 与 [AppError] 同理：内核层与设置层各自独立演进，它不该知道界面说什么语言。
 * 分类刻意做粗——用户能采取的动作只有「换内核 / 换解码方式 / 换清晰度 / 重试」，
 * 比这更细的分类对着 UI 没有意义。
 */
sealed interface PlayerError {

    /** 解码失败：片源本身没问题，但当前内核 + 解码方式解不出来 */
    data class Codec(val detail: String? = null) : PlayerError

    /** 片源 / 网络失败：404、m3u8 失效、连接中断、起播就抛 */
    data class Source(val detail: String? = null) : PlayerError

    /** 其余（IJK 的 what/extra 无法归类时落这里）；[detail] 只进日志 */
    data class Unknown(val detail: String? = null) : PlayerError
}
