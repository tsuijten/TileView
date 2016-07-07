package tileview.demo

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.os.Bundle
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.request.animation.GlideAnimation
import com.bumptech.glide.request.target.SimpleTarget
import com.google.gson.Gson
import com.qozix.tileview.BitmapProvider
import com.qozix.tileview.TileSize
import com.qozix.tileview.widgets.ZoomPanLayout
import java.io.InputStreamReader
import java.util.concurrent.Executors

data class TilesJson(val levels: List<TileLevelJson>)
data class TileLevelJson(val name: String, val width: Int, val height: Int, val tiles: List<TileJson>)
data class TileJson(val x: Int, val y: Int, val url: String)

data class DetailLevel(val scale: Float, val tiles: Map<Point, String>)

class LargeImageTileViewActivity : TileViewActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        val inputStream = assets.open("tiles/tiles.json")
        val tilesJson = Gson().fromJson(InputStreamReader(inputStream), TilesJson::class.java)

        val max = tilesJson.levels.maxBy { it.width } ?: return
        val maxWidth = max.width
        val maxHeight = max.height

        val tiles = tilesJson.levels.associateBy({ (it.width.toFloat()) / maxWidth }, { it.tiles.associateBy({ Point(it.x, it.y) }, { it.url }) })
        val preview = tilesJson.levels.filter { it.tiles.size == 1 }.maxBy { it.width }

        // multiple references
        val tileView = tileView

        tileView.tileSize = TileSize(512, 512)
        // size of original image at 100% mScale
        tileView.setSize(maxWidth, maxHeight)

        if (preview != null) {
            Glide.with(this).load(preview.tiles.first().url).asBitmap().format(DecodeFormat.PREFER_ARGB_8888).into(object : SimpleTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap?, glideAnimation: GlideAnimation<in Bitmap>?) {
                    tileView.preview = resource
                }
            })
        }
//        tileView.preview = bm

        //		// detail levels
        tileView.setBackgroundColor(Color.BLACK)
//        tileView.debug = true
        tileView.recycleBitmaps = false
        tileView.density = 1.25f
        tileView.bitmapProvider = object : BitmapProvider {
            override fun loadBitmap(scale: Float, row: Int, column: Int): Bitmap? {
                val url = tiles[scale]?.get(Point(column, row)) ?: return null
                return Glide.with(this@LargeImageTileViewActivity).load(url).asBitmap().format(DecodeFormat.PREFER_ARGB_8888).into(512, 512).get()
            }
        }

        tileView.executor = Executors.newScheduledThreadPool(2 * Runtime.getRuntime().availableProcessors())
        tiles.keys.forEach { tileView.addDetailLevel(it) }
        tileView.setScaleLimits(0.01f, 3f)
        tileView.post {
            tileView.setMinimumScaleMode(ZoomPanLayout.MinimumScaleMode.FILL)
            tileView.scale = 0f
            tileView.defineBounds( 0.0, 0.0, 1.0, 1.0 );
            tileView.scrollToAndCenter(0.5, 0.5)
        }
        //

//        tileView.addDetailLevel(1.000f);
//        tileView.addDetailLevel(0.500f);
//        tileView.addDetailLevel(0.250f);
//        tileView.addDetailLevel(0.125f);

        //
        //		// set mScale to 0, but keep scaleToFit true, so it'll be as small as possible but still match the container
        //		tileView.setScale( 0.3f );
        //
        //		// let's use 0-1 positioning...
        //		tileView.defineBounds( 0, 0, 1, 1 );
        //
        //		// frame to center
        ////		frameTo( 0.5, 0.5 );
        //
        //		// render while panning
        //		tileView.setShouldRenderWhilePanning( true );
        //
        //		tileView.setShouldUpdateDetailLevelWhileZooming(true);
        //
        //		tileView.setShouldScaleToFit(false);
        //
        //		// disallow going back to minimum scale while double-taping at maximum scale (for demo purpose)
        //		tileView.setShouldLoopScale( false );
    }
}
