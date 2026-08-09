package com.nanobotkt.core.session

/**
 * 描述一个需要随认证主体切换而失效的长生命周期状态持有者。
 *
 * 该契约只表达“清理当前会话状态”这一最小能力，不暴露任何 feature 的 UI state、
 * Repository 方法或网络模型。App 作为组合根可以统一编排退出登录，而 feature 不需要
 * 反向依赖 AppViewModel，也不需要让 AppViewModel 了解每个 feature 的具体实现细节。
 */
fun interface SessionStateOwner {
    /**
     * 使当前账号产生的内存快照、请求代次和错误状态立即失效。
     *
     * 实现必须保证迟到的网络响应不能在清理后重新写回旧账号数据；具体的代次保护仍由
     * 各 feature Repository 自己负责，契约只规定调用边界和时机。
     */
    fun resetSessionState()
}
