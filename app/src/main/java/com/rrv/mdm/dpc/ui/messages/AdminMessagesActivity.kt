package com.rrv.mdm.dpc.ui.messages

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rrv.mdm.dpc.RrvMdmApplication
import com.rrv.mdm.dpc.databinding.ActivityAdminMessagesBinding
import com.rrv.mdm.dpc.databinding.ItemAdminMessageBinding
import com.rrv.mdm.dpc.domain.model.AdminMessage
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AdminMessagesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminMessagesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminMessagesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.rvMessages.layoutManager = LinearLayoutManager(this)

        val app = application as RrvMdmApplication

        lifecycleScope.launch {
            app.getAdminMessagesUseCase.getMessages().collect { list ->
                if (list.isEmpty()) {
                    binding.tvEmptyMessages.visibility = View.VISIBLE
                    binding.rvMessages.visibility = View.GONE
                } else {
                    binding.tvEmptyMessages.visibility = View.GONE
                    binding.rvMessages.visibility = View.VISIBLE
                    binding.rvMessages.adapter = MessageAdapter(list)
                }
            }
        }
    }

    private class MessageAdapter(private val messages: List<AdminMessage>) :
        RecyclerView.Adapter<MessageAdapter.MsgViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MsgViewHolder {
            val binding = ItemAdminMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return MsgViewHolder(binding)
        }

        override fun onBindViewHolder(holder: MsgViewHolder, position: Int) {
            holder.bind(messages[position])
        }

        override fun getItemCount(): Int = messages.size

        class MsgViewHolder(private val binding: ItemAdminMessageBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(msg: AdminMessage) {
                binding.tvMsgTitle.text = msg.title
                binding.tvMsgBody.text = msg.message
                binding.tvMsgSender.text = "From: ${msg.sender}"
                binding.tvMsgPriorityBadge.text = msg.priority.name

                val timeFmt = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                binding.tvMsgTimestamp.text = timeFmt.format(Date(msg.timestamp))
            }
        }
    }
}
