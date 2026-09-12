package moe.tekuza.m9player

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect as AndroidRect
import android.graphics.RectF
import android.text.TextPaint
import kotlin.math.ceil

internal data class VerticalTextToken(
    val sourceOffset: Int,
    val sourceEndExclusive: Int,
    val text: String
)

/** 語間の空白（ASCII の空白と NBSP）。 */
internal fun isAsciiRunSpaceChar(ch: Char): Boolean = ch == ' ' || ch == '\u00A0'

/** 横倒しで組む欧文のランに含められる文字：英数字・欧文の記号・語間の空白。 */
internal fun isAsciiTextRunChar(ch: Char): Boolean = ch in '!'..'~' || isAsciiRunSpaceChar(ch)

/**
 * 横倒し（時計回り90度）で組む欧文の文字列か（JLREQ §3.2.6）。
 * 句読点などの欧文記号もランに含める（"work," のように切らずに横倒しにするため）。
 * 和文が混ざるトークンは false。
 */
internal fun isSidewaysAsciiText(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.length <= 1) return false
    if (trimmed.none { it.isLetterOrDigit() }) return false
    return trimmed.all(::isAsciiTextRunChar)
}

/**
 * [start] から続く欧文ランの終端（末尾の空白は含めない）。
 * ランとして組めない場合（和文で始まる／英数字を含まない／1 文字だけ／2〜4 桁の数字）は
 * [start] を返し、呼び出し側の通常処理（縦中横など）に任せる。
 */
internal fun asciiTextRunEnd(text: String, start: Int, end: Int): Int {
    if (start >= end || !isAsciiTextRunChar(text[start])) return start
    var cursor = start
    var lastNonSpace = start
    var hasLetterOrDigit = false
    while (cursor < end && isAsciiTextRunChar(text[cursor])) {
        if (text[cursor].isLetterOrDigit()) hasLetterOrDigit = true
        if (!isAsciiRunSpaceChar(text[cursor])) lastNonSpace = cursor + 1
        cursor += 1
    }
    if (!hasLetterOrDigit || lastNonSpace - start <= 1) return start
    val run = text.substring(start, lastNonSpace)
    // 2〜4 桁の数字は縦中横で組む（ここでランにしてしまうと横倒しになってしまう）
    if (run.length in 2..4 && run.all { it.isDigit() }) return start
    return lastNonSpace
}

internal object VerticalTextGlyphEngine {
    private data class RotationStyle(
        val degrees: Float,
        val dxEm: Float = 0f,
        val dyEm: Float = 0f,
        val mirrorAfterRotation: Boolean = false
    )

    private val scratchBounds = ThreadLocal.withInitial { AndroidRect() }

    private val topRightPunctuation = setOf(
        '。', '、', '︒', '︑', '︐', '︔', '，', '．', '.', ','
    )

    private val centerPunctuation = setOf(
        '・', '：', '︓', '︰', '︙'
    )

    private val centeredInkPunctuation = setOf('︕', '︖')

    private val smallKana = setOf(
        'ぁ', 'ぃ', 'ぅ', 'ぇ', 'ぉ', 'っ', 'ゃ', 'ゅ', 'ょ', 'ゎ',
        'ァ', 'ィ', 'ゥ', 'ェ', 'ォ', 'ッ', 'ャ', 'ュ', 'ョ', 'ヮ', 'ヶ'
    )

    private val rotateClockwise = setOf(
        '「', '」', '『', '』', '（', '）', '(', ')', '［', '］', '[', ']',
        '｛', '｝', '{', '}', '〔', '〕', '【', '】', '〈', '〉', '《', '》',
        '〖', '〗', '＜', '＞', '〜', '～', '…', '‥', '-', '_', '~',
        '／', '/', '｜', '|', '＝', '=', '÷', '：', ':', '；', ';'
    )

    private val vjapDashRotation = mapOf(
        'ー' to RotationStyle(degrees = -90f),
        '─' to RotationStyle(degrees = 90f, dxEm = -0.08f, dyEm = -0.02f),
        '—' to RotationStyle(degrees = 90f, dxEm = -0.08f, dyEm = -0.02f),
        '―' to RotationStyle(degrees = 90f, dxEm = -0.08f, dyEm = -0.02f),
        '−' to RotationStyle(degrees = 90f, dxEm = -0.08f, dyEm = -0.02f)
    )

    private val verticalPresentationForms = setOf(
        '︵', '︶', '︷', '︸', '︹', '︺', '︿', '﹀', '︽', '︾', '︻', '︼',
        '﹁', '﹂', '﹃', '﹄', '︙'
    )

    private val mirrorAfterRotation = setOf('〜', '～')

    private val noColumnStartChars: Set<Char> = setOf(
        '、', '。', '，', '．', '.', ',', '：', '；', ':', ';',
        '！', '？', '）', ')', ']', '】', '}', '』', '」', '”', '’', '》', '〉',
        '…', '—', '―', '─', '−', '～', '〜',
        '︑', '︒', '︐', '︓', '︔', '︕', '︖', '︶', '︺', '︸', '﹀',
        '︙', '︰', '﹂', '﹄', '﹡'
    )

    private val noColumnEndChars: Set<Char> = setOf(
        '「', '『', '“', '‘', '（', '(', '[', '{', '【', '〔', '〈', '《', '＜',
        '︵', '︹', '︷', '︿', '﹁', '﹃'
    )

    private fun presentationChar(ch: Char): Char = when (ch) {
        '「', '“' -> '﹁'
        '」', '”' -> '﹂'
        '『', '‘' -> '﹃'
        '』', '’' -> '﹄'
        '\u3001' -> '\uFE11'
        '\u3002' -> '\uFE12'
        '\uFF0C' -> '\uFE10'
        '\uFF0E' -> '\uFE12'
        '\uFF1A' -> '\uFE13'
        '\uFF1B' -> '\uFE14'
        '\uFF01' -> '\uFE15'
        '\uFF1F' -> '\uFE16'
        '\uFF08' -> '\uFE35'
        '\uFF09' -> '\uFE36'
        '\uFF3B' -> '\uFE39'
        '\uFF3D' -> '\uFE3A'
        '\uFF5B' -> '\uFE37'
        '\uFF5D' -> '\uFE38'
        '\uFF1C' -> '\uFE3F'
        '\uFF1E' -> '\uFE40'
        ',' -> '\uFE10'
        '.' -> '\uFE12'
        ':' -> '\uFE13'
        ';' -> '\uFE14'
        '!' -> '\uFE15'
        '?' -> '\uFE16'
        '(' -> '\uFE35'
        ')' -> '\uFE36'
        '[' -> '\uFE39'
        ']' -> '\uFE3A'
        '{' -> '\uFE37'
        '}' -> '\uFE38'
        '<' -> '\uFE3F'
        '>' -> '\uFE40'
        '\u2025' -> '\uFE30'
        '\u2026', '\u22EF' -> '\uFE19'
        '\u203B' -> '\uFE61'
        else -> ch
    }

    private fun presentationText(text: String): String {
        if (text.isEmpty()) return text
        if (text.length == 1) {
            val mapped = presentationChar(text[0])
            return if (mapped == text[0]) text else mapped.toString()
        }
        var changed = false
        val out = StringBuilder(text.length)
        text.forEach { ch ->
            val mapped = presentationChar(ch)
            if (mapped != ch) changed = true
            out.append(mapped)
        }
        return if (changed) out.toString() else text
    }

    fun isNoColumnStart(ch: Char): Boolean {
        return ch in noColumnStartChars || presentationChar(ch) in noColumnStartChars
    }

    fun isNoColumnEnd(ch: Char): Boolean {
        return ch in noColumnEndChars || presentationChar(ch) in noColumnEndChars
    }

    fun isAsciiWordChar(ch: Char): Boolean {
        return ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9'
    }

    private fun isAsciiLetter(ch: Char): Boolean {
        return ch in 'A'..'Z' || ch in 'a'..'z'
    }

    private fun isAsciiJoiner(ch: Char): Boolean {
        return ch == '\'' || ch == '-' || ch == '/' || ch == '.' || ch == '_' || ch == '+' || ch == '&'
    }

    fun nextVerticalTextToken(
        text: String,
        start: Int,
        endExclusive: Int = text.length
    ): VerticalTextToken {
        val endLimit = endExclusive.coerceIn(0, text.length)
        if (start >= endLimit) return VerticalTextToken(start, start, "")
        val first = text[start]
        if (isAsciiWordChar(first)) {
            var end = start
            while (end < endLimit) {
                when {
                    isAsciiWordChar(text[end]) -> end += 1
                    end > start && end + 1 < endLimit &&
                        isAsciiJoiner(text[end]) && isAsciiWordChar(text[end + 1]) -> end += 1
                    else -> break
                }
            }
            val tokenText = text.substring(start, end)
            val isLetters = tokenText.all(::isAsciiLetter)
            val isDigits = tokenText.all { it in '0'..'9' }
            if (tokenText.length == 1 && (isLetters || isDigits)) {
                return VerticalTextToken(start, start + 1, text.substring(start, start + 1))
            }
            if (tokenText.length in 2..3 && isLetters && tokenText.all { it in 'A'..'Z' }) {
                return VerticalTextToken(start, start + 1, text.substring(start, start + 1))
            }
            if (isDigits && tokenText.length != 2) {
                return VerticalTextToken(start, start + 1, text.substring(start, start + 1))
            }
            return VerticalTextToken(start, end, tokenText)
        }
        // 代理对（CJK 扩展 B 等非 BMP 字符）必须整体作为一个 token，
        // 否则拆成两个孤立代理项各自渲染会变成豆腐（缺字形）。
        val unitCount = if (
            Character.isHighSurrogate(text[start]) &&
            start + 1 < endLimit &&
            Character.isLowSurrogate(text[start + 1])
        ) {
            2
        } else {
            1
        }
        return VerticalTextToken(start, start + unitCount, text.substring(start, start + unitCount))
    }

    fun isTateChuYokoToken(text: String): Boolean {
        val compact = text.trim().filterNot(::isAsciiRunSpaceChar)
        if (compact.length !in 2..4 || !compact.all { it.isLetterOrDigit() }) return false
        return compact.all { it.isDigit() }
    }

    fun estimateCellWidth(paint: TextPaint): Float {
        val sampleWidth = maxOf(
            paint.measureText("国"),
            paint.measureText("あ"),
            paint.textSize
        )
        return ceil((sampleWidth * 1.12f).toDouble()).toFloat().coerceAtLeast(1f)
    }

    fun draw(canvas: Canvas, sourcePaint: TextPaint, text: String, rect: RectF) {
        val displayText = presentationText(text)
        if (displayText.isEmpty()) return
        withPaint(sourcePaint, Paint.Align.CENTER) { paint ->
            val baselineAdjust = -(paint.ascent() + paint.descent()) * 0.5f
            when (val ch = displayText.first()) {
                in topRightPunctuation -> drawTopRightPunctuation(canvas, paint, displayText, rect)
                in smallKana -> drawSmallKana(canvas, paint, displayText, rect)
                in centeredInkPunctuation -> drawInkCenteredText(canvas, paint, displayText, rect)
                in centerPunctuation -> drawOffsetText(canvas, paint, displayText, rect, baselineAdjust, 0f, -paint.textSize * 0.04f)
                else -> drawRotatableText(canvas, paint, displayText, rect, baselineAdjust)
            }
        }
    }

    fun drawLatinRun(canvas: Canvas, sourcePaint: TextPaint, text: String, rect: RectF) {
        val displayText = text.trim()
        if (displayText.isEmpty()) return
        withPaint(sourcePaint, Paint.Align.LEFT) { paint ->
            val baselineAdjust = -(paint.ascent() + paint.descent()) * 0.5f
            val cx = rect.centerX()
            val cy = rect.centerY()
            val topPadding = (paint.textSize * 0.08f).coerceAtMost(rect.height() * 0.18f)
            val drawX = cx + (rect.top + topPadding - cy)
            val drawY = cy + baselineAdjust
            canvas.save()
            canvas.rotate(90f, cx, cy)
            canvas.drawText(displayText, drawX, drawY, paint)
            canvas.restore()
        }
    }

    fun drawTateChuYoko(canvas: Canvas, sourcePaint: TextPaint, text: String, rect: RectF) {
        val displayText = text.trim()
        if (displayText.isEmpty()) return
        withPaint(sourcePaint, Paint.Align.CENTER) { paint ->
            val oldSize = paint.textSize
            val baselineAdjust = -(paint.ascent() + paint.descent()) * 0.5f
            val maxWidth = rect.width() * 0.92f
            val measured = paint.measureText(displayText).coerceAtLeast(1f)
            if (measured > maxWidth) {
                paint.textSize = (oldSize * maxWidth / measured).coerceAtLeast(oldSize * 0.72f)
            }
            canvas.drawText(displayText, rect.centerX(), rect.centerY() + baselineAdjust, paint)
            paint.textSize = oldSize
        }
    }

    fun inkRect(sourcePaint: TextPaint, text: String, rect: RectF): RectF {
        val displayText = presentationText(text)
        if (displayText.isEmpty()) return rect
        return withPaint(sourcePaint, Paint.Align.CENTER) { paint ->
            val baselineAdjust = -(paint.ascent() + paint.descent()) * 0.5f
            val rawRect = when (val ch = displayText.first()) {
                in topRightPunctuation -> topRightPunctuationInkRect(paint, displayText, rect)
                in smallKana -> smallKanaInkRect(paint, displayText, rect)
                in centeredInkPunctuation -> inkCenteredTextRect(paint, displayText, rect)
                in centerPunctuation -> offsetInkRect(paint, displayText, rect, baselineAdjust, 0f, -paint.textSize * 0.04f)
                else -> rotatableInkRect(paint, displayText, rect, baselineAdjust)
            }
            clampAndPadInkRect(rawRect, rect, paint.textSize)
        }
    }

    fun rotationFor(text: String): Float {
        val ch = text.firstOrNull()?.let(::presentationChar) ?: return 0f
        return when {
            ch in vjapDashRotation -> vjapDashRotation.getValue(ch).degrees
            text.trim().length == 1 && (ch in 'A'..'Z' || ch in 'a'..'z') -> 0f
            ch in 'A'..'Z' || ch in 'a'..'z' -> 90f
            ch in '0'..'9' -> 0f
            ch in verticalPresentationForms -> 0f
            ch in rotateClockwise -> 90f
            else -> 0f
        }
    }

    private fun rotationStyleFor(text: String): RotationStyle {
        val ch = text.firstOrNull()?.let(::presentationChar) ?: return RotationStyle(0f)
        vjapDashRotation[ch]?.let { return it }
        return RotationStyle(
            degrees = rotationFor(text),
            mirrorAfterRotation = ch in mirrorAfterRotation
        )
    }

    private inline fun <T> withPaint(
        paint: TextPaint,
        align: Paint.Align,
        block: (TextPaint) -> T
    ): T {
        val previousAlign = paint.textAlign
        val previousAntiAlias = paint.isAntiAlias
        paint.textAlign = align
        paint.isAntiAlias = true
        return try {
            block(paint)
        } finally {
            paint.textAlign = previousAlign
            paint.isAntiAlias = previousAntiAlias
        }
    }

    private fun measureBounds(paint: TextPaint, text: String): AndroidRect {
        val bounds = scratchBounds.get() ?: AndroidRect().also(scratchBounds::set)
        bounds.setEmpty()
        paint.getTextBounds(text, 0, text.length, bounds)
        return bounds
    }

    private fun offsetInkRect(
        paint: TextPaint,
        text: String,
        rect: RectF,
        baselineAdjust: Float,
        dx: Float,
        dy: Float
    ): RectF {
        val bounds = measureBounds(paint, text)
        val measuredWidth = paint.measureText(text).coerceAtLeast(bounds.width().toFloat())
        val cx = rect.centerX() + dx
        val baseline = rect.centerY() + dy + baselineAdjust
        return RectF(
            cx - measuredWidth / 2f,
            baseline + bounds.top,
            cx + measuredWidth / 2f,
            baseline + bounds.bottom
        )
    }

    private fun topRightPunctuationInkRect(
        paint: TextPaint,
        text: String,
        rect: RectF
    ): RectF {
        return withPaint(paint, Paint.Align.LEFT) { markPaint ->
            val bounds = measureBounds(markPaint, text)
            val targetRight = rect.right - rect.width() * 0.10f
            val targetTop = rect.top + rect.height() * 0.08f
            val x = targetRight - bounds.right
            val y = targetTop - bounds.top
            RectF(x + bounds.left, y + bounds.top, x + bounds.right, y + bounds.bottom)
        }
    }

    private fun rotatableInkRect(
        paint: TextPaint,
        text: String,
        rect: RectF,
        baselineAdjust: Float
    ): RectF {
        val rotation = rotationFor(text)
        if (rotation == 0f) {
            return offsetInkRect(paint, text, rect, baselineAdjust, 0f, 0f)
        }
        val style = rotationStyleFor(text)
        val base = offsetInkRect(
            paint = paint,
            text = text,
            rect = rect,
            baselineAdjust = baselineAdjust,
            dx = paint.fontSpacing * style.dxEm,
            dy = paint.fontSpacing * style.dyEm
        )
        val cx = rect.centerX()
        val cy = rect.centerY()
        return rotatedBounds(base, cx, cy, rotation)
    }

    private fun smallKanaInkRect(
        paint: TextPaint,
        text: String,
        rect: RectF
    ): RectF {
        return withPaint(paint, Paint.Align.LEFT) { markPaint ->
            val bounds = measureBounds(markPaint, text)
            val targetRight = rect.right - rect.width() * 0.18f
            val targetTop = rect.top + rect.height() * 0.18f
            val x = targetRight - bounds.right
            val y = targetTop - bounds.top
            RectF(x + bounds.left, y + bounds.top, x + bounds.right, y + bounds.bottom)
        }
    }

    private fun rotatedBounds(rect: RectF, cx: Float, cy: Float, degrees: Float): RectF {
        val radians = Math.toRadians(degrees.toDouble())
        val cos = kotlin.math.cos(radians).toFloat()
        val sin = kotlin.math.sin(radians).toFloat()
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY

        fun include(x: Float, y: Float) {
            val dx = x - cx
            val dy = y - cy
            val rotatedX = cx + dx * cos - dy * sin
            val rotatedY = cy + dx * sin + dy * cos
            minX = minOf(minX, rotatedX)
            minY = minOf(minY, rotatedY)
            maxX = maxOf(maxX, rotatedX)
            maxY = maxOf(maxY, rotatedY)
        }

        include(rect.left, rect.top)
        include(rect.right, rect.top)
        include(rect.right, rect.bottom)
        include(rect.left, rect.bottom)
        return RectF(minX, minY, maxX, maxY)
    }

    private fun clampAndPadInkRect(rect: RectF, cellRect: RectF, textSize: Float): RectF {
        val horizontalPad = (textSize * 0.08f).coerceAtMost(cellRect.width() * 0.12f)
        val verticalPad = (textSize * 0.04f).coerceAtMost(cellRect.height() * 0.08f)
        val minWidth = (textSize * 0.70f).coerceAtMost(cellRect.width())
        val minHeight = (textSize * 0.78f).coerceAtMost(cellRect.height())
        val expanded = RectF(
            rect.left - horizontalPad,
            rect.top - verticalPad,
            rect.right + horizontalPad,
            rect.bottom + verticalPad
        )
        if (expanded.width() < minWidth) {
            val extra = (minWidth - expanded.width()) / 2f
            expanded.left -= extra
            expanded.right += extra
        }
        if (expanded.height() < minHeight) {
            val extra = (minHeight - expanded.height()) / 2f
            expanded.top -= extra
            expanded.bottom += extra
        }
        return RectF(
            expanded.left.coerceAtLeast(cellRect.left),
            expanded.top.coerceAtLeast(cellRect.top),
            expanded.right.coerceAtMost(cellRect.right),
            expanded.bottom.coerceAtMost(cellRect.bottom)
        )
    }

    private fun drawTopRightPunctuation(
        canvas: Canvas,
        paint: TextPaint,
        text: String,
        rect: RectF
    ) {
        withPaint(paint, Paint.Align.LEFT) { markPaint ->
            val bounds = measureBounds(markPaint, text)
            val targetRight = rect.right - rect.width() * 0.10f
            val targetTop = rect.top + rect.height() * 0.08f
            val x = targetRight - bounds.right
            val y = targetTop - bounds.top
            canvas.drawText(text, x, y, markPaint)
        }
    }

    private fun drawSmallKana(
        canvas: Canvas,
        paint: TextPaint,
        text: String,
        rect: RectF
    ) {
        withPaint(paint, Paint.Align.LEFT) { markPaint ->
            val bounds = measureBounds(markPaint, text)
            val targetRight = rect.right - rect.width() * 0.18f
            val targetTop = rect.top + rect.height() * 0.18f
            val x = targetRight - bounds.right
            val y = targetTop - bounds.top
            canvas.drawText(text, x, y, markPaint)
        }
    }

    private fun drawOffsetText(
        canvas: Canvas,
        paint: TextPaint,
        text: String,
        rect: RectF,
        baselineAdjust: Float,
        dx: Float,
        dy: Float
    ) {
        val cx = rect.centerX() + dx
        val cy = rect.centerY() + dy
        canvas.drawText(text, cx, cy + baselineAdjust, paint)
    }

    private fun drawInkCenteredText(
        canvas: Canvas,
        paint: TextPaint,
        text: String,
        rect: RectF
    ) {
        withPaint(paint, Paint.Align.LEFT) { markPaint ->
            val bounds = measureBounds(markPaint, text)
            val x = rect.centerX() - (bounds.left + bounds.right) * 0.5f
            val baseline = rect.centerY() - (bounds.top + bounds.bottom) * 0.5f
            canvas.drawText(text, x, baseline, markPaint)
        }
    }

    private fun inkCenteredTextRect(
        paint: TextPaint,
        text: String,
        rect: RectF
    ): RectF {
        withPaint(paint, Paint.Align.LEFT) { markPaint ->
            val bounds = measureBounds(markPaint, text)
            val x = rect.centerX() - (bounds.left + bounds.right) * 0.5f
            val baseline = rect.centerY() - (bounds.top + bounds.bottom) * 0.5f
            return RectF(
                x + bounds.left,
                baseline + bounds.top,
                x + bounds.right,
                baseline + bounds.bottom
            )
        }
    }

    private fun drawRotatableText(
        canvas: Canvas,
        paint: TextPaint,
        text: String,
        rect: RectF,
        baselineAdjust: Float
    ) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val style = rotationStyleFor(text)
        if (style.degrees == 0f) {
            canvas.drawText(text, cx, cy + baselineAdjust, paint)
            return
        }
        val drawX = cx + paint.fontSpacing * style.dxEm
        val drawY = cy + baselineAdjust + paint.fontSpacing * style.dyEm
        canvas.save()
        canvas.rotate(style.degrees, cx, cy)
        if (style.mirrorAfterRotation) {
            canvas.scale(1f, -1f, cx, cy)
        }
        canvas.drawText(text, drawX, drawY, paint)
        canvas.restore()
    }
}
