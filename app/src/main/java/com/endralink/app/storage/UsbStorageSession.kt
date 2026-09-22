package com.endralink.app.storage

import android.hardware.usb.*
import java.io.Closeable
import java.io.IOException

/** Owns a non-forced USB interface claim and exposes only read-only storage operations. */
class UsbStorageSession(
    private val connection: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val isCancelled: () -> Boolean
) : Closeable {
    private var closed = false
    val volume: Fat16Volume

    init {
        val endpoints = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
        val input = endpoints.first { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN }
        val output = endpoints.first { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT }
        if (!connection.claimInterface(intf, false)) {
            throw IOException("USB storage is busy. Close other USB apps; if Android mounted it, safely eject it there first. EndraLink will not force-detach a storage driver.")
        }
        try {
            val bot = ReadOnlyBot(object : ReadOnlyBot.BulkPipe {
                override fun send(data: ByteArray, offset: Int, length: Int): Int {
                    if (isCancelled()) throw IOException("Storage read cancelled.")
                    return connection.bulkTransfer(output, data, offset, length, 3000)
                }
                override fun receive(data: ByteArray, offset: Int, length: Int): Int {
                    if (isCancelled()) throw IOException("Storage read cancelled.")
                    return connection.bulkTransfer(input, data, offset, length, 3000)
                }
                override fun clearInputHalt() {
                    // Standard endpoint CLEAR_FEATURE(ENDPOINT_HALT); never sends a storage write.
                    if (connection.controlTransfer(0x02, 1, 0, input.address, null, 0, 1000) < 0) {
                        throw IOException("Cannot recover USB read endpoint. Reconnect the calculator.")
                    }
                }
            })
            bot.initialize()
            volume = Fat16Volume(bot)
        } catch (e: Exception) {
            connection.releaseInterface(intf)
            throw e
        }
    }

    /** Called on the serialized I/O worker after any pending read finishes. */
    override fun close() {
        if (!closed) {
            closed = true
            connection.releaseInterface(intf)
        }
    }
}
