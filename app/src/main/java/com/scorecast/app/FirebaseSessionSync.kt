package com.scorecast.app

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Firebase Realtime Database sync for the shared game-state object (spec §4/§9). Local-first
 * (spec §2): [GameStateHolder]'s local StateFlow stays the source of truth for rendering on both
 * devices — this only pushes local changes out and merges remote changes in; it never gates a
 * local read or write on the network.
 *
 * Scores/sets use [ServerValue.increment] on their own dedicated keys so simultaneous taps from
 * both devices both land (spec §4 "Conflict resolution"). Everything else (names, colors, text,
 * period, clock anchor) is last-write-wins via a targeted [DatabaseReference.updateChildren] per
 * changed field — never a full-object overwrite after the initial create, so unrelated fields
 * can't clobber each other.
 */
object FirebaseSessionSync {

    enum class Role { MAIN, MIRROR }

    sealed interface ConnectionState {
        data object Idle : ConnectionState
        data object Connecting : ConnectionState
        data object Connected : ConnectionState
        data object Reconnecting : ConnectionState
        data class Failed(val message: String) : ConnectionState
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /** Main-device-only (spec §9): whether a mirror has joined this session, for the top-bar dot. */
    private val _mirrorConnected = MutableStateFlow(false)
    val mirrorConnected: StateFlow<Boolean> = _mirrorConnected.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pushJob: Job? = null
    private var sessionRef: DatabaseReference? = null
    private var valueListener: ValueEventListener? = null
    private var connectedListener: ValueEventListener? = null
    private var membersListener: ValueEventListener? = null
    private var role: Role = Role.MAIN
    private var previousState: GameState? = null
    @Volatile private var applyingRemote = false

    /** Main device: create the session, write the initial state + membership, start syncing. */
    fun startAsMain(session: SessionCode) {
        stop()
        role = Role.MAIN
        _connectionState.value = ConnectionState.Connecting
        authenticate { uid ->
            if (uid == null) {
                _connectionState.value = ConnectionState.Failed("Sign-in failed")
                return@authenticate
            }
            val ref = FirebaseDatabase.getInstance().getReference("sessions").child(session.sessionId)
            ref.setValue(GameStateHolder.state.value.toFirebaseMap(session.joinToken, uid))
                .addOnSuccessListener {
                    sessionRef = ref
                    _connectionState.value = ConnectionState.Connected
                    attachListener(ref)
                    observeLocalChanges(ref)
                    // More than just our own membership entry means a mirror has joined.
                    val listener = object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            _mirrorConnected.value = snapshot.childrenCount > 1
                        }
                        override fun onCancelled(error: DatabaseError) {}
                    }
                    membersListener = listener
                    ref.child("members").addValueEventListener(listener)
                }
                .addOnFailureListener { e ->
                    _connectionState.value = ConnectionState.Failed(e.message ?: "Could not create session")
                }
        }
    }

    /**
     * Mirror device: validate the scanned joinToken against the session before joining (spec §9 —
     * a stray/expired scan must not bind). [onResult] reports whether the join succeeded.
     */
    fun joinAsMirror(session: SessionCode, onResult: (Boolean) -> Unit) {
        stop()
        role = Role.MIRROR
        _connectionState.value = ConnectionState.Connecting
        authenticate { uid ->
            if (uid == null) {
                _connectionState.value = ConnectionState.Failed("Sign-in failed")
                onResult(false)
                return@authenticate
            }
            val ref = FirebaseDatabase.getInstance().getReference("sessions").child(session.sessionId)
            ref.child("joinToken").get()
                .addOnSuccessListener { snapshot ->
                    val actualToken = snapshot.getValue(String::class.java)
                    if (actualToken == null || actualToken != session.joinToken) {
                        _connectionState.value = ConnectionState.Failed("This pairing code is no longer valid")
                        onResult(false)
                        return@addOnSuccessListener
                    }
                    // Writing our uid keyed by the now-verified joinToken lets the security rules
                    // (database.rules.json) confirm membership server-side too, not just here.
                    val memberRef = ref.child("members").child(uid)
                    memberRef.setValue(session.joinToken)
                        .addOnSuccessListener {
                            // Auto-clear our membership if we disconnect ungracefully, so the main
                            // device's "scorer offline" indicator (spec §9) reflects reality.
                            memberRef.onDisconnect().removeValue()
                            sessionRef = ref
                            _connectionState.value = ConnectionState.Connected
                            attachListener(ref)
                            observeLocalChanges(ref)
                            onResult(true)
                        }
                        .addOnFailureListener { e ->
                            _connectionState.value = ConnectionState.Failed(e.message ?: "Could not join session")
                            onResult(false)
                        }
                }
                .addOnFailureListener { e ->
                    _connectionState.value = ConnectionState.Failed(e.message ?: "Session not found")
                    onResult(false)
                }
        }
    }

    /**
     * Tears down local listeners. [deleteSession] additionally deletes the session node itself —
     * only meaningful (and only takes effect) for the main device, since it owns the session's
     * lifetime; a mirror leaving must never delete a session still live for the main device.
     */
    fun stop(deleteSession: Boolean = false) {
        if (deleteSession && role == Role.MAIN) {
            sessionRef?.removeValue()
        }
        pushJob?.cancel()
        pushJob = null
        valueListener?.let { l -> sessionRef?.removeEventListener(l) }
        valueListener = null
        membersListener?.let { l -> sessionRef?.child("members")?.removeEventListener(l) }
        membersListener = null
        connectedListener?.let { l ->
            FirebaseDatabase.getInstance().getReference(".info/connected").removeEventListener(l)
        }
        connectedListener = null
        sessionRef = null
        previousState = null
        _connectionState.value = ConnectionState.Idle
        _mirrorConnected.value = false
    }

    private fun authenticate(onReady: (uid: String?) -> Unit) {
        val auth = FirebaseAuth.getInstance()
        val existing = auth.currentUser
        if (existing != null) {
            onReady(existing.uid)
            return
        }
        auth.signInAnonymously()
            .addOnSuccessListener { result -> onReady(result.user?.uid) }
            .addOnFailureListener { onReady(null) }
    }

    private fun observeLocalChanges(ref: DatabaseReference) {
        previousState = GameStateHolder.state.value
        pushJob = scope.launch {
            GameStateHolder.state.drop(1).collect { newState ->
                if (applyingRemote) {
                    previousState = newState
                    return@collect
                }
                val old = previousState
                previousState = newState
                if (old != null) pushDiff(ref, old, newState)
            }
        }
    }

    private fun pushDiff(ref: DatabaseReference, old: GameState, new: GameState) {
        val updates = mutableMapOf<String, Any?>()
        if (new.homeScore != old.homeScore) {
            updates["homeScore"] = ServerValue.increment((new.homeScore - old.homeScore).toLong())
        }
        if (new.awayScore != old.awayScore) {
            updates["awayScore"] = ServerValue.increment((new.awayScore - old.awayScore).toLong())
        }
        if (new.setsWonHome != old.setsWonHome) {
            updates["setsWon/home"] = ServerValue.increment((new.setsWonHome - old.setsWonHome).toLong())
        }
        if (new.setsWonAway != old.setsWonAway) {
            updates["setsWon/away"] = ServerValue.increment((new.setsWonAway - old.setsWonAway).toLong())
        }
        if (new.homeTeam != old.homeTeam) updates["teamNames/home"] = new.homeTeam
        if (new.awayTeam != old.awayTeam) updates["teamNames/away"] = new.awayTeam
        if (new.homeColorHex != old.homeColorHex) updates["teamColors/home"] = new.homeColorHex
        if (new.awayColorHex != old.awayColorHex) updates["teamColors/away"] = new.awayColorHex
        if (new.customText != old.customText) updates["customText"] = new.customText
        if (new.period != old.period) updates["period"] = new.period
        if (new.periodLabel != old.periodLabel) updates["periodLabel"] = new.periodLabel
        if (new.clockDirection != old.clockDirection) updates["clockDirection"] = new.clockDirection
        if (new.clockRunning != old.clockRunning) updates["clockRunning"] = new.clockRunning
        if (new.startedAtMs != old.startedAtMs) updates["startedAt"] = new.startedAtMs
        if (new.baseRemainingSeconds != old.baseRemainingSeconds) {
            updates["baseRemaining"] = new.baseRemainingSeconds.toDouble()
        }
        if (new.overlayPosition != old.overlayPosition) updates["overlayPosition"] = new.overlayPosition.name
        if (new.sport != old.sport) updates["sport"] = new.sport
        if (new.extraFields != old.extraFields) updates["extraFields"] = new.extraFields
        if (new.playersHome != old.playersHome) updates["players/home"] = new.playersHome
        if (new.playersAway != old.playersAway) updates["players/away"] = new.playersAway
        if (updates.isEmpty()) return
        updates["updatedAt"] = ServerValue.TIMESTAMP
        updates["lastUpdatedBy"] = role.name
        ref.updateChildren(updates)
    }

    private fun attachListener(ref: DatabaseReference) {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val remote = snapshot.toGameState() ?: return
                applyingRemote = true
                GameStateHolder.applyRemote(remote)
                applyingRemote = false
            }
            override fun onCancelled(error: DatabaseError) {
                _connectionState.value = ConnectionState.Failed(error.message)
            }
        }
        valueListener = listener
        ref.addValueEventListener(listener)

        // Presence (spec §9 "scorer offline/connected" indicator) via the standard RTDB pattern.
        val connectedRef = FirebaseDatabase.getInstance().getReference(".info/connected")
        val cl = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val isConnected = snapshot.getValue(Boolean::class.java) ?: false
                _connectionState.value = if (isConnected) ConnectionState.Connected else ConnectionState.Reconnecting
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        connectedListener = cl
        connectedRef.addValueEventListener(cl)
    }
}
