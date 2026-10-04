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
import android.view.MotionEvent
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.Toast
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
 * 每条弹幕使用独立的小窗口，点击立即复制/播放/下载；空白区域保持触摸穿透。
 * 所有窗口均不获取键盘焦点，避免打断游戏输入。
 */
object DanmakuOverlay {
    data class Action(val label: String, val perform: () -> Unit)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeBullets = linkedSetOf<BulletView>()
    private var voicePlayer: android.media.MediaPlayer? = null
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
            1,
            1,
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
        activeBullets.toList().forEach(::removeBullet)
        stopVoice()
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
        lp.height = 1
        lp.alpha = 0f
        runCatching { m.updateViewLayout(c, lp) }
        // Old positions are no longer meaningful after a density/orientation/config change.
        activeBullets.toList().forEach(::removeBullet)
    }

    private fun windowAlpha(c: DanmakuContainer): Float {
        val limit = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (c.context.getSystemService(Context.INPUT_SERVICE) as InputManager).maximumObscuringOpacityForTouch
        } else 1f
        return DanmakuTouchPolicy.opacity(alphaValue, limit)
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
                maxWidth = (ctx.resources.displayMetrics.widthPixels * .85f).toInt()
                ellipsize = android.text.TextUtils.TruncateAt.END
                setShadowLayer(6f, 0f, 1f, Color.argb(220, 0, 0, 0))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            tv.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            launchBullet(BulletView(ctx, tv, copyAction(copyText ?: text)), tv.measuredWidth.coerceAtLeast(1))
        }
    }

    /** 推送一条图片弹幕。dataUrl 为 data:image/...;base64,xxx */
    fun pushImage(label: String, dataUrl: String, color: Int = colorValue, downloadImage: Boolean = true, copyText: String? = null, action: Action? = null) {
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
                maxWidth = (ctx.resources.displayMetrics.widthPixels * .55f).toInt()
                ellipsize = android.text.TextUtils.TruncateAt.END
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
            launchBullet(BulletView(ctx, row, action ?: if (downloadImage) imageAction(dataUrl) else copyAction(copyText ?: label)), totalW)
        }
    }

    fun pushCard(label: String, preview: MessagePreview, action: Action? = null, voiceData: String? = null) {
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
            val operation = action ?: if (preview.kind == "voice") Action(L("播放语音", "Play voice")) { playVoice(voiceData) }
                else copyAction("${preview.text} ${preview.detail}".trim())
            launchBullet(BulletView(ctx, view, operation), view.measuredWidth)
        }
    }

    fun pushMediaFile(label: String, file: File, preview: MessagePreview, action: Action? = null) {
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
                    downloadImage = preview.kind == "image", copyText = "${preview.text} ${preview.detail}", action = action)
                else pushCard(label, preview.copy(detail = "${preview.detail} · 预览暂不可用"), action)
            }
        }
    }

    /** 把一条弹幕加入容器并启动从右到左的动画 */
    private fun launchBullet(bullet: BulletView, contentWidth: Int) {
        val c = container ?: return
        val ctx = appCtx ?: return
        val d = density()
        val sw = ctx.resources.displayMetrics.widthPixels
        bullet.alpha = 1f
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
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(), (baseFlags() and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()) or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = sw; y = topPx; alpha = windowAlpha(c)
        }
        if (activeBullets.size >= 24) activeBullets.firstOrNull()?.let(::removeBullet)
        if (runCatching { wm?.addView(bullet, lp) }.isFailure) return
        activeBullets.add(bullet)
        val anim = android.animation.ValueAnimator.ofFloat(sw.toFloat(), -tw.toFloat())
        anim.addUpdateListener { value ->
            lp.x = (value.animatedValue as Float).toInt()
            if (bullet in activeBullets) runCatching { wm?.updateViewLayout(bullet, lp) }
        }
        anim.duration = dur
        anim.interpolator = LinearInterpolator()
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                removeBullet(bullet)
            }
        })
        bullet.animator = anim
        anim.start()
    }

    private fun removeBullet(bullet: BulletView) {
        if (!activeBullets.remove(bullet)) return
        bullet.dispose()
        runCatching { wm?.removeView(bullet) }
    }

    private fun toast(text: String) { appCtx?.let { Toast.makeText(it, text, Toast.LENGTH_SHORT).show() } }

    private fun copyAction(text: String) = Action(L("复制内容", "Copy")) {
        val clipboard = appCtx?.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("MCTier", text))
        toast(L("已复制消息内容", "Message copied"))
    }

    private fun imageAction(data: String) = Action(L("下载图片", "Download image")) {
        val ctx = appCtx ?: return@Action
        mediaScope.launch {
            val ok = runCatching {
                val bytes = decodeDataUrl(data) ?: error("Invalid image")
                val mime = top.pmh13.mctier.data.sniffChatImageMime(bytes) ?: error("Invalid image")
                val extension = top.pmh13.mctier.data.imageExtension(mime)
                val name = "MCTier_${System.currentTimeMillis()}.$extension"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = ctx.contentResolver
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                        put(android.provider.MediaStore.Images.Media.MIME_TYPE, mime)
                        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MCTier")
                        put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("No output")
                    try {
                        checkNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
                        values.clear(); values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                    } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
                } else {
                    val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "MCTier")
                    check(dir.mkdirs() || dir.isDirectory)
                    File(dir, name).writeBytes(bytes)
                }
            }.isSuccess
            withContext(Dispatchers.Main) { toast(if (ok) L("图片已保存到 Pictures/MCTier", "Image saved to Pictures/MCTier") else L("图片保存失败", "Image save failed")) }
        }
    }

    private fun stopVoice() {
        voicePlayer?.let { runCatching { it.release() } }
        voicePlayer = null
    }

    private fun playVoice(data: String?) {
        stopVoice()
        if (data == null || data.length > 3 * 1024 * 1024 || !data.matches(Regex("^data:audio/(webm|ogg|mp4|wav);base64,[A-Za-z0-9+/]+=*$"))) {
            toast(L("语音不可用，请在聊天室重试", "Voice unavailable. Try in chat")); return
        }
        runCatching {
            val bytes = decodeDataUrl(data) ?: error("Invalid voice")
            check(bytes.size <= 2 * 1024 * 1024)
            val player = android.media.MediaPlayer().also { voicePlayer = it }
            player.setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            player.setDataSource(object : android.media.MediaDataSource() {
                override fun getSize() = bytes.size.toLong()
                override fun close() { bytes.fill(0) }
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                    if (position < 0 || position >= bytes.size) return -1
                    val count = minOf(size, bytes.size - position.toInt())
                    bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
                    return count
                }
            })
            player.setOnPreparedListener {
                if (voicePlayer === it) runCatching { it.start() }.onFailure {
                    stopVoice(); toast(L("语音播放失败", "Voice playback failed"))
                }
            }
            player.setOnCompletionListener { if (voicePlayer === it) stopVoice() }
            player.setOnErrorListener { failed, _, _ ->
                if (voicePlayer === failed) { stopVoice(); toast(L("语音播放失败", "Voice playback failed")) }
                true
            }
            player.prepareAsync()
        }.onFailure { stopVoice(); toast(L("语音播放失败", "Voice playback failed")) }
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
        private val action: Action,
    ) : LinearLayout(ctx) {
        var animator: android.animation.ValueAnimator? = null
        private var actioned = false
        private val resumeTask = Runnable { resume() }
        init {
            orientation = VERTICAL
            content.contentDescription = action.label
            content.setOnClickListener {
                if (!actioned) {
                    actioned = true
                    runCatching { action.perform() }.onFailure { toast(L("操作失败，请在聊天室重试", "Action failed. Try in chat")) }
                    resume()
                }
            }
            addView(content, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) { resume(); return false }
            if (event.actionMasked == MotionEvent.ACTION_DOWN) pin()
            return super.dispatchTouchEvent(event)
        }

        override fun dispatchHoverEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> pin()
                MotionEvent.ACTION_HOVER_EXIT -> { mainHandler.removeCallbacks(resumeTask); mainHandler.postDelayed(resumeTask, 250) }
            }
            return super.dispatchHoverEvent(event)
        }

        private fun pin() {
            if (actioned || this !in activeBullets) return
            activeBullets.filter { it !== this }.forEach { it.resume() }
            animator?.pause()
            mainHandler.removeCallbacks(resumeTask)
            mainHandler.postDelayed(resumeTask, 8000)
        }

        fun resume() {
            mainHandler.removeCallbacks(resumeTask)
            if (this in activeBullets) runCatching { wm?.updateViewLayout(this, layoutParams) }
            animator?.resume()
        }

        fun dispose() {
            mainHandler.removeCallbacks(resumeTask)
            animator?.removeAllListeners(); animator?.removeAllUpdateListeners(); animator?.cancel()
        }
    }

    private class DanmakuContainer(ctx: Context) : FrameLayout(ctx)
}
