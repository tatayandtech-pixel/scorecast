package com.scorecast.app

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks Firebase's `.info/serverTimeOffset` (spec §5 "Clock skew (required)") so the clock-anchor
 * elapsed-time math can compute `now` as `localTime + offsetMs` instead of raw device time, which
 * drifts against a paired device's clock. [offsetMs] stays 0 whenever no session is active, so
 * solo (unpaired) use is unaffected.
 */
object ServerTimeSync {
    private val _offsetMs = MutableStateFlow(0L)
    val offsetMs: StateFlow<Long> = _offsetMs.asStateFlow()

    private var listener: ValueEventListener? = null

    fun start() {
        if (listener != null) return
        val ref = FirebaseDatabase.getInstance().getReference(".info/serverTimeOffset")
        val l = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                _offsetMs.value = snapshot.getValue(Long::class.java) ?: 0L
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        listener = l
        ref.addValueEventListener(l)
    }

    fun stop() {
        listener?.let { l ->
            FirebaseDatabase.getInstance().getReference(".info/serverTimeOffset").removeEventListener(l)
        }
        listener = null
        _offsetMs.value = 0L
    }

    fun nowMs(): Long = System.currentTimeMillis() + offsetMs.value
}
