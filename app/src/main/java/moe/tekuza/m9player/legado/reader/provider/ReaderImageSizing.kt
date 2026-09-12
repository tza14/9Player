package moe.tekuza.m9player.legado.reader.provider

/**
 * 独立成段插图的放大上限。
 * 小装饰图（几十 px）不至于被拉成一整页；真插图（≥400px 宽）仍能铺满一页。
 */
internal const val READER_IMAGE_MAX_UPSCALE = 3f

/**
 * 独立成段插图在页面里的显示尺寸：按原图比例缩放到「刚好放进可用区域」（contain）——
 * 超大图缩小，低分辨率插图也放大，让整页插画真正占满一页，且不变形。
 *
 * 参考实现 legado 的 setTypeImage 只缩小不放大，结果 720×1024 的插画在手机上
 * 只占小半页；这里改成双向适配，放大倍数限制在 [READER_IMAGE_MAX_UPSCALE] 倍以内。
 */
internal fun fitReaderStandaloneImageSize(
    sourceWidth: Float,
    sourceHeight: Float,
    maxWidth: Float,
    maxHeight: Float
): Pair<Float, Float> {
    if (sourceWidth <= 0f || sourceHeight <= 0f || maxWidth <= 0f || maxHeight <= 0f) {
        return maxWidth to maxHeight
    }
    val fitScale = minOf(maxWidth / sourceWidth, maxHeight / sourceHeight)
    val scale = fitScale.coerceAtMost(READER_IMAGE_MAX_UPSCALE)
    return (sourceWidth * scale) to (sourceHeight * scale)
}
