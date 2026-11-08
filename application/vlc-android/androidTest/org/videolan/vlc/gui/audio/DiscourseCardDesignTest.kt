package org.videolan.vlc.gui.audio

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.videolan.vlc.R
import org.videolan.vlc.discourse.Discourse
import java.io.File

@RunWith(AndroidJUnit4::class)
class DiscourseCardDesignTest {
    @Test fun sharedCardsRenderWithOptionalMetadataAtPhoneAndTabletWidths() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                val base = instrumentation.targetContext
                val config = Configuration(base.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
                    fontScale = 1f
                }
                val context = ContextThemeWrapper(base.createConfigurationContext(config), R.style.Theme_VLC_Black)
                val density = context.resources.displayMetrics.density
                val item = Discourse("preview", "A Bird on the Wing", null, false, "english", null, "", "",
                    totalTracks = 11, totalLikes = 2, location = "Pune, Maharashtra, India", date = "1974-06-10")
                val output = File(base.getExternalFilesDir(null), "card-previews").apply { mkdirs() }
                val reference = BitmapFactory.decodeFile("/data/local/tmp/discourse-card-reference.png")
                val artwork = reference?.let { Bitmap.createBitmap(it, 92, 172, 400, 400) }
                try {
                    for (layout in listOf(R.layout.discourse_catalogue_card, R.layout.discourse_search_card, R.layout.discourse_card)) {
                        for (widthDp in listOf(320, 360, 420, 600)) {
                            val root = LayoutInflater.from(context).inflate(layout, null)
                            root.findViewById<View>(R.id.discourse_letter_heading)?.isVisible = false
                            root.findViewById<TextView>(R.id.discourse_title).text = item.title
                            root.bindDiscourseCardMetadata(item, "11 tracks")
                            if (artwork != null) root.findViewById<ImageView>(R.id.discourse_image).setImageBitmap(artwork)
                            root.findViewById<TextView>(R.id.discourse_like).apply {
                                isVisible = true
                                text = "2"
                                setTextColor(context.getColor(R.color.discourse_card_secondary))
                                val icon = context.getDrawable(R.drawable.ic_discourse_heart)!!.mutate()
                                icon.setTint(currentTextColor)
                                val size = (22 * density).toInt()
                                icon.setBounds(0, 0, size, size)
                                setCompoundDrawablesRelative(icon, null, null, null)
                            }
                            val width = (widthDp * density).toInt()
                            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                            root.layout(0, 0, width, root.measuredHeight)
                            val bitmap = Bitmap.createBitmap(width, root.height, Bitmap.Config.ARGB_8888)
                            root.draw(Canvas(bitmap))
                            File(output, "${context.resources.getResourceEntryName(layout)}-$widthDp.png").outputStream().use {
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                            bitmap.recycle()
                            assertEquals("Pune", root.findViewById<TextView>(R.id.discourse_location).text.toString())
                            assertEquals("Jun 10, 1974", root.findViewById<TextView>(R.id.discourse_date).text.toString())
                            val date = root.findViewById<TextView>(R.id.discourse_date)
                            assertTrue("Date needs enough room at $widthDp dp", date.width > date.compoundPaddingLeft + date.compoundPaddingRight)
                            assertEquals("Date should remain readable at $widthDp dp", 0, date.layout.getEllipsisCount(0))
                            for (id in listOf(R.id.discourse_date, R.id.discourse_counts, R.id.discourse_like, R.id.discourse_download_icon)) {
                                val view = root.findViewById<View>(id)
                                val bounds = android.graphics.Rect(0, 0, view.width, view.height)
                                (root as android.view.ViewGroup).offsetDescendantRectToMyCoords(view, bounds)
                                assertTrue("Footer must not be clipped at $widthDp dp: $bounds", bounds.bottom <= root.height)
                            }
                            if (widthDp >= 360) {
                                assertEquals("Footer should share one row at $widthDp dp",
                                    root.findViewById<View>(R.id.discourse_card_details).top,
                                    root.findViewById<View>(R.id.discourse_card_actions).top)
                            }
                            root.bindDiscourseCardMetadata(item.copy(date = null, location = null), "11 tracks")
                            assertFalse(root.findViewById<View>(R.id.discourse_location).isVisible)
                            assertFalse(root.findViewById<View>(R.id.discourse_date).isVisible)
                            assertFalse(root.findViewById<View>(R.id.discourse_date_separator).isVisible)
                        }
                    }
                } finally { artwork?.recycle(); reference?.recycle() }
            } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }
}
