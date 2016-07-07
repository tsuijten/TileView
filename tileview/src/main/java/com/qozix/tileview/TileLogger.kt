package com.qozix.tileview

import android.util.Log

interface TileLogger {
    val loggingEnabled: Boolean
        get() = true

    val loggerTag: String
        get() {
            val tag = javaClass.simpleName
            return if (tag.length <= 23) {
                tag
            } else {
                tag.substring(0, 23)
            }
        }
}

inline fun TileLogger.info(logMessage: () -> String) = log(Log.INFO, logMessage)
inline fun TileLogger.debug(logMessage: () -> String) = log(Log.DEBUG, logMessage)
inline fun TileLogger.log(level: Int, logMessage: () -> String) {
    if (loggingEnabled && Log.isLoggable(loggerTag, level)) {
        Log.println(level, loggerTag, Thread.currentThread().toString() + " - " + logMessage())
    }
}