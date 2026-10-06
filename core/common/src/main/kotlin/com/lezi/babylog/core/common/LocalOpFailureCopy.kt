package com.lezi.babylog.core.common

/**
 * Shared fallback copy for local (single-device) operations that did not take
 * effect. Every line answers three things: the action did not apply, what
 * state the data is still in, and what to do next — no bare 「X失败，请重试」.
 *
 * Used as the [productUiError] fallback: product-facing Chinese domain
 * messages still pass through and win over these lines.
 */
object LocalOpFailureCopy {
    const val EDIT_WAKE = "修正没有生效，记录保持原样，可重试"
    const val WITHDRAW_WAKE = "撤回没有生效，记录保持原样，可重试"
    const val PICK_WAKE = "选择没有生效，可重试"
    const val SKIP_PLAN = "跳过没有生效，护理计划保持原样，可重试"
    const val DELETE_PLAN = "删除没有生效，护理计划还在，可重试"
    const val DELETE_RECORD = "删除没有生效，记录还在，可重试"
    const val SAVE_RECORD = "保存没有生效，这条没有写入，可重试"
    const val SAVE = "没有保存成功，可重试"
    const val SAVE_KEEP_ORIGINAL = "没有保存成功，原有内容未变，可重试"
    const val ADD = "没有添加成功，可重试"
    const val DELETE = "没有删除成功，可重试"
    const val LOAD = "读取没有成功，可重试"
    const val CONVERT_RECORD = "转换没有完成，这条记录保持原样，可重试"

    /** Suspected-duplicate group actions on the timeline (author declare / owner resolve). */
    const val DECLARE_DUPLICATE = "确认没有生效，重复标记保持原样，可重试"
    const val RESOLVE_DUPLICATE = "处理没有生效，这组重复记录保持原样，可重试"

    /** App update flows (check + download/install), shared by settings and account. */
    const val APP_UPDATE_CHECK = "更新没有检查成功，可稍后再试"
    const val APP_UPDATE_INSTALL = "下载或安装没有成功，已装的乐记不受影响，可稍后再试"

    /** Camera launch failure for photo/avatar capture, shared by log and family. */
    const val CAMERA_LAUNCH = "相机暂时打不开，可稍后再试一次"

    /** Settings / account baby local layout (theme + order). */
    const val BABY_LOCAL_THEME = "本机主题保存失败"
    const val BABY_LOCAL_ORDER = "本机顺序保存失败"

}
