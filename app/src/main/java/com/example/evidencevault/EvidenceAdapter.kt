package com.example.evidencevault

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.evidencevault.domain.Evidence

class EvidenceAdapter(
    private val onPlayClick: (Evidence, EvidenceViewHolder) -> Unit,
    private val onSeekBarChange: (Evidence, Int) -> Unit,
    private val onRenameClick: (Evidence) -> Unit
) : RecyclerView.Adapter<EvidenceAdapter.EvidenceViewHolder>() {

    private var evidences: List<Evidence> = emptyList()
    private var currentlyPlaying: Evidence? = null

    class EvidenceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val itemTitle: TextView = itemView.findViewById(R.id.itemTitle)
        val itemDate: TextView = itemView.findViewById(R.id.itemDate)
        val itemDuration: TextView = itemView.findViewById(R.id.itemDuration)
        val playerControls: LinearLayout = itemView.findViewById(R.id.playerControls)
        val itemSeekBar: SeekBar = itemView.findViewById(R.id.itemSeekBar)
        val itemCurrentTime: TextView = itemView.findViewById(R.id.itemCurrentTime)
        val itemPlayPause: ImageButton = itemView.findViewById(R.id.itemPlayPause)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EvidenceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_evidence, parent, false)
        return EvidenceViewHolder(view)
    }

    override fun onBindViewHolder(holder: EvidenceViewHolder, position: Int) {
        val evidence = evidences[position]

        holder.itemTitle.text = evidence.title
        holder.itemDate.text = evidence.dateText
        holder.itemDuration.text = evidence.durationText

        val isPlaying = evidence == currentlyPlaying
        holder.playerControls.visibility = if (isPlaying) View.VISIBLE else View.GONE
        holder.itemPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow)

        holder.itemView.setOnClickListener {
            currentlyPlaying = if (isPlaying) null else evidence
            notifyDataSetChanged()
            if (currentlyPlaying != null) {
                onPlayClick(evidence, holder)
            }
        }
        
        holder.itemView.setOnLongClickListener {
            onRenameClick(evidence)
            true
        }

        holder.itemSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    onSeekBarChange(evidence, progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    override fun getItemCount(): Int = evidences.size

    fun updateEvidences(newEvidences: List<Evidence>) {
        this.evidences = newEvidences
        notifyDataSetChanged()
    }
    
    fun updatePlaybackState(evidence: Evidence?, isPlaying: Boolean) {
        currentlyPlaying = if (isPlaying) evidence else null
        notifyDataSetChanged()
    }
}
