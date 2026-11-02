package com.narada.android.connection

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Trust operations use application scope; QR input preparation/consumption follows the Activity lifecycle. */
@HiltViewModel
class ConnectionsViewModel @Inject constructor(private val owner: ConnectionsCoordinator, discovery: LocalDiscovery) : ViewModel() {
    val state = owner.state
    val discoveryState = discovery.state
    fun foregroundChanged(value: Boolean) = owner.foregroundChanged(value)
    fun initialize() = owner.initialize()
    suspend fun preparePairingInput(): Boolean = owner.preparePairingInput()
    fun reload() = owner.reload()
    fun enable(value: Boolean) = owner.enable(value)
    fun acceptCode(value: String) = owner.acceptCode(value)
    suspend fun acceptPairingInput(value: String) = owner.acceptPairingInput(value)
    fun discardCode() = owner.discardCode()
    fun requestPairing(compared: Boolean) = owner.requestPairing(compared)
    fun finish(id: String, compared: Boolean) = owner.finish(id, compared)
    fun recover(id: String) = owner.recover(id)
    fun reconnect(id: String, endpoint: String) = owner.reconnect(id, endpoint)
    fun revoke(id: String) = owner.revoke(id)
    fun inputUnavailable() = owner.inputUnavailable()
    fun scanNetwork() = owner.scanNetwork()
    fun stopDiscovery() = owner.stopDiscovery()
}
