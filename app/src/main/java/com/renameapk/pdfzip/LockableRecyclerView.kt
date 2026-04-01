package com.renameapk.pdfzip

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.RecyclerView

class LockableRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : RecyclerView(context, attrs) {

    var userScrollEnabled: Boolean = true

    override fun onInterceptTouchEvent(e: android.view.MotionEvent): Boolean {
        if (!userScrollEnabled) {
            return false
        }
        return super.onInterceptTouchEvent(e)
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (!userScrollEnabled) {
            return false
        }
        return super.onTouchEvent(e)
    }
}
