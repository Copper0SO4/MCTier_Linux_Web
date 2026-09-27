package top.pmh13.mctier.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import java.io.File
import top.pmh13.mctier.data.MessagePreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 系统级只读弹幕。整个生命周期不接收触摸、不抢焦点，消息出现/消失不切换输入窗口。
 * 复制或保存消息请使用聊天室。Android 12+ 同时限制窗口 alpha，避免系统拦截下层触摸。
 */
object DanmakuOverlay {
    private val mediaScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile var enabled = false
    var fontSizeSp = 20f
    var speedDp = 130f
    var alphaValue = 0.9f
    var tracks = 4
    var colorValue = Color.WHITE
    var rainbow = false

    private var wm: WindowManager? = null
    private var container: DanmakuContainer? = null
    private var appCtx: Context? = null
    private val trackFreeAt = LongArray(16)


    fun hasPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx)

    /** 应用配置；若启用且有权限则确保覆盖层已显示，否则移除 */
    fun applyConfig(ctx: Context, enabled: Boolean, fontSizeSp: Float, speedDp: Float, alpha: Float, tracks: Int, colorInt: Int = Color.WHITE, rainbow: Boolean = false) {
        this.enabled = enabled
        this.fontSizeSp = fontSizeSp
        this.speedDp = speedDp
        this.alphaValue = alpha
        this.tracks = tracks.coerceIn(1, 12)
        this.colorValue = colorInt
        this.rainbow = rainbow
        if (enabled && hasPermission(ctx)) {
            show(ctx)
            updateWindowMetrics()
        } else {
            hide()
        }
    }

    /** 生成明亮鲜艳的随机颜色（彩色模式：每条弹幕颜色不同） */
    private fun randomBrightColor(): Int {
        val hsv = floatArrayOf((Math.random() * 360).toFloat(), 0.85f, 0.98f)
        return Color.HSVToColor(hsv)
    }

    private fun density(): Float = (appCtx ?: container?.context)?.resources?.displayMetrics?.density ?: 2.5f

    /** 顶部安全间距：状态栏高度 + 额外留白，避免最顶部弹幕被系统状态栏遮挡而点不到 */
    private fun topInsetPx(): Int {
        val d = density()
        val ctx = appCtx ?: container?.context ?: return (40 * d).toInt()
        val resId = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        val sb = if (resId > 0) ctx.resources.getDimensionPixelSize(resId) else (26 * d).toInt()
        return sb + (12 * d).toInt()
    }

    /** 弹幕顶部条高度（含轨道与按钮空间），单位 px */
    private fun stripHeightPx(): Int {
        val d = density()
        val lineH = fontSizeSp * 1.95f * d
        return (topInsetPx() + tracks.coerceIn(1, 12) * lineH + 64f * d).toInt()
    }

    private fun baseFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    fun show(ctx: Context) {
        if (!hasPermission(ctx)) return
        appCtx = ctx.applicationContext
        if (container != null) return
        val manager = appCtx!!.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val fl = DanmakuContainer(appCtx!!)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            stripHeightPx(),
            overlayType(),
            baseFlags(),
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.alpha = 0f // 无弹幕时连透明覆盖窗口也不参与触摸遮挡判定。
        if (runCatching { manager.addView(fl, lp) }.isFailure) return
        wm = manager
        container = fl
    }

    fun hide() {
        val c = container
        val m = wm
        if (c != null) {
            for (i in 0 until c.childCount) (c.getChildAt(i) as? BulletView)?.animator?.removeAllListeners()
            for (i in 0 until c.childCount) (c.getChildAt(i) as? BulletView)?.animator?.cancel()
        }
        if (c != null && m != null) runCatching { m.removeView(c) }
        trackFreeAt.fill(0)
        container = null
        wm = null
    }

    /** 更新窗口尺寸（轨道/字号变化或旋转后调用） */
    private fun updateWindowMetrics() {
        val c = container ?: return
        val m = wm ?: return
        val lp = c.layoutParams as? WindowManager.LayoutParams ?: return
        lp.height = stripHeightPx()
        lp.alpha = windowAlpha(c)
        runCatching { m.updateViewLayout(c, lp) }
    }

    private fun windowAlpha(c: DanmakuContainer): Float {
        if (c.childCount == 0) return 0f
        val limit = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (c.context.getSystemService(Context.INPUT_SERVICE) as InputManager).maximumObscuringOpacityForTouch
        } else 1f
        return DanmakuTouchPolicy.opacity(alphaValue, limit)
    }

    private fun refreshWindowAlpha() {
        val c = container ?: return
        val lp = c.layoutParams as? WindowManager.LayoutParams ?: return
        val alpha = windowAlpha(c)
        if (lp.alpha == alpha) return
        lp.alpha = alpha
        runCatching { wm?.updateViewLayout(c, lp) }
    }

    /** 推送一条文本弹幕。copyText 为点击后可复制的原始消息内容 */
    fun push(text: String, color: Int = colorValue, copyText: String? = null) {
        if (!enabled || text.isBlank()) return
        val c = container ?: return
        val ctx = appCtx ?: return
        val finalColor = if (rainbow) randomBrightColor() else color
        c.post {
            if (!enabled || container !== c) return@post
            val tv = TextView(ctx).apply {
                this.text = text
                setTextColor(finalColor)
                textSize = fontSizeSp
                maxLines = 1
                setShadowLayer(6f, 0f, 1f, Color.argb(220, 0, 0, 0))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            tv.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            launchBullet(BulletView(ctx, tv, isImage = false, copyText = copyText, imageData = null), tv.measuredWidth.coerceAtLeast(1))
        }
    }

    /** 推送一条图片弹幕。dataUrl 为 data:image/...;base64,xxx */
    fun pushImage(label: String, dataUrl: String, color: Int = colorValue, downloadImage: Boolean = true, copyText: String? = null) {
        if (!enabled) return
        val c = container ?: return
        val ctx = appCtx ?: return
        val finalColor = if (rainbow) randomBrightColor() else color
        c.post {
            if (!enabled || container !== c) return@post
            val bytes = decodeDataUrl(dataUrl)
            if (bytes == null) { push(label, finalColor, null); return@post }
            val drawable = runCatching {
                if (Build.VERSION.SDK_INT >= 28) android.graphics.ImageDecoder.decodeDrawable(
                    android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                    val scale = minOf(1f, 320f / info.size.width, 180f / info.size.height)
                    decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                } else android.graphics.drawable.BitmapDrawable(ctx.resources, BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            }.getOrNull()
            if (drawable == null) { pushCard(label, MessagePreview("image", "[图片预览不可用]")); return@post }
            val d = density()
            // 缩略图大小适中：高度贴合轨道行高，宽度按比例但限制最大值，既能看清又不过度遮挡
            val targetH = (fontSizeSp * 1.55f * d).toInt().coerceIn((26 * d).toInt(), (54 * d).toInt())
            val ratio = drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight.toFloat().coerceAtLeast(1f)
            val maxW = (fontSizeSp * 3.6f * d).toInt()
            val targetW = (targetH * ratio).toInt().coerceIn((targetH * 0.4f).toInt(), maxW)
            // 名字 + 缩略图 横向排布，让用户知道是谁发的图
            val row = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val nameTv = TextView(ctx).apply {
                text = label
                setTextColor(finalColor)
                textSize = fontSizeSp
                maxLines = 1
                setShadowLayer(6f, 0f, 1f, Color.argb(220, 0, 0, 0))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            val iv = ImageView(ctx).apply {
                setImageDrawable(drawable)
                scaleType = ImageView.ScaleType.FIT_CENTER
                addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) { (drawable as? android.graphics.drawable.Animatable)?.start() }
                    override fun onViewDetachedFromWindow(v: View) { (drawable as? android.graphics.drawable.Animatable)?.stop() }
                })
            }
            row.addView(nameTv, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            row.addView(iv, android.widget.LinearLayout.LayoutParams(targetW, targetH).apply {
                leftMargin = (6 * d).toInt()
            })
            row.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val totalW = row.measuredWidth.coerceAtLeast(targetW)
            launchBullet(BulletView(ctx, row, isImage = downloadImage, copyText = copyText, imageData = dataUrl), totalW)
        }
    }

    fun pushCard(label: String, preview: MessagePreview) {
        val c = container ?: return
        val ctx = appCtx ?: return
        if (!enabled) return
        c.post {
            if (!enabled || container !== c) return@post
            val icon = when (preview.kind) { "voice" -> "▂▅▃▇▅▂"; "audio" -> "♫"; "video" -> "▶"; "image" -> "▧"; else -> "▤" }
            val text = "$label $icon ${preview.text}" + if (preview.detail.isNotBlank()) " · ${preview.detail}" else ""
            val view = TextView(ctx).apply {
                this.text = text; textSize = fontSizeSp * .85f; setTextColor(Color.WHITE)
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                maxWidth = (ctx.resources.displayMetrics.widthPixels * .85f).toInt()
                setPadding((10 * density()).toInt(), (4 * density()).toInt(), (10 * density()).toInt(), (4 * density()).toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.argb(238, 23, 37, 29)); cornerRadius = 7 * density(); setStroke(1, Color.rgb(113, 164, 85))
                }
            }
            view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            launchBullet(BulletView(ctx, view, false, "${preview.text} ${preview.detail}", null), view.measuredWidth)
        }
    }

    fun pushMediaFile(label: String, file: File, preview: MessagePreview) {
        if (!enabled) return
        val expectedContainer = container ?: return
        mediaScope.launch {
            val image = runCatching {
                if (preview.kind == "image" && file.length() <= 2 * 1024 * 1024) {
                    val bytes = file.readBytes()
                    val mime = top.pmh13.mctier.data.sniffChatImageMime(bytes) ?: error("Unsupported image")
                    "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                } else {
                    val bitmap = if (preview.kind == "video") {
                        val retriever = android.media.MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(file.absolutePath)
                            if (Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
                            else retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { original ->
                                val scale = minOf(1f, 320f / original.width, 180f / original.height)
                                val scaled = android.graphics.Bitmap.createScaledBitmap(original, (original.width * scale).toInt().coerceAtLeast(1), (original.height * scale).toInt().coerceAtLeast(1), true)
                                if (scaled !== original) original.recycle()
                                scaled
                            }
                        }
                        finally { retriever.release() }
                    } else {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(file.absolutePath, bounds)
                        val options = BitmapFactory.Options().apply { inSampleSize = maxOf(1, maxOf(bounds.outWidth / 320, bounds.outHeight / 180)) }
                        BitmapFactory.decodeFile(file.absolutePath, options)
                    } ?: error("No preview frame")
                    try {
                        val output = java.io.ByteArrayOutputStream()
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, output)
                        "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
                    } finally { bitmap.recycle() }
                }
            }.getOrNull()
            withContext(Dispatchers.Main) {
                if (!enabled || container !== expectedContainer) return@withContext
                if (image != null) pushImage(if (preview.kind == "video") "$label ▶ ${preview.text}" else label, image,
                    downloadImage = preview.kind == "image", copyText = "${preview.text} ${preview.detail}")
                else pushCard(label, preview.copy(detail = "${preview.detail} · 预览暂不可用"))
            }
        }
    }

    /** 把一条弹幕加入容器并启动从右到左的动画 */
    private fun launchBullet(bullet: BulletView, contentWidth: Int) {
        val c = container ?: return
        val ctx = appCtx ?: return
        val d = density()
        val sw = ctx.resources.displayMetrics.widthPixels
        bullet.alpha = 1f // 用户透明度统一施加到 WindowManager.LayoutParams.alpha。
        val lineH = fontSizeSp * 1.95f * d
        val now = System.currentTimeMillis()
        val nTracks = tracks.coerceIn(1, 12)
        var track = 0
        var earliest = Long.MAX_VALUE
        for (i in 0 until nTracks) {
            if (trackFreeAt[i] <= now) { track = i; break }
            if (trackFreeAt[i] < earliest) { earliest = trackFreeAt[i]; track = i }
        }
        val speedPx = (speedDp * d).coerceAtLeast(40f)
        val tw = contentWidth.coerceAtLeast(1)
        val distance = sw + tw
        val dur = (distance / speedPx * 1000f).toLong().coerceIn(2000L, 20000L)
        val releaseDelay = ((tw + 40) / speedPx * 1000f).toLong()
        trackFreeAt[track] = now + releaseDelay
        val topPx = (topInsetPx() + track * lineH).toInt()
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = topPx; leftMargin = 0 }
        c.addView(bullet, lp)
        bullet.translationX = sw.toFloat()
        val anim = android.animation.ObjectAnimator.ofFloat(bullet, "translationX", sw.toFloat(), -tw.toFloat())
        anim.duration = dur
        anim.interpolator = LinearInterpolator()
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                runCatching { c.removeView(bullet) }
                if (container === c) refreshWindowAlpha()
            }
        })
        bullet.animator = anim
        anim.start()
        refreshWindowAlpha()
    }

    /** 解析 data URL 为字节数组 */
    private fun decodeDataUrl(dataUrl: String): ByteArray? {
        val idx = dataUrl.indexOf(',')
        val b64 = if (idx >= 0) dataUrl.substring(idx + 1) else dataUrl
        return runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
    }

    /** 跳转到系统悬浮窗授权页 */
    fun requestPermissionIntent(ctx: Context): android.content.Intent =
        android.content.Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:${ctx.packageName}"),
        )

    /** 弹幕视图：包裹文本/图片内容，持有动画与元数据 */
    private class BulletView(
        ctx: Context,
        content: View,
        val isImage: Boolean,
        val copyText: String?,
        val imageData: String?,
    ) : FrameLayout(ctx) {
        var animator: android.animation.ObjectAnimator? = null
        init {
            isClickable = false
            addView(
                content,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
            )
        }
    }

    private class DanmakuContainer(ctx: Context) : FrameLayout(ctx)
}
