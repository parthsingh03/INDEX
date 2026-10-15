package com.parth.index.ui.members

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.parth.index.data.model.Roles
import com.parth.index.databinding.ActivityMembersBinding
import com.parth.index.firebase.ClassGate
import com.parth.index.firebase.CloudConfig
import com.parth.index.ui.adapters.MemberAdapter
import kotlinx.coroutines.launch

/** The class's front-desk: join approvals and the member list.
 *
 *  Owners and admins both work here, but not with the same powers —
 *  approvals are shared, while role changes and removals are the owner's
 *  alone. That split is enforced three ways so no single layer is the
 *  only guard: this screen hides what you cannot do, ClassGate only
 *  exposes sane operations, and firestore.rules refuses the rest. */
class MembersActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMembersBinding
    private var pendingAdapter: MemberAdapter? = null
    private var membersAdapter: MemberAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMembersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rvPendingMembers.layoutManager = LinearLayoutManager(this)
        binding.rvMembers.layoutManager = LinearLayoutManager(this)

        lifecycleScope.launch {
            val me = runCatching { ClassGate.myMember() }.getOrNull()
            if (me == null || !me.canManage) {
                Toast.makeText(
                    this@MembersActivity,
                    "Only the owner and admins manage members.",
                    Toast.LENGTH_LONG
                ).show()
                finish()
                return@launch
            }
            val isOwner = me.role == Roles.OWNER
            pendingAdapter = MemberAdapter(
                viewerIsOwner = isOwner,
                viewerCanManage = true,
                viewerUid = me.uid,
                pendingList = true,
                onAction = { member, action -> onMemberAction(member, action) }
            )
            membersAdapter = MemberAdapter(
                viewerIsOwner = isOwner,
                viewerCanManage = true,
                viewerUid = me.uid,
                pendingList = false,
                onAction = { member, action -> onMemberAction(member, action) }
            )
            binding.rvPendingMembers.adapter = pendingAdapter
            binding.rvMembers.adapter = membersAdapter

            ClassGate.observeMembers().collect { members ->
                val pending = members.filter { it.status == CloudConfig.STATUS_PENDING }
                val approved = members.filter { it.isApproved }
                pendingAdapter?.submit(pending)
                membersAdapter?.submit(approved)
                binding.tvPendingLabel.visibility =
                    if (pending.isEmpty()) View.GONE else View.VISIBLE
                binding.rvPendingMembers.visibility =
                    if (pending.isEmpty()) View.GONE else View.VISIBLE
                binding.tvMembersEmpty.visibility =
                    if (approved.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun onMemberAction(member: ClassGate.Member, action: MemberAdapter.Action) {
        when (action) {
            MemberAdapter.Action.APPROVE -> runAction {
                ClassGate.approve(member.uid)
                toast("${member.name} is in the class now.")
            }
            MemberAdapter.Action.REJECT -> confirm(
                title = "Reject ${member.name}?",
                message = "Their join request is deleted. They can ask again later if you change your mind.",
                positive = "Reject"
            ) {
                ClassGate.reject(member.uid)
                toast("Request from ${member.name} rejected.")
            }
            MemberAdapter.Action.MAKE_ADMIN -> runAction {
                ClassGate.setRole(member.uid, Roles.ADMIN)
                toast("${member.name} is now an Admin.")
            }
            MemberAdapter.Action.MAKE_MEMBER -> runAction {
                ClassGate.setRole(member.uid, Roles.MEMBER)
                toast("${member.name} is now a Member.")
            }
            MemberAdapter.Action.REMOVE -> confirm(
                title = "Remove ${member.name}?",
                message = "They lose access to the class immediately. Files they already uploaded stay in the folders.",
                positive = "Remove"
            ) {
                ClassGate.removeMember(member.uid)
                toast("${member.name} removed from the class.")
            }
        }
    }

    private fun runAction(block: suspend () -> Unit) {
        lifecycleScope.launch {
            runCatching { block() }.onFailure {
                toast("That did not work: ${it.message ?: "unknown error"}")
            }
        }
    }

    private fun confirm(
        title: String,
        message: String,
        positive: String,
        block: suspend () -> Unit
    ) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positive) { _, _ -> runAction(block) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(text: String) =
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
