package com.ender.ninnimanager

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.ender.ninni.protocol.Queue
import com.ender.ninni.protocol.QueueItem

/** Rows of the play queue dialog; the track playing now is marked. */
class QueueAdapter(private val activity: Activity) : BaseAdapter() {

    private var items: List<QueueItem> = emptyList()
    private var active: Long? = null

    fun show(queue: Queue?, active: Long?) {
        val newItems = queue?.items.orEmpty()
        if (newItems == items && active == this.active) return
        items = newItems
        this.active = active
        notifyDataSetChanged()
    }

    /** Position of the track playing now, or -1. */
    fun activePosition(): Int = items.indexOfFirst { it.id == active }

    override fun getCount() = items.size

    override fun getItem(position: Int): QueueItem = items[position]

    override fun getItemId(position: Int) = items[position].id

    override fun hasStableIds() = true

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: activity.layoutInflater.inflate(R.layout.item_queue, parent, false)
        val item = items[position]
        val playing = item.id == active
        view.findViewById<View>(R.id.itemPlaying).visibility = if (playing) View.VISIBLE else View.INVISIBLE
        view.findViewById<TextView>(R.id.itemTitle).apply {
            text = item.title.ifEmpty { "—" }
            setTextColor(activity.getColor(if (playing) R.color.moon else R.color.text))
        }
        view.findViewById<TextView>(R.id.itemArtist).apply {
            text = item.artist.orEmpty()
            visibility = if (item.artist == null) View.GONE else View.VISIBLE
        }
        return view
    }
}
