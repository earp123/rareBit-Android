package com.example.rarebit.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.rarebit.R
import com.example.rarebit.ble.BleDevice
import com.example.rarebit.ble.DeviceType
import com.example.rarebit.ble.GlowState
import com.google.android.material.card.MaterialCardView

class DeviceCardAdapter(
    private val onCardClick: (BleDevice) -> Unit
) : ListAdapter<BleDevice, DeviceCardAdapter.ViewHolder>(DIFF) {

    companion object {
        private const val PAYLOAD_RSSI = "RSSI"

        private val DIFF = object : DiffUtil.ItemCallback<BleDevice>() {
            override fun areItemsTheSame(old: BleDevice, new: BleDevice) =
                old.address == new.address

            override fun areContentsTheSame(old: BleDevice, new: BleDevice) = old == new

            override fun getChangePayload(old: BleDevice, new: BleDevice): Any? {
                return if (!new.isConnected && old.rssi != new.rssi) PAYLOAD_RSSI else null
            }
        }

        private fun glowColor(state: GlowState): Int = when (state) {
            GlowState.GREEN  -> Color.parseColor("#39FF14")
            GlowState.CYAN   -> Color.parseColor("#00CFFF")
            GlowState.BLUE   -> Color.parseColor("#007AFF")
            GlowState.YELLOW -> Color.parseColor("#FFC300")
            GlowState.RED    -> Color.parseColor("#FF3B30")
        }
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val glowCard: GlowCardView = itemView as GlowCardView
        val card: MaterialCardView = itemView.findViewById(R.id.card)
        val rssiBar: ProgressBar = itemView.findViewById(R.id.rssiBar)
        val deviceName: TextView = itemView.findViewById(R.id.deviceName)
        val badgeRow: LinearLayout = itemView.findViewById(R.id.badgeRow)
        val badgeUpdate: TextView = itemView.findViewById(R.id.badgeUpdate)
        val deviceTypeIcon: ImageView = itemView.findViewById(R.id.deviceTypeIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        bindFull(holder, getItem(position))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: List<Any>) {
        if (payloads.isEmpty()) {
            bindFull(holder, getItem(position))
            return
        }
        if (PAYLOAD_RSSI in payloads) {
            updateRssiBar(holder, getItem(position))
        }
    }

    private fun bindFull(holder: ViewHolder, device: BleDevice) {
        holder.itemView.setOnClickListener { onCardClick(device) }
        holder.deviceName.text = device.name

        if (device.isConnected) {
            bindConnected(holder, device)
        } else {
            bindPreConnect(holder, device)
        }
    }

    private fun bindPreConnect(holder: ViewHolder, device: BleDevice) {
        holder.card.setCardBackgroundColor(Color.WHITE)
        holder.card.strokeWidth = 0
        holder.glowCard.hideGlow()

        holder.deviceName.setTextColor(Color.BLACK)
        holder.rssiBar.visibility = View.VISIBLE
        holder.badgeRow.visibility = View.GONE
        holder.deviceTypeIcon.visibility = View.GONE

        updateRssiBar(holder, device)
    }

    private fun bindConnected(holder: ViewHolder, device: BleDevice) {
        val color = glowColor(device.glowState)

        val density = holder.card.context.resources.displayMetrics.density
        holder.card.setCardBackgroundColor(Color.parseColor("#1A1A1A"))
        holder.card.strokeColor = color
        holder.card.strokeWidth = (2 * density + 0.5f).toInt()
        holder.glowCard.setGlowColor(color)
        holder.glowCard.showGlow(animate = true)

        holder.deviceName.setTextColor(Color.WHITE)
        holder.rssiBar.visibility = View.GONE
        holder.badgeRow.visibility = View.VISIBLE
        holder.badgeUpdate.visibility = if (device.hasUpdate) View.VISIBLE else View.GONE

        holder.deviceTypeIcon.visibility = View.VISIBLE
        holder.deviceTypeIcon.setImageResource(
            when (device.deviceType) {
                DeviceType.FLAG     -> R.drawable.ic_device_flag
                DeviceType.RECEIVER -> R.drawable.ic_device_receiver
                DeviceType.RELAY    -> R.drawable.ic_relay
                DeviceType.UNKNOWN  -> R.drawable.ic_device_unknown
            }
        )
        val sizeDp = when (device.deviceType) {
            DeviceType.FLAG     -> 67
            DeviceType.RECEIVER -> 51
            DeviceType.RELAY    -> 60   // tall icon (device body + arcs)
            else                -> 51
        }
        val sizePx = (sizeDp * density + 0.5f).toInt()
        // Keep icon centers aligned at a fixed distance from the card's right edge
        val centerFromRightDp = 47
        val marginEndPx = ((centerFromRightDp - sizeDp / 2) * density + 0.5f).toInt()
        val lp = holder.deviceTypeIcon.layoutParams as android.view.ViewGroup.MarginLayoutParams
        lp.width = sizePx
        lp.height = sizePx
        lp.marginEnd = marginEndPx
        holder.deviceTypeIcon.layoutParams = lp
    }

    private fun updateRssiBar(holder: ViewHolder, device: BleDevice) {
        // -75 dBm = 0%, -15 dBm = 100%
        val progress = ((device.rssi + 75) / 60f * 100).toInt().coerceIn(0, 100)
        holder.rssiBar.setProgress(progress, true)
    }
}
