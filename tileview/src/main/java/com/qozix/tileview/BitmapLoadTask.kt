package com.qozix.tileview

import android.graphics.Bitmap
import android.os.AsyncTask
import android.util.Log
import java.lang.ref.WeakReference

/**
 * Created by thijs on 05-07-16.
 */

internal class BitmapLoadTask(tileView: TileView, tile: Tile, override var loggingEnabled: Boolean) : AsyncTask<Unit, Int, Bitmap?>(), TileLogger {
    private val tileView = WeakReference(tileView)
    private val tile = WeakReference(tile)

    override fun doInBackground(vararg params: Unit): Bitmap? {
        val tile = tile.get() ?: return null

        info { "$loggingEnabled" }
        return tileView.get()?.let {
            try {
                info { "Loading bitmap $tile" }
                it.bitmapProvider?.loadBitmap(tile.scale, tile.row, tile.column)
            } catch (e: InterruptedException) {
                null
            } catch (e: Exception) {
                Log.w(javaClass.simpleName, "Loading bitmap failed", e)
                null
            }
        }
    }

    override fun onPostExecute(result: Bitmap?) {
        val tile = tile.get() ?: return

        info { "Load complete $tile" }

        tile.task = null
        tile.bitmapRef = result?.let { StrongRef(it) }

        if (result != null) {
            tileView.get()?.let { it.invalidate(tile) }
        }
    }

    override fun onCancelled(result: Bitmap?) {
        val tile = tile.get() ?: return
        info { "Cancelled $tile" }

        tile.task = null
        tile.bitmapRef = null
    }
}