package com.andrerinas.headunitrevived.connection

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.andrerinas.headunitrevived.utils.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LibusbAccessoryConnection(private val usbMgr: UsbManager, private val device: UsbDevice) : AccessoryConnection {
    @Volatile private var isConnectedVal = false
    private var usbDeviceConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null
    private var usbNative: UsbNative? = null

    // Leftover buffer — serves read data without truncation
    private var leftoverBuffer: ByteArray? = null
    private var leftoverPos = 0

    override val isSingleMessage: Boolean
        get() = false

    override val isConnected: Boolean
        get() = isConnectedVal

    fun isDeviceRunning(device: UsbDevice): Boolean {
        return isConnectedVal && UsbDeviceCompat.getUniqueName(device) == UsbDeviceCompat.getUniqueName(this.device)
    }

    override suspend fun connect() = withContext(Dispatchers.IO) {
        synchronized(sStateLock) {
            if (isConnectedVal) {
                disconnect()
            }
            try {
                if (!usbMgr.hasPermission(device)) {
                    AppLog.e("LibusbAccessoryConnection: No permission for USB device")
                    return@withContext false
                }
                
                // Open device
                var conn: UsbDeviceConnection? = null
                for (i in 0 until 3) {
                    try {
                        conn = usbMgr.openDevice(device)
                        if (conn != null) break
                    } catch (t: Throwable) {
                        AppLog.w("LibusbAccessoryConnection: Attempt ${i + 1} to openDevice failed: ${t.message}")
                    }
                    if (i < 2) try { Thread.sleep(1000) } catch (_: Exception) {}
                }
                
                if (conn == null) {
                    AppLog.e("LibusbAccessoryConnection: connection is null")
                    return@withContext false
                }
                usbDeviceConnection = conn

                if (device.interfaceCount <= 0) {
                    AppLog.e("LibusbAccessoryConnection: No interface found on device")
                    conn.close()
                    usbDeviceConnection = null
                    return@withContext false
                }
                usbInterface = device.getInterface(0)
                
                if (!conn.claimInterface(usbInterface, true)) {
                    AppLog.e("LibusbAccessoryConnection: Failed to claim interface")
                    conn.close()
                    usbDeviceConnection = null
                    return@withContext false
                }
                
                // Find endpoints
                for (i in 0 until usbInterface!!.endpointCount) {
                    val ep = usbInterface!!.getEndpoint(i)
                    if (ep.direction == UsbConstants.USB_DIR_IN) {
                        if (endpointIn == null) endpointIn = ep
                    } else {
                        if (endpointOut == null) endpointOut = ep
                    }
                }
                if (endpointIn == null || endpointOut == null) {
                    AppLog.e("LibusbAccessoryConnection: Unable to find endpoints")
                    conn.releaseInterface(usbInterface)
                    conn.close()
                    usbDeviceConnection = null
                    return@withContext false
                }

                val native = UsbNative()
                if (!native.wrap(conn, endpointIn!!.address, endpointOut!!.address)) {
                    AppLog.e("LibusbAccessoryConnection: Failed to wrap USB device via JNI")
                    native.close()
                    conn.releaseInterface(usbInterface)
                    conn.close()
                    usbDeviceConnection = null
                    return@withContext false
                }
                usbNative = native
                isConnectedVal = true
                AppLog.i("LibusbAccessoryConnection: Successfully connected via JNI Libusb")
                return@withContext true
            } catch (e: Exception) {
                AppLog.e("LibusbAccessoryConnection: Error during connect: ${e.message}")
                disconnect()
                return@withContext false
            }
        }
    }

    override fun disconnect() {
        synchronized(sStateLock) {
            isConnectedVal = false
            try {
                usbNative?.close()
            } catch (e: Exception) {
                AppLog.e("LibusbAccessoryConnection: Error closing native: ${e.message}")
            }
            usbNative = null
            
            try {
                if (usbDeviceConnection != null && usbInterface != null) {
                    usbDeviceConnection!!.releaseInterface(usbInterface)
                }
            } catch (e: Exception) {}
            
            try {
                usbDeviceConnection?.close()
            } catch (e: Exception) {}
            
            usbDeviceConnection = null
            usbInterface = null
            endpointIn = null
            endpointOut = null
            leftoverBuffer = null
            leftoverPos = 0
        }
    }

    override fun sendBlocking(buf: ByteArray, length: Int, timeout: Int): Int {
        val native = usbNative ?: return -1
        val data = if (buf.size == length) buf else buf.copyOfRange(0, length)
        return native.write(data, timeout)
    }

    override fun recvBlocking(buf: ByteArray, length: Int, timeout: Int, readFully: Boolean): Int {
        val native = usbNative ?: return -1
        var totalReturned = 0

        while (totalReturned < length) {
            val leftover = leftoverBuffer
            if (leftover != null) {
                val available = leftover.size - leftoverPos
                val toCopy = minOf(length - totalReturned, available)
                System.arraycopy(leftover, leftoverPos, buf, totalReturned, toCopy)
                leftoverPos += toCopy
                totalReturned += toCopy

                if (leftoverPos >= leftover.size) {
                    leftoverBuffer = null
                    leftoverPos = 0
                }

                if (totalReturned >= length || !readFully) break
                continue
            }

            val readBytes = native.read(timeout)
            if (readBytes == null) {
                return if (totalReturned > 0) totalReturned else -1
            }
            if (readBytes.isEmpty()) {
                return totalReturned
            }

            leftoverBuffer = readBytes
            leftoverPos = 0
        }

        return totalReturned
    }

    companion object {
        private val sStateLock = Any()
    }
}
