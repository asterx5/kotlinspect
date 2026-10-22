@file:OptIn(ExperimentalForeignApi::class)

package io.github.asterx5.kotlinspect.internal

import io.github.asterx5.kotlinspect.internal.uimodel.BubbleCounts
import io.github.asterx5.kotlinspect.internal.uimodel.KsPalette
import io.github.asterx5.kotlinspect.internal.uimodel.ToastController
import io.github.asterx5.kotlinspect.internal.uimodel.ToastMessage
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import platform.CoreGraphics.CGAffineTransformMakeTranslation
import platform.CoreGraphics.CGPoint
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSNumber
import platform.QuartzCore.CABasicAnimation
import platform.QuartzCore.CAShapeLayer
import platform.QuartzCore.kCALineCapRound
import platform.UIKit.NSLayoutConstraint
import platform.UIKit.NSTextAlignmentCenter
import platform.UIKit.UIBezierPath
import platform.UIKit.UIColor
import platform.UIKit.UIEvent
import platform.UIKit.UIFont
import platform.UIKit.UIFontWeightBold
import platform.UIKit.UIFontWeightRegular
import platform.UIKit.UILabel
import platform.UIKit.UILayoutConstraintAxisHorizontal
import platform.UIKit.UILayoutConstraintAxisVertical
import platform.UIKit.UIStackView
import platform.UIKit.UIStackViewAlignmentCenter
import platform.UIKit.UITouch
import platform.UIKit.UIView
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import kotlin.math.PI
import kotlin.math.abs

internal fun uiColor(argb: Long): UIColor = UIColor(
    red = ((argb shr 16) and 0xFF) / 255.0,
    green = ((argb shr 8) and 0xFF) / 255.0,
    blue = (argb and 0xFF) / 255.0,
    alpha = ((argb shr 24) and 0xFF) / 255.0,
)

private val palette = KsPalette.Dark
private const val BUBBLE = 64.0
private const val DISC = 50.0
private const val MARGIN = 6.0
private const val DRAG_SLOP = 6.0

/** The bubble: dark disc with the call count, a spinning arc while calls are in flight, red when errors exist. */
internal class BubbleView(private val onTap: () -> Unit, private val onDragEnd: () -> Unit) :
    UIView(frame = CGRectMake(0.0, 0.0, BUBBLE, BUBBLE)) {

    private val disc = UIView(frame = CGRectMake((BUBBLE - DISC) / 2, (BUBBLE - DISC) / 2, DISC, DISC))
    private val count = UILabel(frame = disc.bounds)
    private val badge = UILabel(frame = CGRectMake(BUBBLE - 22.0, 0.0, 20.0, 20.0))
    private val ring = CAShapeLayer()

    private var touchStart: Pair<Double, Double>? = null
    private var centerStart: Pair<Double, Double>? = null
    private var dragging = false

    init {
        backgroundColor = UIColor.clearColor

        disc.backgroundColor = uiColor(KsPalette.INK)
        disc.layer.cornerRadius = DISC / 2
        disc.layer.borderWidth = 2.0
        disc.layer.borderColor = uiColor(palette.accent).CGColor
        disc.layer.shadowColor = UIColor.blackColor.CGColor
        disc.layer.shadowOpacity = 0.35f
        disc.layer.shadowRadius = 8.0
        disc.layer.shadowOffset = CGSizeMake(0.0, 4.0)
        addSubview(disc)

        count.textAlignment = NSTextAlignmentCenter
        count.textColor = uiColor(KsPalette.INK_TEXT)
        count.font = UIFont.monospacedSystemFontOfSize(17.0, UIFontWeightBold)
        count.text = "0"
        disc.addSubview(count)

        ring.frame = bounds
        ring.path = UIBezierPath.bezierPathWithArcCenter(
            center = CGPointMake(BUBBLE / 2, BUBBLE / 2),
            radius = BUBBLE / 2 - 2.0,
            startAngle = 0.0,
            endAngle = PI * 1.5,
            clockwise = true,
        ).CGPath
        ring.fillColor = UIColor.clearColor.CGColor
        ring.strokeColor = uiColor(palette.accent).CGColor
        ring.lineWidth = 3.0
        ring.lineCap = kCALineCapRound
        ring.hidden = true
        layer.addSublayer(ring)

        badge.textAlignment = NSTextAlignmentCenter
        badge.textColor = uiColor(KsPalette.INK_TEXT)
        badge.font = UIFont.monospacedSystemFontOfSize(10.0, UIFontWeightBold)
        badge.backgroundColor = uiColor(palette.error)
        badge.layer.cornerRadius = 10.0
        badge.clipsToBounds = true
        badge.hidden = true
        addSubview(badge)
    }

    fun update(counts: BubbleCounts) {
        val hasErrors = counts.errors > 0
        val tint = uiColor(if (hasErrors) palette.error else palette.accent)
        count.text = if (counts.total > 999) "999+" else counts.total.toString()
        count.font = UIFont.monospacedSystemFontOfSize(if (counts.total > 99) 13.0 else 17.0, UIFontWeightBold)
        disc.layer.borderColor = tint.CGColor
        ring.strokeColor = tint.CGColor
        badge.hidden = !hasErrors
        badge.text = if (counts.errors > 99) "99" else counts.errors.toString()
        val spinning = counts.inFlight > 0
        if (spinning && ring.hidden) {
            ring.hidden = false
            val spin = CABasicAnimation.animationWithKeyPath("transform.rotation.z")
            spin.fromValue = NSNumber(double = 0.0)
            spin.toValue = NSNumber(double = 2 * PI)
            spin.duration = 0.9
            spin.repeatCount = Float.MAX_VALUE
            ring.addAnimation(spin, forKey = "spin")
        } else if (!spinning && !ring.hidden) {
            ring.removeAnimationForKey("spin")
            ring.hidden = true
        }
    }

    private fun location(touches: Set<*>): Pair<Double, Double>? {
        val touch = touches.firstOrNull() as? UITouch ?: return null
        return touch.locationInView(superview).useContents { x to y }
    }

    override fun touchesBegan(touches: Set<*>, withEvent: UIEvent?) {
        touchStart = location(touches)
        centerStart = center.useContents { x to y }
        dragging = false
    }

    override fun touchesMoved(touches: Set<*>, withEvent: UIEvent?) {
        val start = touchStart ?: return
        val origin = centerStart ?: return
        val now = location(touches) ?: return
        val dx = now.first - start.first
        val dy = now.second - start.second
        if (!dragging && (abs(dx) > DRAG_SLOP || abs(dy) > DRAG_SLOP)) dragging = true
        if (dragging) (superview as? OverlayView)?.moveBubbleCenter(origin.first + dx, origin.second + dy)
    }

    override fun touchesEnded(touches: Set<*>, withEvent: UIEvent?) {
        if (dragging) onDragEnd() else onTap()
        touchStart = null
        dragging = false
    }

    override fun touchesCancelled(touches: Set<*>, withEvent: UIEvent?) {
        if (dragging) onDragEnd()
        touchStart = null
        dragging = false
    }
}

/** Dark pill: status dot, method badge, then the summary. */
internal class ToastView : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    private val dot = UIView(frame = CGRectMake(0.0, 0.0, 9.0, 9.0))
    private val method = UILabel()
    private val title = UILabel()
    private val subtitle = UILabel()
    private var shownId: Long? = null

    init {
        backgroundColor = uiColor(KsPalette.INK)
        layer.cornerRadius = 16.0
        layer.shadowColor = UIColor.blackColor.CGColor
        layer.shadowOpacity = 0.3f
        layer.shadowRadius = 12.0
        layer.shadowOffset = CGSizeMake(0.0, 6.0)
        userInteractionEnabled = false
        alpha = 0.0
        hidden = true

        dot.layer.cornerRadius = 4.5
        dot.translatesAutoresizingMaskIntoConstraints = false
        method.font = UIFont.monospacedSystemFontOfSize(12.0, UIFontWeightBold)
        title.font = UIFont.monospacedSystemFontOfSize(12.0, UIFontWeightRegular)
        title.textColor = uiColor(KsPalette.INK_TEXT)
        subtitle.font = UIFont.systemFontOfSize(11.0)
        subtitle.textColor = uiColor(KsPalette.INK_TEXT).colorWithAlphaComponent(0.6)

        val texts = UIStackView(arrangedSubviews = listOf(title, subtitle))
        texts.axis = UILayoutConstraintAxisVertical
        val row = UIStackView(arrangedSubviews = listOf(dot, method, texts))
        row.axis = UILayoutConstraintAxisHorizontal
        row.alignment = UIStackViewAlignmentCenter
        row.spacing = 8.0
        row.translatesAutoresizingMaskIntoConstraints = false
        addSubview(row)
        NSLayoutConstraint.activateConstraints(
            listOf(
                dot.widthAnchor.constraintEqualToConstant(9.0),
                dot.heightAnchor.constraintEqualToConstant(9.0),
                row.leadingAnchor.constraintEqualToAnchor(leadingAnchor, constant = 14.0),
                row.trailingAnchor.constraintEqualToAnchor(trailingAnchor, constant = -14.0),
                row.topAnchor.constraintEqualToAnchor(topAnchor, constant = 10.0),
                row.bottomAnchor.constraintEqualToAnchor(bottomAnchor, constant = -10.0),
            ),
        )
    }

    fun show(message: ToastMessage?) {
        if (message == null) {
            if (shownId == null) return
            shownId = null
            UIView.animateWithDuration(
                duration = 0.2,
                animations = {
                    alpha = 0.0
                    transform = CGAffineTransformMakeTranslation(0.0, -16.0)
                },
                completion = { _ -> if (shownId == null) hidden = true },
            )
            return
        }
        shownId = message.id
        dot.backgroundColor = uiColor(if (message.isError) palette.error else palette.ok)
        val m = message.method
        method.hidden = m == null
        method.text = m
        method.textColor = uiColor(if (m != null) palette.methodColor(m) else palette.textMuted)
        title.text = if (m != null) message.title.removePrefix(m).trimStart() else message.title
        subtitle.text = message.subtitle
        subtitle.hidden = message.subtitle == null
        if (hidden || alpha < 1.0) {
            hidden = false
            transform = CGAffineTransformMakeTranslation(0.0, -16.0)
            UIView.animateWithDuration(0.25) {
                alpha = 1.0
                transform = CGAffineTransformMakeTranslation(0.0, 0.0)
            }
        }
    }
}

/**
 * Transparent, full-size view holding the bubble and toast. Touches anywhere except the bubble
 * pass through to the views underneath, so it can sit on top of an app's root view.
 */
internal class OverlayView(
    private val sessionId: String?,
    private val embedded: Boolean,
) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {

    private val bubble = BubbleView(onTap = ::openInspector, onDragEnd = ::snap)
    private val toast = ToastView()
    private var scope: CoroutineScope? = null
    private var toasts: ToastController? = null
    private var detach: (() -> Unit)? = null

    // Bubble anchor, kept across rotations and resizes: which side it hugs and how far down (0..1).
    private var alignRight = true
    private var yFraction = 0.72

    init {
        backgroundColor = UIColor.clearColor
        autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
        addSubview(toast)
        addSubview(bubble)
        bubble.hidden = true
        toast.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activateConstraints(
            listOf(
                toast.topAnchor.constraintEqualToAnchor(safeAreaLayoutGuide.topAnchor, constant = 8.0),
                toast.centerXAnchor.constraintEqualToAnchor(centerXAnchor),
                toast.widthAnchor.constraintLessThanOrEqualToConstant(440.0),
                toast.leadingAnchor.constraintGreaterThanOrEqualToAnchor(leadingAnchor, constant = 12.0),
            ),
        )
    }

    override fun hitTest(point: CValue<CGPoint>, withEvent: UIEvent?): UIView? {
        val hit = super.hitTest(point, withEvent)
        return if (hit === this) null else hit
    }

    override fun didMoveToWindow() {
        super.didMoveToWindow()
        if (window != null) start() else stop()
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        place()
    }

    private fun start() {
        if (scope != null) return
        val host = io.github.asterx5.kotlinspect.Kotlinspect.host() ?: return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = s
        if (embedded) detach = host.attachEmbeddedOverlay()
        val t = host.toasts(sessionId)
        toasts = t
        s.launch { host.bubbleCounts(sessionId).collect { bubble.update(it) } }
        s.launch { t.current.collect { toast.show(it) } }
        s.launch {
            combine(host.bubbleVisible, host.embeddedOverlays) { _, _ -> }.collect {
                val visible = if (embedded) host.bubbleVisible.value && host.isActive else host.showAutomaticOverlay
                bubble.hidden = !visible
                hidden = !visible && !embedded
            }
        }
    }

    private fun stop() {
        scope?.cancel()
        scope = null
        toasts?.close()
        toasts = null
        detach?.invoke()
        detach = null
    }

    private fun openInspector() {
        io.github.asterx5.kotlinspect.Kotlinspect.openInspector(sessionId)
    }

    /** Range for the bubble's center: this view's bounds inside the safe area. */
    private fun area(): List<Double> {
        val (w, h) = bounds.useContents { size.width to size.height }
        val (top, left, bottom, right) = safeAreaInsets.useContents { listOf(top, left, bottom, right) }
        val half = BUBBLE / 2
        val minX = left + MARGIN + half
        val minY = top + MARGIN + half
        return listOf(minX, minY, (w - right - MARGIN - half).coerceAtLeast(minX), (h - bottom - MARGIN - half).coerceAtLeast(minY))
    }

    fun moveBubbleCenter(x: Double, y: Double) {
        val (minX, minY, maxX, maxY) = area()
        bubble.setCenter(CGPointMake(x.coerceIn(minX, maxX), y.coerceIn(minY, maxY)))
    }

    private fun snap() {
        val (minX, minY, maxX, maxY) = area()
        val (x, y) = bubble.center.useContents { this.x to this.y }
        alignRight = x - minX > maxX - x
        yFraction = if (maxY > minY) (y - minY) / (maxY - minY) else 0.0
        UIView.animateWithDuration(0.2) { place() }
    }

    private fun place() {
        val (minX, minY, maxX, maxY) = area()
        bubble.setCenter(CGPointMake(if (alignRight) maxX else minX, minY + (maxY - minY) * yFraction.coerceIn(0.0, 1.0)))
    }
}

/** A window that lets touches through wherever its overlay has nothing to hit. */
internal class PassthroughWindow(scene: UIWindowScene) : UIWindow(windowScene = scene) {
    override fun hitTest(point: CValue<CGPoint>, withEvent: UIEvent?): UIView? {
        val hit = super.hitTest(point, withEvent)
        return if (hit === this || hit === rootViewController?.view) null else hit
    }
}
