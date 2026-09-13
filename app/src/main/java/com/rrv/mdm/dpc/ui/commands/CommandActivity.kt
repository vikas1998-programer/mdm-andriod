package com.rrv.mdm.dpc.ui.commands

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rrv.mdm.dpc.RrvMdmApplication
import com.rrv.mdm.dpc.databinding.ActivityCommandHistoryBinding
import com.rrv.mdm.dpc.databinding.ItemCommandHistoryBinding
import com.rrv.mdm.dpc.domain.model.CommandStatus
import com.rrv.mdm.dpc.domain.model.MdmCommand
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CommandActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCommandHistoryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCommandHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.rvCommands.layoutManager = LinearLayoutManager(this)

        val app = application as RrvMdmApplication

        lifecycleScope.launch {
            app.getRecentCommandsUseCase.getRecentCommands(50).collect { list ->
                if (list.isEmpty()) {
                    binding.tvEmptyCommands.visibility = View.VISIBLE
                    binding.rvCommands.visibility = View.GONE
                } else {
                    binding.tvEmptyCommands.visibility = View.GONE
                    binding.rvCommands.visibility = View.VISIBLE
                    binding.rvCommands.adapter = CommandAdapter(list)
                }
            }
        }
    }

    private class CommandAdapter(private val commands: List<MdmCommand>) :
        RecyclerView.Adapter<CommandAdapter.CmdViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CmdViewHolder {
            val binding = ItemCommandHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return CmdViewHolder(binding)
        }

        override fun onBindViewHolder(holder: CmdViewHolder, position: Int) {
            holder.bind(commands[position])
        }

        override fun getItemCount(): Int = commands.size

        class CmdViewHolder(private val binding: ItemCommandHistoryBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(cmd: MdmCommand) {
                binding.tvCommandType.text = cmd.commandType
                binding.tvCommandResult.text = cmd.resultMessage ?: "Command processed."
                binding.tvCommandStatusBadge.text = cmd.status.name

                when (cmd.status) {
                    CommandStatus.SUCCESS -> {
                        binding.tvCommandStatusBadge.setTextColor(Color.parseColor("#10B981"))
                    }
                    CommandStatus.EXECUTING -> {
                        binding.tvCommandStatusBadge.setTextColor(Color.parseColor("#38BDF8"))
                    }
                    CommandStatus.FAILED -> {
                        binding.tvCommandStatusBadge.setTextColor(Color.parseColor("#EF4444"))
                    }
                    else -> {
                        binding.tvCommandStatusBadge.setTextColor(Color.parseColor("#94A3B8"))
                    }
                }

                val timeFmt = SimpleDateFormat("MMM d, h:mm:ss a", Locale.getDefault())
                binding.tvCommandTimestamp.text = timeFmt.format(Date(cmd.timestamp))
            }
        }
    }
}
