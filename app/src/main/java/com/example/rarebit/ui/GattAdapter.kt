package com.example.rarebit.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.rarebit.R
import com.example.rarebit.ble.GattCharItem
import com.example.rarebit.ble.GattServiceItem
import java.util.UUID

sealed class GattListItem {
    data class ServiceHeader(val service: GattServiceItem) : GattListItem()
    data class CharRow(val serviceUuid: UUID, val char: GattCharItem) : GattListItem()
}

class GattAdapter(
    private val onReadClick: (serviceUuid: UUID, charUuid: UUID) -> Unit,
    private val onWriteClick: (serviceUuid: UUID, charUuid: UUID, hex: String) -> Unit,
    private val onNotifyToggle: (serviceUuid: UUID, charUuid: UUID, enable: Boolean) -> Unit
) : ListAdapter<GattListItem, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        private const val TYPE_SERVICE = 0
        private const val TYPE_CHAR = 1

        private val DIFF = object : DiffUtil.ItemCallback<GattListItem>() {
            override fun areItemsTheSame(old: GattListItem, new: GattListItem): Boolean {
                return when {
                    old is GattListItem.ServiceHeader && new is GattListItem.ServiceHeader ->
                        old.service.uuid == new.service.uuid
                    old is GattListItem.CharRow && new is GattListItem.CharRow ->
                        old.char.uuid == new.char.uuid
                    else -> false
                }
            }

            override fun areContentsTheSame(old: GattListItem, new: GattListItem) = old == new
        }
    }

    // Tracks which service UUIDs are collapsed
    private val collapsedServices = mutableSetOf<UUID>()

    fun submitServices(services: List<GattServiceItem>) {
        val flat = mutableListOf<GattListItem>()
        for (service in services) {
            flat.add(GattListItem.ServiceHeader(service))
            if (service.uuid !in collapsedServices) {
                service.characteristics.forEach { char ->
                    flat.add(GattListItem.CharRow(service.uuid, char))
                }
            }
        }
        submitList(flat)
    }

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is GattListItem.ServiceHeader -> TYPE_SERVICE
        is GattListItem.CharRow      -> TYPE_CHAR
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SERVICE -> ServiceViewHolder(
                inflater.inflate(R.layout.item_gatt_service, parent, false)
            )
            else -> CharViewHolder(
                inflater.inflate(R.layout.item_gatt_characteristic, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is GattListItem.ServiceHeader -> (holder as ServiceViewHolder).bind(item)
            is GattListItem.CharRow      -> (holder as CharViewHolder).bind(item)
        }
    }

    inner class ServiceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val serviceName: TextView = itemView.findViewById(R.id.serviceName)
        private val expandIcon: ImageView = itemView.findViewById(R.id.expandIcon)

        fun bind(item: GattListItem.ServiceHeader) {
            serviceName.text = item.service.name
            val collapsed = item.service.uuid in collapsedServices
            expandIcon.rotation = if (collapsed) -90f else 0f

            itemView.setOnClickListener {
                val uuid = item.service.uuid
                if (uuid in collapsedServices) collapsedServices.remove(uuid)
                else collapsedServices.add(uuid)
                // Re-flatten from current list — find services by walking current items
                val services = currentList
                    .filterIsInstance<GattListItem.ServiceHeader>()
                    .map { it.service }
                submitServices(services)
            }
        }
    }

    inner class CharViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val charName: TextView = itemView.findViewById(R.id.charName)
        private val chipRead: TextView = itemView.findViewById(R.id.chipRead)
        private val chipWrite: TextView = itemView.findViewById(R.id.chipWrite)
        private val chipNotify: TextView = itemView.findViewById(R.id.chipNotify)
        private val charValue: TextView = itemView.findViewById(R.id.charValue)
        private val writeRow: LinearLayout = itemView.findViewById(R.id.writeRow)
        private val writeInput: android.widget.EditText = itemView.findViewById(R.id.writeInput)
        private val writeButton: android.widget.Button = itemView.findViewById(R.id.writeButton)

        private var notifyEnabled = false

        fun bind(item: GattListItem.CharRow) {
            val char = item.char
            charName.text = char.name
            charValue.text = char.valueDisplay

            chipRead.visibility = if (char.canRead) View.VISIBLE else View.GONE
            chipWrite.visibility = if (char.canWrite) View.VISIBLE else View.GONE
            chipNotify.visibility = if (char.canNotify) View.VISIBLE else View.GONE
            writeRow.visibility = if (char.canWrite) View.VISIBLE else View.GONE

            chipRead.setOnClickListener {
                onReadClick(item.serviceUuid, char.uuid)
            }

            chipNotify.setOnClickListener {
                notifyEnabled = !notifyEnabled
                onNotifyToggle(item.serviceUuid, char.uuid, notifyEnabled)
                chipNotify.alpha = if (notifyEnabled) 1f else 0.5f
            }

            writeButton.setOnClickListener {
                val hex = writeInput.text.toString().trim()
                if (hex.isNotEmpty()) onWriteClick(item.serviceUuid, char.uuid, hex)
            }
        }
    }
}
