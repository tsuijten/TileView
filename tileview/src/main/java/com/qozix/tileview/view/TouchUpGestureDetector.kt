package com.qozix.tileview.view

import android.view.MotionEvent

/**
 * @author Mike Dunn, 10/6/15.
 */
class TouchUpGestureDetector(private val mOnTouchUpListener: TouchUpGestureDetector.OnTouchUpListener?) {

    fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            if (mOnTouchUpListener != null) {
                return mOnTouchUpListener.onTouchUp(event)
            }
        }
        return true
    }

    interface OnTouchUpListener {
        fun onTouchUp(event: MotionEvent): Boolean
    }
}

