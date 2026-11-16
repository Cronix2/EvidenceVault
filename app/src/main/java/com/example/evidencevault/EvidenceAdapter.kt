package com.example.evidencevault

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.evidencevault.domain.Evidence
import com.example.evidencevault.domain.IntegrityStatus

class EvidenceAdapter(
    private val onPlayClick: (Evidence, EvidenceViewHolder) -> Unit,
    private val onPauseClick: (Evidence) -> Unit,
    private val onSeekBarChange: (Int) -> Unit,
    private val onRenameClick: (Evidence) -> Unit,
    private val onIntegrityClick: (Evidence) -> Unit
) : RecyclerView.Adapter<EvidenceAdapter.EvidenceViewHolder>() {

    private var evidences: List<Evidence> = emptyList()
    private var expandedPosition = -1
    private var isPlaying = false

    class EvidenceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val itemTitle: TextView = itemView.findViewById(R.id.itemTitle)
        val itemDate: TextView = itemView.findViewById(R.id.itemDate)
        val itemType: TextView = itemView.findViewById(R.id.itemType)
        val itemDuration: TextView = itemView.findViewById(R.id.itemDuration)
        val itemPlayIcon: ImageButton = itemView.findViewById(R.id.itemPlayIcon)
        val playerControls: LinearLayout = itemView.findViewById(R.id.playerControls)
        val itemSeekBar: SeekBar = itemView.findViewById(R.id.itemSeekBar)
        val itemCurrentTime: TextView = itemView.findViewById(R.id.itemCurrentTime)
        val itemTotalTime: TextView = itemView.findViewById(R.id.itemTotalTime)
        val imgIntegrity: ImageView = itemView.findViewById(R.id.imgIntegrity)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EvidenceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_evidence, parent, false)
        return EvidenceViewHolder(view)
    }

    override fun onBindViewHolder(holder: EvidenceViewHolder, position: Int) {
        val evidence = evidences[position]
        val isExpanded = position == expandedPosition

        holder.itemTitle.text = evidence.title
        holder.itemDate.text = evidence.dateText
        holder.itemType.text = if (evidence.file.name.endsWith(".mp4.enc")) "VIDÉO" else "AUDIO"
        holder.itemDuration.text = evidence.durationText
        holder.itemTotalTime.text = evidence.durationText

        holder.playerControls.visibility = if (isExpanded) View.VISIBLE else View.GONE
        holder.itemPlayIcon.setImageResource(if (isExpanded && isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow)

        holder.imgIntegrity.contentDescription = when (evidence.integrity) {
            IntegrityStatus.OK -> "Intégrité vérifiée"
            IntegrityStatus.MODIFIED -> "Intégrité compromise"
            IntegrityStatus.UNVERIFIED -> "Intégrité non vérifiée"
        }
        holder.imgIntegrity.setImageResource(
            when (evidence.integrity) {
                IntegrityStatus.OK -> R.drawable.ic_integrity_ok
                IntegrityStatus.MODIFIED -> R.drawable.ic_integrity_warn
                IntegrityStatus.UNVERIFIED -> R.drawable.ic_integrity_unknown
            }
        )

        holder.itemView.setOnClickListener {
            val oldPosition = expandedPosition
            val clickedPosition = holder.bindingAdapterPosition
            if (clickedPosition == RecyclerView.NO_POSITION) return@setOnClickListener
            expandedPosition = if (clickedPosition == oldPosition) -1 else clickedPosition
            if (oldPosition in evidences.indices) notifyItemChanged(oldPosition)
            if (expandedPosition in evidences.indices) notifyItemChanged(expandedPosition)
            if (expandedPosition != -1) onPlayClick(evidence, holder)
            else onPauseClick(evidence)
        }

        holder.itemPlayIcon.setOnClickListener {
             if (isExpanded && isPlaying) {
                onPauseClick(evidence)
            } else {
                onPlayClick(evidence, holder)
            }
        }

        holder.itemView.setOnLongClickListener {
            onRenameClick(evidence)
            true
        }

        holder.imgIntegrity.setOnClickListener { 
            onIntegrityClick(evidence)
        }

        holder.itemSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    onSeekBarChange(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    override fun getItemCount(): Int = evidences.size

    fun updateEvidences(newEvidences: List<Evidence>) {
        evidences = newEvidences
        expandedPosition = -1
        isPlaying = false
        notifyDataSetChanged()
    }

    fun updatePlaybackState(isPlaying: Boolean) {
        this.isPlaying = isPlaying
        if (expandedPosition in evidences.indices) notifyItemChanged(expandedPosition)
    }
}
