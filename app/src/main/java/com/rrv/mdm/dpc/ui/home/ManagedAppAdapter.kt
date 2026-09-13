package com.rrv.mdm.dpc.ui.home

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.rrv.mdm.dpc.R
import com.rrv.mdm.dpc.databinding.ItemManagedAppBinding
import com.rrv.mdm.dpc.domain.model.ApplicationInfo
import com.rrv.mdm.dpc.domain.model.InstallStatus

class ManagedAppAdapter(
    private val onAppClick: (ApplicationInfo) -> Unit,
    private val onAppLongClick: (ApplicationInfo) -> Unit
) : ListAdapter<ApplicationInfo, ManagedAppAdapter.AppViewHolder>(AppDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val binding = ItemManagedAppBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return AppViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class AppViewHolder(private val binding: ItemManagedAppBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(app: ApplicationInfo) {
            binding.tvAppName.text = app.appName

            if (app.icon != null) {
                binding.ivAppIcon.setImageDrawable(app.icon)
            } else {
                // Fallback styled monogram icon if package is not installed locally
                binding.ivAppIcon.setImageResource(R.drawable.ic_mdm_launcher)
            }

            if (app.installStatus == InstallStatus.DOWNLOADING || app.installStatus == InstallStatus.INSTALLING) {
                binding.pbAppInstall.visibility = View.VISIBLE
                binding.layoutAppIconCard.alpha = 0.6f
            } else {
                binding.pbAppInstall.visibility = View.GONE
                binding.layoutAppIconCard.alpha = 1.0f
            }

            binding.root.setOnClickListener { onAppClick(app) }
            binding.root.setOnLongClickListener {
                onAppLongClick(app)
                true
            }
        }
    }

    class AppDiffCallback : DiffUtil.ItemCallback<ApplicationInfo>() {
        override fun areItemsTheSame(oldItem: ApplicationInfo, newItem: ApplicationInfo): Boolean {
            return oldItem.packageName == newItem.packageName
        }

        override fun areContentsTheSame(oldItem: ApplicationInfo, newItem: ApplicationInfo): Boolean {
            return oldItem == newItem
        }
    }
}
