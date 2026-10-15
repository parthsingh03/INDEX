package com.parth.index.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.parth.index.data.model.Roles
import com.parth.index.databinding.ItemMemberBinding
import com.parth.index.firebase.ClassGate

/** One row in the Members screen. The same adapter serves the pending
 *  list (Approve / Reject) and the member list (role flips + removal) —
 *  which buttons exist is decided per row from the VIEWER's power and the
 *  row's role, mirroring the product rules:
 *    - approve / reject ........ owner + admins
 *    - make admin / make member  owner only
 *    - remove .................. owner only
 *    - the owner row, and your own row, never carry actions. */
class MemberAdapter(
    private val viewerIsOwner: Boolean,
    private val viewerCanManage: Boolean,
    private val viewerUid: String,
    private val pendingList: Boolean,
    private val onAction: (ClassGate.Member, Action) -> Unit
) : RecyclerView.Adapter<MemberAdapter.Holder>() {

    enum class Action { APPROVE, REJECT, MAKE_ADMIN, MAKE_MEMBER, REMOVE }

    private var items: List<ClassGate.Member> = emptyList()

    fun submit(list: List<ClassGate.Member>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemMemberBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val member = items[position]
        val b = holder.binding
        b.tvMemberName.text = member.name
        b.tvMemberMeta.text = if (pendingList) {
            "Wants to join · ${member.email}"
        } else {
            "${roleLabel(member.role)} · ${member.email}"
        }

        var primary: Pair<String, Action>? = null
        var secondary: Pair<String, Action>? = null
        if (pendingList) {
            if (viewerCanManage) {
                primary = "Approve" to Action.APPROVE
                secondary = "Reject" to Action.REJECT
            }
        } else if (
            viewerIsOwner &&
            member.role != Roles.OWNER &&
            member.uid != viewerUid
        ) {
            primary = (if (member.role == Roles.ADMIN) "Make member" else "Make admin") to
                (if (member.role == Roles.ADMIN) Action.MAKE_MEMBER else Action.MAKE_ADMIN)
            secondary = "Remove" to Action.REMOVE
        }

        if (primary == null) {
            b.llMemberActions.visibility = View.GONE
        } else {
            b.llMemberActions.visibility = View.VISIBLE
            b.btnMemberPrimary.text = primary.first
            b.btnMemberPrimary.setOnClickListener { onAction(member, primary.second) }
            if (secondary != null) {
                b.btnMemberSecondary.visibility = View.VISIBLE
                b.btnMemberSecondary.text = secondary.first
                b.btnMemberSecondary.setOnClickListener { onAction(member, secondary.second) }
            } else {
                b.btnMemberSecondary.visibility = View.GONE
            }
        }
    }

    private fun roleLabel(role: String): String = when (role) {
        Roles.OWNER -> "Owner"
        Roles.ADMIN -> "Admin"
        else -> "Member"
    }

    class Holder(val binding: ItemMemberBinding) : RecyclerView.ViewHolder(binding.root)
}
