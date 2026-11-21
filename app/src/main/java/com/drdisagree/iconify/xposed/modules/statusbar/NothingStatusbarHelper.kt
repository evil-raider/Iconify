package com.drdisagree.iconify.xposed.modules.statusbar

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import com.drdisagree.iconify.data.common.Const.SYSTEMUI_PACKAGE
import com.drdisagree.iconify.xposed.core.hook.hookMethod
import com.drdisagree.iconify.xposed.core.hook.intercept
import com.drdisagree.iconify.xposed.core.hook.runAfter
import com.drdisagree.iconify.xposed.core.layout.hookLayout
import com.drdisagree.iconify.xposed.core.reflect.callMethodSilently
import com.drdisagree.iconify.xposed.core.reflect.findClass
import com.drdisagree.iconify.xposed.core.reflect.getExtraFieldSilently
import com.drdisagree.iconify.xposed.core.reflect.getFieldSilently
import com.drdisagree.iconify.xposed.core.reflect.setExtraField
import com.drdisagree.iconify.xposed.core.reflect.setFieldSilently
import com.drdisagree.iconify.xposed.core.resources.ResourceHookManager
import com.drdisagree.iconify.xposed.modules.extras.utils.misc.ViewHelper.toPx
import java.lang.reflect.Method

/**
 * Nothing OS status bar adaptations extracted from [StatusbarMisc] to keep upstream merges clean.
 */
@SuppressLint("DiscouragedApi")
internal object NothingStatusbarHelper {

    fun install(
        context: Context,
        isDualStatusbarEnabled: () -> Boolean,
        getClockPosition: () -> Int,
        getPhoneStatusBarView: () -> ViewGroup?,
        getClockView: () -> TextView?
    ) {
        installContentExtAndClockPaddingHooks(
            context = context,
            isDualStatusbarEnabled = isDualStatusbarEnabled,
            getClockPosition = getClockPosition
        )
        installSpacingAndPrivacyDotHooks(
            context = context,
            isDualStatusbarEnabled = isDualStatusbarEnabled,
            getClockPosition = getClockPosition,
            getPhoneStatusBarView = getPhoneStatusBarView,
            getClockView = getClockView
        )
    }

    private fun installContentExtAndClockPaddingHooks(
        context: Context,
        isDualStatusbarEnabled: () -> Boolean,
        getClockPosition: () -> Int
    ) {
        val clockId = context.resources.getIdentifier("clock", "id", context.packageName)
        val leftMinWidthPx = context.toPx(48)
        val rightMinExtraPx = context.toPx(4)
        val rightClockPadStartPx = context.toPx(2)
        val rightClockPadEndPx = context.toPx(2)

        findClass("com.nothing.systemui.statusbar.widgets.NTStatusBarContentExt", suppressError = true)
            ?.apply {
                hookMethod("getLeftSideMinWidth")
                    .suppressError()
                    .intercept { chain ->
                        if (!isDualStatusbarEnabled() && getClockPosition() != 0) {
                            leftMinWidthPx
                        } else {
                            chain.proceed()
                        }
                    }

                hookMethod("getRightSideMinWidth")
                    .suppressError()
                    .intercept { chain ->
                        val baseMin = (chain.proceed() as? Int) ?: 0
                        if (!isDualStatusbarEnabled() && getClockPosition() == 2) {
                            val rightView = chain.thisObject?.getFieldSilently("mRight") as? View
                            val measuredW = rightView?.measuredWidth ?: 0
                            if (measuredW > 0) {
                                maxOf(baseMin, measuredW + rightMinExtraPx)
                            } else {
                                baseMin
                            }
                        } else {
                            baseMin
                        }
                    }

                hookMethod("onMeasure")
                    .suppressError()
                    .runAfter { chain, _ ->
                        if (!isDualStatusbarEnabled() && getClockPosition() == 2) {
                            val ext = chain.thisObject as? ViewGroup ?: return@runAfter
                            val rightView = ext.getFieldSilently("mRight") as? View ?: return@runAfter
                            val leftView = ext.getFieldSilently("mLeft") as? View
                            val spaceView = ext.getFieldSilently("mSpace") as? View
                            val totalW = ext.measuredWidth - ext.paddingLeft - ext.paddingRight
                            val contentH = ext.measuredHeight - ext.paddingTop - ext.paddingBottom
                            if (totalW <= 0 || contentH <= 0) return@runAfter

                            val spaceW = if (spaceView != null && spaceView.visibility != View.GONE) {
                                spaceView.measuredWidth
                            } else {
                                0
                            }
                            // Prevent privacy_dot Space or CentralSurfacesImplEx marginEnd inside mRight from shifting icons left
                            if (rightView is ViewGroup) {
                                (rightView.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                                    if (lp.marginEnd != 0) lp.marginEnd = 0
                                }
                                for (i in 0 until rightView.childCount) {
                                    val inner = rightView.getChildAt(i)
                                    (inner?.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                                        if (lp.marginStart != 0) lp.marginStart = 0
                                        if (lp.marginEnd != 0) lp.marginEnd = 0
                                    }
                                    if (inner is ViewGroup) {
                                        for (j in 0 until inner.childCount) {
                                            val c = inner.getChildAt(j)
                                            (c?.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                                                if (lp.marginStart != 0) lp.marginStart = 0
                                                if (lp.marginEnd != 0) lp.marginEnd = 0
                                            }
                                            if (c is Space) {
                                                if (c.visibility != View.GONE) c.visibility = View.GONE
                                                if (c.layoutParams?.width != 0) {
                                                    c.layoutParams?.width = 0
                                                }
                                            }
                                            if (c?.javaClass?.simpleName == "ComposeView" && c.visibility != View.GONE) {
                                                c.visibility = View.GONE
                                            }
                                        }
                                    }
                                }
                            }
                            val maxRightW = (totalW - spaceW).coerceAtLeast(0)

                            rightView.measure(
                                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                                View.MeasureSpec.makeMeasureSpec(contentH, View.MeasureSpec.EXACTLY)
                            )
                            val rawRightW = rightView.measuredWidth
                            val neededRightW = rawRightW.coerceAtMost(maxRightW)
                            val availLeftW = (totalW - neededRightW - spaceW).coerceAtLeast(0)

                            if (neededRightW != rawRightW) {
                                rightView.measure(
                                    View.MeasureSpec.makeMeasureSpec(neededRightW, View.MeasureSpec.EXACTLY),
                                    View.MeasureSpec.makeMeasureSpec(contentH, View.MeasureSpec.EXACTLY)
                                )
                            }
                            leftView?.measure(
                                View.MeasureSpec.makeMeasureSpec(availLeftW, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(contentH, View.MeasureSpec.EXACTLY)
                            )
                        }
                    }
            }

        findClass("$SYSTEMUI_PACKAGE.statusbar.policy.Clock", suppressError = true)
            ?.hookMethod("onDensityOrFontScaleChanged")
            ?.suppressError()
            ?.runAfter { chain, _ ->
                if (!isDualStatusbarEnabled() && getClockPosition() == 2) {
                    val clock = chain.thisObject as? View ?: return@runAfter
                    if (clock.id == clockId &&
                        (clock.paddingStart != rightClockPadStartPx || clock.paddingEnd != rightClockPadEndPx)
                    ) {
                        clock.setPaddingRelative(rightClockPadStartPx, 0, rightClockPadEndPx, 0)
                    }
                }
            }
    }

    private fun installSpacingAndPrivacyDotHooks(
        context: Context,
        isDualStatusbarEnabled: () -> Boolean,
        getClockPosition: () -> Int,
        getPhoneStatusBarView: () -> ViewGroup?,
        getClockView: () -> TextView?
    ) {
        // Set uniform 3dp spacing across all status bar icons and remove uneven internal spacers/margins
        ResourceHookManager
            .hookDimen()
            .forPackageName(SYSTEMUI_PACKAGE)
            .addResource("status_bar_system_icon_spacing") { 3 }
            .addResource("status_bar_mobile_icon_spacing") { 3 }
            .addResource("status_bar_cast_icon_spacing") { 3 }
            .addResource("status_bar_icon_horizontal_margin") { 1 }
            .addResource("status_bar_bindable_icon_padding") { 1 }
            .addResource("status_bar_wifi_signal_spacer_width") { 0 }
            .addResource("status_bar_wifi_signal_horizontal_margin") { 0 }
            .addResource("status_bar_airplane_spacer_width") { 0 }
            .addResource("status_bar_mobile_container_margin_start") { 0 }
            .addResource("status_bar_mobile_container_margin_end") { 0 }
            .addResource("status_bar_mobile_type_container_margin_end") { 0 }
            .addResource("status_bar_icons_padding_start") { 0 }
            .addResource("status_bar_icons_padding_end") { 1 }
            .addResource("signal_cluster_battery_padding") { 1 }
            .addResource("signal_cluster_battery_padding_new") { 1 }
            .addResource("status_bar_battery_end_padding") { 0 }
            .apply()

        val sysRes = context.resources
        val systemIconsId = sysRes.getIdentifier("system_icons", "id", SYSTEMUI_PACKAGE)
        val mobileGroupIdCached = sysRes.getIdentifier("mobile_group", "id", SYSTEMUI_PACKAGE)
        val mobileTypeIdCached = sysRes.getIdentifier("mobile_type", "id", SYSTEMUI_PACKAGE)
        val mobileTypeContainerIdCached = sysRes.getIdentifier("mobile_type_container", "id", SYSTEMUI_PACKAGE)
        val clockIdCached = sysRes.getIdentifier("clock", "id", context.packageName)
        val privacyDotIdCached = sysRes.getIdentifier("privacy_dot", "id", SYSTEMUI_PACKAGE)

        val gapPx = context.toPx(3)
        val endPadPx = context.toPx(1)
        val wifiPullPx = context.toPx(1)
        val overlapBasePx = context.toPx(9)
        val badgeShiftRightBasePx = context.toPx(1).toFloat()
        val badgeShiftUpPx = -context.toPx(3).toFloat()
        val dotExtraDownPx = context.toPx(2)
        val dotFallbackTopPx = context.toPx(6)
        val dotFallbackExtraStartPx = context.toPx(3)

        val visibleStateMethodCache = HashMap<Class<*>, Method?>(16)
        val tmpLoc = IntArray(2)
        val tmpRect = Rect()

        fun getVisibleStateFast(view: View): Int? {
            val cls = view.javaClass
            val method = if (visibleStateMethodCache.containsKey(cls)) {
                visibleStateMethodCache[cls]
            } else {
                val found = runCatching { cls.getMethod("getVisibleState") }.getOrNull()
                visibleStateMethodCache[cls] = found
                found
            }
            return method?.let { runCatching { it.invoke(view) as? Int }.getOrNull() }
        }

        fun getSlotCached(view: View): String {
            val cached = view.getExtraFieldSilently("iconifySlot") as? String
            if (cached != null) return cached
            val slot = (view.callMethodSilently("getSlot") as? String).orEmpty()
            view.setExtraField("iconifySlot", slot)
            return slot
        }

        fun unclipSubtree(v: View?) {
            if (v == null) return
            if (v.clipBounds != null) v.clipBounds = null
            if (v is ViewGroup) {
                if (v.clipChildren) v.clipChildren = false
                if (v.clipToPadding) v.clipToPadding = false
                for (k in 0 until v.childCount) {
                    unclipSubtree(v.getChildAt(k))
                }
            }
        }

        fun isStatusBarContainer(container: ViewGroup): Boolean {
            val cached = container.getExtraFieldSilently("iconifyIsSbContainer") as? Boolean
            if (cached != null) return cached
            val parent = container.parent as? ViewGroup ?: return false
            val isSb = systemIconsId != 0 && parent.id == systemIconsId
            container.setExtraField("iconifyIsSbContainer", isSb)
            return isSb
        }

        fun alignStatusIconContainer(container: ViewGroup, checkLayoutParent: Boolean) {
            if (!isStatusBarContainer(container)) return
            val isRtl = container.layoutDirection == View.LAYOUT_DIRECTION_RTL
            var cursor = if (isRtl) endPadPx else container.width - endPadPx
            var prevSlot: String? = null

            val startIdx = if (isRtl) 0 else container.childCount - 1
            val endIdx = if (isRtl) container.childCount else -1
            val step = if (isRtl) 1 else -1

            var i = startIdx
            while (i != endIdx) {
                val child = container.getChildAt(i)
                i += step
                if (child == null || child.visibility != View.VISIBLE || child.alpha <= 0.05f || child.width <= 0) {
                    continue
                }
                val vs = getVisibleStateFast(child)
                if (vs == 2) continue // STATE_HIDDEN

                if (child.getExtraFieldSilently("iconifyUnclipped") != true) {
                    unclipSubtree(child)
                    child.setExtraField("iconifyUnclipped", true)
                }

                val slot = getSlotCached(child)
                if (slot == "mobile" && child.layoutParams?.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
                    child.layoutParams = child.layoutParams?.apply {
                        width = ViewGroup.LayoutParams.WRAP_CONTENT
                    }
                    child.post { child.requestLayout() }
                }

                if (prevSlot == "mobile" && slot == "wifi") {
                    cursor += if (isRtl) -wifiPullPx else wifiPullPx
                }

                val targetX = if (isRtl) cursor.toFloat() else (cursor - child.width).toFloat()
                val desiredTx = targetX - child.left
                if (child.translationX != desiredTx) {
                    child.animate().cancel()
                    child.translationX = desiredTx
                }
                cursor = if (isRtl) {
                    targetX.toInt() + child.width + gapPx
                } else {
                    targetX.toInt() - gapPx
                }
                prevSlot = slot
            }

            if (!checkLayoutParent) return

            // Restrict sibling cleanup and parent relayout strictly to status bar's system_icons (never touch QS header MotionLayout)
            val parent = container.parent as? ViewGroup ?: return
            if (systemIconsId != 0 && parent.id != systemIconsId) return

            val lastChildCount = parent.getExtraFieldSilently("iconifySysIconsChildCount") as? Int
            if (lastChildCount != parent.childCount) {
                parent.setExtraField("iconifySysIconsChildCount", parent.childCount)
                for (j in 0 until parent.childCount) {
                    val sc = parent.getChildAt(j) ?: continue
                    if (sc.javaClass.simpleName == "ComposeView" && sc.visibility != View.GONE) {
                        sc.visibility = View.GONE
                    }
                    if (sc !== container) {
                        val lp = sc.layoutParams as? ViewGroup.MarginLayoutParams
                        if (lp != null && (lp.marginStart != 0 || lp.marginEnd != 0)) {
                            lp.marginStart = 0
                            lp.marginEnd = 0
                            sc.layoutParams = lp
                        }
                    }
                }
            }

            val w = container.width
            val prevW = container.getExtraFieldSilently("iconifyLastWidth") as? Int
            if (prevW != null && prevW != w) {
                container.setExtraField("iconifyLastWidth", w)
                var p = container.parent
                while (p is ViewGroup) {
                    if (p.javaClass.simpleName == "NTStatusBarContentExt") {
                        p.post { p.requestLayout() }
                        break
                    }
                    p = p.parent
                }
            } else if (prevW == null) {
                container.setExtraField("iconifyLastWidth", w)
            }
        }

        findClass("$SYSTEMUI_PACKAGE.statusbar.phone.StatusIconContainer", suppressError = true)
            ?.apply {
                hookMethod("reloadDimens")
                    .suppressError()
                    .runAfter { chain, _ ->
                        val container = chain.thisObject as? ViewGroup ?: return@runAfter
                        container.setFieldSilently("mIconSpacing", gapPx)
                        container.setFieldSilently("mMobileIconSpacing", gapPx)
                        container.setFieldSilently("mCastIconSpacing", gapPx)
                        if (container.paddingEnd != endPadPx) {
                            container.setPaddingRelative(
                                container.paddingStart,
                                container.paddingTop,
                                endPadPx,
                                container.paddingBottom
                            )
                        }
                    }

                hookMethod("onFinishInflate")
                    .suppressError()
                    .runAfter { chain, _ ->
                        val container = chain.thisObject as? ViewGroup ?: return@runAfter
                        (container.layoutParams as? LinearLayout.LayoutParams)?.apply {
                            if (width == 0 && weight > 0f) {
                                width = LinearLayout.LayoutParams.WRAP_CONTENT
                                weight = 0f
                                container.layoutParams = this
                            }
                        }
                    }

                hookMethod("onMeasure")
                    .suppressError()
                    .runAfter { chain, _ ->
                        val container = chain.thisObject as? ViewGroup ?: return@runAfter
                        (container.layoutParams as? LinearLayout.LayoutParams)?.apply {
                            if (width == 0 && weight > 0f) {
                                width = LinearLayout.LayoutParams.WRAP_CONTENT
                                weight = 0f
                                container.layoutParams = this
                            }
                        }
                    }

                hookMethod("ntClipToRow")
                    .suppressError()
                    .runAfter { chain, _ ->
                        val container = chain.thisObject as? ViewGroup ?: return@runAfter
                        if (container.clipBounds != null) container.clipBounds = null
                        if (container.clipChildren) container.clipChildren = false
                        if (container.clipToPadding) container.clipToPadding = false
                    }

                hookMethod("resetPaddingToInitialValues")
                    .suppressError()
                    .runAfter { chain, _ ->
                        val container = chain.thisObject as? ViewGroup ?: return@runAfter
                        if (container.paddingEnd != endPadPx) {
                            container.setPaddingRelative(
                                container.paddingStart,
                                container.paddingTop,
                                endPadPx,
                                container.paddingBottom
                            )
                        }
                    }

                // Single hook point: onLayout -> calculateIconTranslations -> applyIconStates always ends here
                hookMethod("applyIconStates")
                    .suppressError()
                    .runAfter { chain, _ ->
                        val container = chain.thisObject as? ViewGroup ?: return@runAfter
                        alignStatusIconContainer(container, true)
                    }
            }

        // Prevent NTStatusIconShowHideController.applyClip from clipping and keep icons aligned during tick() animations
        findClass("com.nothing.systemui.statusbar.phone.NTStatusIconShowHideController", suppressError = true)
            ?.apply {
                hookMethod("applyClip")
                    .suppressError()
                    .intercept { chain ->
                        val v = chain.args.firstOrNull() as? View
                        if (v != null && v.clipBounds != null) v.clipBounds = null
                        null
                    }

                hookMethod("tick")
                    .suppressError()
                    .runAfter { chain, _ ->
                        val container = chain.thisObject?.getFieldSilently("container") as? ViewGroup
                            ?: return@runAfter
                        alignStatusIconContainer(container, false)
                    }
            }

        // Allow NTRiveMobileWrapper / NTRiveStatusBarIconWrapper to expand to its innerView's measuredWidth (e.g. when 4G+ badge is visible)
        findClass("com.nothing.systemui.statusbar.policy.bluetooth.NTRiveStatusBarIconWrapper", suppressError = true)
            ?.hookMethod("setInner")
            ?.suppressError()
            ?.runAfter { chain, _ ->
                runCatching {
                    val wrapper = chain.thisObject as? ViewGroup ?: return@runCatching
                    unclipSubtree(wrapper)
                    wrapper.setExtraField("iconifyUnclipped", true)
                    if (getSlotCached(wrapper) == "mobile") {
                        val lp = wrapper.layoutParams
                        if (lp != null && lp.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
                            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                            wrapper.layoutParams = lp
                        }
                    }
                }
            }

        hookLayout()
            .packageName(SYSTEMUI_PACKAGE)
            .resource("layout", "status_bar")
            .suppressError()
            .run { liparam ->
                val systemIcons = liparam.view.findViewById<ViewGroup>(
                    liparam.res.getIdentifier("system_icons", "id", SYSTEMUI_PACKAGE)
                ) ?: return@run
                systemIcons.setPaddingRelative(
                    0,
                    systemIcons.paddingTop,
                    0,
                    systemIcons.paddingBottom
                )
                val statusIcons = systemIcons.findViewById<ViewGroup>(
                    liparam.res.getIdentifier("statusIcons", "id", SYSTEMUI_PACKAGE)
                )
                statusIcons?.setPaddingRelative(
                    statusIcons.paddingStart,
                    statusIcons.paddingTop,
                    endPadPx,
                    statusIcons.paddingBottom
                )
                (statusIcons?.layoutParams as? LinearLayout.LayoutParams)?.apply {
                    width = LinearLayout.LayoutParams.WRAP_CONTENT
                    weight = 0f
                    statusIcons.layoutParams = this
                }
            }

        fun applyFloatingMobileTypeStyle(
            mobileType: View?,
            mobileTypeContainer: ViewGroup?,
            factor: Float = 1f,
            animShiftCompensatePx: Float = 0f
        ) {
            val scale = 0.66f
            val overlapNegMarginPx = (-overlapBasePx * factor.coerceIn(0f, 1f)).toInt()
            val shiftRightPx = badgeShiftRightBasePx - animShiftCompensatePx
            val isBadgeVisible = mobileType != null &&
                mobileType.visibility == View.VISIBLE &&
                factor > 0.02f &&
                (mobileType.layoutParams?.width != 0)
            val targetMarginEnd = if (isBadgeVisible) overlapNegMarginPx else 0

            val actualContainer = mobileTypeContainer?.takeIf { it.id != mobileGroupIdCached }

            // Ensure mobile_group itself never gets negative marginEnd
            if (mobileTypeContainer != null && mobileTypeContainer.id == mobileGroupIdCached) {
                (mobileTypeContainer.layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
                    if (marginStart != 0 || marginEnd != 0) {
                        marginStart = 0
                        marginEnd = 0
                        mobileTypeContainer.layoutParams = this
                    }
                }
            }

            actualContainer?.apply {
                if (clipChildren) clipChildren = false
                if (clipToPadding) clipToPadding = false
                if (paddingStart != 0 || paddingTop != 0 || paddingEnd != 0 || paddingBottom != 0) {
                    setPaddingRelative(0, 0, 0, 0)
                }
                (layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
                    if (marginStart != 0 || marginEnd != targetMarginEnd) {
                        marginStart = 0
                        marginEnd = targetMarginEnd
                        layoutParams = this
                    }
                }
                if (translationZ != 4f) translationZ = 4f
            }

            mobileType?.apply {
                if (this is ImageView && cropToPadding) {
                    cropToPadding = false
                }
                if (pivotX != 0f) pivotX = 0f
                if (pivotY != 0f) pivotY = 0f
                if (scaleX != scale) scaleX = scale
                if (scaleY != scale) scaleY = scale
                if (translationX != shiftRightPx) translationX = shiftRightPx
                if (translationY != badgeShiftUpPx) translationY = badgeShiftUpPx
                if (translationZ != 4f) translationZ = 4f
                val childMarginEnd = if (actualContainer == null) targetMarginEnd else 0
                (layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
                    if (marginStart != 0 || marginEnd != childMarginEnd) {
                        marginStart = 0
                        marginEnd = childMarginEnd
                        layoutParams = this
                    }
                }
            }
        }

        // Style mobile_type (4G+ / 3G / 2G) as a compact badge partially suspended over the top-left of mobile_signal
        findClass("$SYSTEMUI_PACKAGE.statusbar.pipeline.mobile.ui.binder.MobileIconBinder", suppressError = true)
            ?.hookMethod("bind")
            ?.suppressError()
            ?.runAfter { chain, _ ->
                runCatching {
                    val mobileView = chain.args.firstOrNull() as? ViewGroup ?: return@runCatching
                    val mobileGroup = if (mobileGroupIdCached != 0) mobileView.findViewById<ViewGroup>(mobileGroupIdCached) else null
                    val mobileType = if (mobileTypeIdCached != 0) mobileView.findViewById<View>(mobileTypeIdCached) else null
                    val mobileTypeContainer = if (mobileTypeContainerIdCached != 0) mobileView.findViewById<ViewGroup>(mobileTypeContainerIdCached) else null

                    unclipSubtree(mobileView)
                    mobileGroup?.let { unclipSubtree(it) }

                    applyFloatingMobileTypeStyle(mobileType, mobileTypeContainer)
                }
            }

        findClass("com.nothing.systemui.statusbar.pipeline.mobile.ui.binder.NTMobileTypeAnimator", suppressError = true)
            ?.apply {
                hookMethod("captureMarginEnd")
                    .suppressError()
                    .intercept { _ ->
                        // Pull mobile_signal directly under the right edge of the scaled 4G+ badge
                        -overlapBasePx
                    }

                hookMethod("applyFixedMatrix")
                    .suppressError()
                    .runAfter { chain, _ ->
                        runCatching {
                            val mobileType = chain.args.firstOrNull() as? View ?: return@runCatching
                            val parent = mobileType.parent as? ViewGroup
                            val state = chain.thisObject?.callMethodSilently("getState", mobileType)
                            val factor = (state?.callMethodSilently("getFactor") as? Float) ?: 1f
                            val lp = mobileType.layoutParams as? ViewGroup.MarginLayoutParams
                            val drawableIw = (mobileType as? ImageView)?.drawable?.intrinsicWidth
                                ?.takeIf { it > 0 }
                            if (drawableIw != null) {
                                mobileType.setExtraField("iconifyFullW", drawableIw)
                            }
                            val fullW = drawableIw
                                ?: (mobileType.getExtraFieldSilently("iconifyFullW") as? Int)
                                ?: mobileType.measuredWidth
                            val origMe = -overlapBasePx
                            val compensate = if (factor < 0.99f && lp != null && lp.width >= 0 && fullW > 0) {
                                val fullSpan = (fullW + origMe).coerceAtLeast(0)
                                val curSpan = lp.width + lp.marginEnd
                                (fullSpan - curSpan).toFloat().coerceAtLeast(0f)
                            } else {
                                0f
                            }
                            applyFloatingMobileTypeStyle(mobileType, parent, factor, compensate)
                        }
                    }

                hookMethod("applyWidth")
                    .suppressError()
                    .runAfter { chain, _ ->
                        runCatching {
                            val mobileType = chain.args.firstOrNull() as? View ?: return@runCatching
                            // NTMobileTypeAnimator.applyWidth(View, Int fullWidth, Int origMarginEnd, Float factor)
                            val fullW = (chain.args.getOrNull(1) as? Int)?.takeIf { it > 0 }?.also {
                                mobileType.setExtraField("iconifyFullW", it)
                            } ?: (mobileType.getExtraFieldSilently("iconifyFullW") as? Int)
                                ?: mobileType.measuredWidth
                            val factor = (chain.args.getOrNull(3) as? Float) ?: 1f
                            val parent = mobileType.parent as? ViewGroup
                            val hidden = factor <= 0.02f || mobileType.visibility != View.VISIBLE
                            val lp = mobileType.layoutParams as? ViewGroup.MarginLayoutParams ?: return@runCatching
                            if (hidden) {
                                if (lp.width != 0 || lp.marginEnd != 0) {
                                    lp.width = 0
                                    lp.marginEnd = 0
                                    mobileType.layoutParams = lp
                                }
                                (parent?.layoutParams as? ViewGroup.MarginLayoutParams)?.let { clp ->
                                    if (clp.marginEnd != 0 || clp.marginStart != 0) {
                                        clp.marginStart = 0
                                        clp.marginEnd = 0
                                        parent.layoutParams = clp
                                    }
                                }
                            } else {
                                val origMe = -overlapBasePx
                                val fullSpan = (fullW + origMe).coerceAtLeast(0)
                                val curMarginEnd = (origMe * factor.coerceIn(0f, 1f)).toInt()
                                val curSpan = lp.width.coerceAtLeast(0) + curMarginEnd
                                val compensate = (fullSpan - curSpan).toFloat().coerceAtLeast(0f)
                                applyFloatingMobileTypeStyle(mobileType, parent, factor, compensate)
                            }
                            var p = mobileType.parent
                            while (p is ViewGroup) {
                                if (p.clipChildren) p.clipChildren = false
                                if (p.clipToPadding) p.clipToPadding = false
                                if (p.clipBounds != null) p.clipBounds = null
                                if (p.javaClass.simpleName == "NTRiveMobileWrapper") {
                                    if (p.layoutParams?.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
                                        p.layoutParams = p.layoutParams?.apply {
                                            width = ViewGroup.LayoutParams.WRAP_CONTENT
                                        }
                                    }
                                    p.requestLayout()
                                    break
                                }
                                p = p.parent
                            }
                        }
                    }
            }

        // Keep Wi-Fi icon horizontal padding clean so gaps match 3dp on both sides
        findClass("$SYSTEMUI_PACKAGE.statusbar.pipeline.wifi.ui.binder.WifiViewBinder", suppressError = true)
            ?.hookMethod("bind")
            ?.suppressError()
            ?.runAfter { chain, _ ->
                runCatching {
                    val wifiView = chain.args.firstOrNull() as? ViewGroup ?: return@runCatching
                    if (wifiView.paddingStart != 0 || wifiView.paddingEnd != 0) {
                        wifiView.setPaddingRelative(
                            0,
                            wifiView.paddingTop,
                            0,
                            wifiView.paddingBottom
                        )
                    }
                }
            }

        // Stop Nothing OS from shifting system_icons left when privacy dot appears/disappears
        findClass("com.nothing.systemui.statusbar.phone.CentralSurfacesImplEx", suppressError = true)
            ?.apply {
                hookMethod("playShiftSystemIconsAnimation")
                    .suppressError()
                    .intercept { _ -> null }

                hookMethod("lambda\$playShiftSystemIconsAnimation\$6")
                    .suppressError()
                    .intercept { _ -> null }
            }

        findClass("com.nothing.systemui.statusbar.phone.KeyguardStatusBarViewControllerEx", suppressError = true)
            ?.hookMethod("updatePersistentDot")
            ?.suppressError()
            ?.intercept { _ -> null }

        var lastDotCalcMs = 0L
        var lastDotCalcKey = ""
        var lastDotCalcResult: Pair<Int, Int>? = null

        fun computeColonDotPosition(ctrl: Any?): Pair<Int, Int>? {
            if (isDualStatusbarEnabled() || getClockPosition() != 2) return null
            val clock = (if (clockIdCached != 0) getPhoneStatusBarView()?.findViewById<TextView>(clockIdCached) else null)
                ?: getClockView()
                ?: return null
            if (!clock.isAttachedToWindow || clock.width <= 0 || clock.height <= 0 || clock.visibility != View.VISIBLE) {
                return null
            }
            val text = clock.text?.toString().orEmpty()
            val tr = ctrl?.getFieldSilently("tr") as? View
            val now = SystemClock.uptimeMillis()
            val key = "$text|${clock.left}|${clock.top}|${tr?.width ?: 0}x${tr?.height ?: 0}"
            if (now - lastDotCalcMs < 64L && key == lastDotCalcKey && lastDotCalcResult != null) {
                return lastDotCalcResult
            }

            clock.getLocationOnScreen(tmpLoc)
            val displayW = clock.resources.displayMetrics.widthPixels
            val mDotSize = (ctrl?.getFieldSilently("mDotSize") as? Int)?.takeIf { it > 0 } ?: dotFallbackTopPx
            if (tr != null && (tr.measuredWidth == 0 || tr.measuredHeight == 0)) {
                tr.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
            }
            val trW = tr?.width?.takeIf { it > 0 } ?: tr?.measuredWidth?.takeIf { it > 0 } ?: mDotSize
            val trH = tr?.height?.takeIf { it > 0 } ?: tr?.measuredHeight?.takeIf { it > 0 } ?: mDotSize

            val dotView = if (privacyDotIdCached != 0) tr?.findViewById<View>(privacyDotIdCached) else null
            val dotCenterFromTrRight = if (dotView != null && dotView.width > 0 && tr != null && tr.width > 0) {
                trW - (dotView.left + dotView.width / 2f)
            } else {
                trW / 2f
            }
            val dotCenterFromTrTop = if (dotView != null && dotView.height > 0 && tr != null && tr.height > 0) {
                dotView.top + dotView.height / 2f
            } else {
                trH / 2f
            }

            val paint = clock.paint
            val colonIdx = text.indexOf(':')
            val anchorInText = if (colonIdx >= 0) {
                clock.layout?.let { layout ->
                    (layout.getPrimaryHorizontal(colonIdx) + layout.getPrimaryHorizontal(colonIdx + 1)) / 2f
                } ?: (paint.measureText(text, 0, colonIdx) + paint.measureText(text, colonIdx, colonIdx + 1) / 2f)
            } else {
                (clock.width - clock.compoundPaddingLeft - clock.compoundPaddingRight) / 2f
            }
            val anchorCenterX = tmpLoc[0] + clock.compoundPaddingLeft + anchorInText
            val lpX = Math.round(displayW - anchorCenterX - dotCenterFromTrRight).coerceAtLeast(0)

            paint.getTextBounds("8", 0, 1, tmpRect)
            val digitsTopY = (tmpLoc[1] + clock.baseline + tmpRect.top).toFloat()
            val lpY = Math.round(digitsTopY / 2f + dotExtraDownPx - dotCenterFromTrTop).coerceAtLeast(0)

            val result = lpX to lpY
            lastDotCalcMs = now
            lastDotCalcKey = key
            lastDotCalcResult = result
            return result
        }

        // Move ALL top-right privacy dots (green/blue/etc.) at once: horizontally over ':', vertically midpoint between screen top and clock digits (+2dp)
        findClass("com.nothing.systemui.statusbar.events.NTPrivacyDotViewController", suppressError = true)
            ?.apply {
                hookMethod("getPrivacyPaddingTop")
                    .suppressError()
                    .intercept { chain ->
                        val rotation = (chain.args.getOrNull(0) as? Int) ?: 0
                        if (rotation == 0 && !isDualStatusbarEnabled() && getClockPosition() == 2) {
                            computeColonDotPosition(chain.thisObject)?.second ?: dotFallbackTopPx
                        } else {
                            chain.proceed()
                        }
                    }

                hookMethod("getPrivacyPaddingStart")
                    .suppressError()
                    .intercept { chain ->
                        val rotation = (chain.args.getOrNull(0) as? Int) ?: 0
                        val isRtl = (chain.args.getOrNull(1) as? Boolean) ?: false
                        if (rotation == 0 && !isRtl && !isDualStatusbarEnabled() && getClockPosition() == 2) {
                            computeColonDotPosition(chain.thisObject)?.first
                                ?: (((chain.proceed() as? Int) ?: 0) + dotFallbackExtraStartPx)
                        } else {
                            chain.proceed()
                        }
                    }

                hookMethod("updatePosition")
                    .suppressError()
                    .runAfter { chain, _ ->
                        runCatching {
                            val ctrl = chain.thisObject ?: return@runCatching
                            val tr = ctrl.getFieldSilently("tr") as? View ?: return@runCatching
                            if (tr.getExtraFieldSilently("iconifyDotLayoutListener") != true) {
                                tr.setExtraField("iconifyDotLayoutListener", true)
                                tr.addOnLayoutChangeListener { v, l, t, r, b, oldL, oldT, oldR, oldB ->
                                    if ((r - l) != (oldR - oldL) || (b - t) != (oldB - oldT)) {
                                        if ((r - l) > 0 && (b - t) > 0) {
                                            val pos = computeColonDotPosition(ctrl) ?: return@addOnLayoutChangeListener
                                            val wlp = v.layoutParams as? WindowManager.LayoutParams
                                                ?: return@addOnLayoutChangeListener
                                            if (wlp.x != pos.first || wlp.y != pos.second) {
                                                wlp.x = pos.first
                                                wlp.y = pos.second
                                                val wm = ctrl.getFieldSilently("mWindowManager") as? WindowManager
                                                v.post { runCatching { wm?.updateViewLayout(v, wlp) } }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
            }

        listOf("privacy_dot", "privacy_dot_top_right").forEach { layoutName ->
            hookLayout()
                .packageName(SYSTEMUI_PACKAGE)
                .resource("layout", layoutName)
                .suppressError()
                .run { liparam ->
                    runCatching {
                        val root = liparam.view as? ViewGroup ?: return@runCatching
                        root.setPaddingRelative(root.paddingStart, 0, root.paddingEnd, 0)
                        val dotId = liparam.res.getIdentifier("privacy_dot", "id", SYSTEMUI_PACKAGE)
                        val dotView = if (dotId != 0) root.findViewById<View>(dotId) else root.getChildAt(0)
                        dotView?.apply {
                            (layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
                                topMargin = 0
                                layoutParams = this
                            }
                        }
                    }
                }
        }
    }
}
