package com.example.crowds

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.crowds.databinding.ItemCommentBinding
import com.google.firebase.Timestamp

class CommentAdapter(
    private val comments: MutableList<Comment>,
    private val isAdmin: Boolean,
    private val onDelete: (Comment) -> Unit
) : RecyclerView.Adapter<CommentAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvUser = view.findViewById<TextView>(R.id.tv_comment_user)
        val tvText = view.findViewById<TextView>(R.id.tv_comment_text)
        val tvTime = view.findViewById<TextView>(R.id.tv_comment_time)
        val btnDel = view.findViewById<ImageButton>(R.id.btn_delete_comment)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_comment, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, pos: Int) {
        val c = comments[pos]
        holder.tvUser.text = c.userName
        holder.tvText.text = c.text

        // форматируем Timestamp в дату/время
        val date = c.timestamp?.toDate()
        holder.tvTime.text = if (date != null) {
            android.text.format.DateFormat.format("dd.MM.yyyy HH:mm", date)
        } else {
            ""
        }

        if (isAdmin) {
            holder.btnDel.visibility = View.VISIBLE
            holder.btnDel.setOnClickListener {
                onDelete(c)
            }
        } else {
            holder.btnDel.visibility = View.GONE
        }
    }

    override fun getItemCount(): Int = comments.size
}
