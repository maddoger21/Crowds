package com.example.crowds

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class CommentAdapter(private val commentList: List<Comment>) :
    RecyclerView.Adapter<CommentAdapter.CommentViewHolder>() {

    class CommentViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvUser: TextView = itemView.findViewById(R.id.tv_comment_user)
        val tvText: TextView = itemView.findViewById(R.id.tv_comment_text)
        val tvTime: TextView = itemView.findViewById(R.id.tv_comment_time)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CommentViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_comment, parent, false)
        return CommentViewHolder(v)
    }

    override fun onBindViewHolder(holder: CommentViewHolder, position: Int) {
        val comment = commentList[position]
        holder.tvUser.text = comment.userName
        holder.tvText.text = comment.text
        comment.timestamp?.toDate()?.let { date ->
            val formatted = Utils.formatDate(date.time)
            holder.tvTime.text = formatted
        } ?: run {
            holder.tvTime.text = ""
        }
    }

    override fun getItemCount(): Int = commentList.size
}