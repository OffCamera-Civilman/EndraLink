package com.endralink.app.storage

import android.hardware.usb.*
import java.io.Closeable
import java.io.IOException
import com.endralink.app.DebugLog

/** Owns a USB interface claim (driver detach requires explicit user confirmation) and exposes only read-only storage operations. */
class UsbStorageSession(
    private val connection: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val directAccess: Boolean = false,
    private val isCancelled: () -> Boolean
) : Closeable {
    class InterfaceBusyException : IOException("USB storage is busy. Close other USB apps and safely eject any Android-mounted calculator storage before retrying.")

    private var closed = false
    val volume: Fat16Volume
    private val bot: ReadOnlyBot

    init {
        val endpoints = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
        val input = endpoints.first { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN }
        val output = endpoints.first { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT }
        DebugLog.event("USB_CLAIM_BEGIN", "interfaceId=" + intf.id + " force=" + directAccess)
        val claimed = connection.claimInterface(intf, directAccess)
        DebugLog.event("USB_CLAIM_RESULT", "claimed=" + claimed)
        if (!claimed) {
            throw InterfaceBusyException()
        }
        try {
            bot = ReadOnlyBot(object : ReadOnlyBot.BulkPipe {
                override fun send(data: ByteArray, offset: Int, length: Int): Int {
                    if (isCancelled()) throw IOException("Storage read cancelled.")
                    val result = connection.bulkTransfer(output, data, offset, length, 3000)
                    if (result != length) DebugLog.event("USB_SEND_SHORT", "expected=" + length + " actual=" + result)
                    return result
                }
                override fun receive(data: ByteArray, offset: Int, length: Int): Int {
                    if (isCancelled()) throw IOException("Storage read cancelled.")
                    val result = connection.bulkTransfer(input, data, offset, length, 3000)
                    if (result != length) DebugLog.event("USB_RECEIVE_SHORT", "expected=" + length + " actual=" + result)
                    return result
                }
                override fun clearInputHalt() {
                    DebugLog.event("USB_CLEAR_INPUT_HALT", "endpoint=" + input.address)
                    // Standard endpoint CLEAR_FEATURE(ENDPOINT_HALT); never sends a storage write.
                    if (connection.controlTransfer(0x02, 1, 0, input.address, null, 0, 1000) < 0) {
                        throw IOException("Cannot recover USB read endpoint. Reconnect the calculator.")
                    }
                }
            })
            DebugLog.event("STORAGE_INITIALIZE_BEGIN")
            bot.initialize()
            DebugLog.event("STORAGE_CAPACITY", "sectors=" + bot.sectorCount())
            volume = Fat16Volume(bot)
            DebugLog.event("FAT16_OPEN", "capacityBytes=" + volume.capacityBytes)
        } catch (e: Exception) {
            DebugLog.event("STORAGE_INITIALIZE_ERROR", error = e)
            DebugLog.event("USB_RELEASE_AFTER_ERROR", "released=" + connection.releaseInterface(intf))
            throw e
        }
    }

    /** Only called for the explicit Eject action on the serialized USB worker. */
    fun eject() {
        if (closed) throw IOException("USB session already closed.")
        DebugLog.event("EJECT_COMMAND_BEGIN", "ALLOW_MEDIUM_REMOVAL then START_STOP_UNIT LOEJ=1 START=0")
        bot.eject()
        DebugLog.event("EJECT_COMMAND_ACCEPTED")
    }

    /** Called on the serialized I/O worker after any pending read finishes. */
    override fun close() {
        if (!closed) {
            closed = true
            DebugLog.event("USB_RELEASE", "released=" + connection.releaseInterface(intf))
        }
    }
}
