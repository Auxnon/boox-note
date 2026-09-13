package com.auxnon.booxnote

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class CanvasOverviewAdapter(
    private val onOpen: (CanvasMeta) -> Unit,
    private val onRename: (CanvasMeta) -> Unit,
    private val thumbnailFor: (CanvasMeta) -> Bitmap?,
) : RecyclerView.Adapter<CanvasOverviewAdapter.CanvasViewHolder>() {

    private val items = ArrayList<CanvasMeta>()

    fun submit(list: List<CanvasMeta>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CanvasViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_canvas_tile, parent, false)
        return CanvasViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: CanvasViewHolder, position: Int) {
        val item = items[position]
        holder.name.text = item.name
        val thumb = thumbnailFor(item)
        if (thumb != null) {
            holder.thumbnail.setImageBitmap(thumb)
        } else {
            holder.thumbnail.setImageDrawable(null)
        }
        holder.itemView.setOnClickListener { onOpen(item) }
        holder.renameButton.setOnClickListener { onRename(item) }
    }

    class CanvasViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.tileThumbnail)
        val name: TextView = view.findViewById(R.id.tileName)
        val renameButton: ImageButton = view.findViewById(R.id.tileRenameButton)
    }
}
