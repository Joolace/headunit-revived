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
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

class LibusbAccessoryConnection(private val usbMgr: UsbManager, private val device: UsbDevice) : AccessoryConnection {
    @Volatile private var isConnectedVal = false
    @Volatile private var isConnecting = false
    private var usbDeviceConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null
    private var usbNative: UsbNative? = null

    // Direct ByteBuffer for JNI and tracking leftover state
    private val readBuffer = ByteBuffer.allocateDirect(16384)
    private var leftoverSize = 0
    private var leftoverPos = 0

    private val activeTransfers = AtomicInteger(0)

    override val isSingleMessage: Boolean
        get() = false

    override val isConnected: Boolean
        get() = isConnectedVal

    fun isDeviceRunning(device: UsbDevice): Boolean {
        return isConnectedVal && UsbDeviceCompat.getUniqueName(device) == UsbDeviceCompat.getUniqueName(this.device)
    }

    override suspend fun connect() = withContext(Dispatchers.IO) {
        synchronized(sStateLock) {
            if (isConnectedVal || isConnecting) {
                return@withContext false
            }
            isConnecting = true
        }

        try {
            if (!usbMgr.hasPermission(device)) {
                AppLog.e("LibusbAccessoryConnection: No permission for USB device")
                synchronized(sStateLock) { isConnecting = false }
                return@withContext false
            }
            
            // Open device
            var conn: UsbDeviceConnection? = null
            for (i in 0 until 3) {
                if (!isConnecting) {
                    conn?.close()
                    return@withContext false
                }
                try {
                    conn = usbMgr.openDevice(device)
                    if (conn != null) break
                } catch (t: Throwable) {
                    AppLog.w("LibusbAccessoryConnection: Attempt ${i + 1} to openDevice failed: ${t.message}")
                }
                if (i < 2) {
                    try {
                        for (k in 0 until 10) {
                            if (!isConnecting) {
                                conn?.close()
                                return@withContext false
                            }
                            Thread.sleep(100)
                        }
                    } catch (_: Exception) {}
                }
            }
            
            if (conn == null) {
                AppLog.e("LibusbAccessoryConnection: connection is null")
                synchronized(sStateLock) { isConnecting = false }
                return@withContext false
            }

            if (!isConnecting) {
                conn.close()
                return@withContext false
            }

            synchronized(sStateLock) {
                usbDeviceConnection = conn
            }

            if (device.interfaceCount <= 0) {
                AppLog.e("LibusbAccessoryConnection: No interface found on device")
                synchronized(sStateLock) {
                    conn.close()
                    usbDeviceConnection = null
                    isConnecting = false
                }
                return@withContext false
            }
            val iface = device.getInterface(0)
            synchronized(sStateLock) {
                usbInterface = iface
            }
            
            if (!conn.claimInterface(iface, true)) {
                AppLog.e("LibusbAccessoryConnection: Failed to claim interface")
                synchronized(sStateLock) {
                    conn.close()
                    usbDeviceConnection = null
                    usbInterface = null
                    isConnecting = false
                }
                return@withContext false
            }
            
            // Find endpoints
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.direction == UsbConstants.USB_DIR_IN) {
                    if (epIn == null) epIn = ep
                } else {
                    if (epOut == null) epOut = ep
                }
            }
            if (epIn == null || epOut == null) {
                AppLog.e("LibusbAccessoryConnection: Unable to find endpoints")
                synchronized(sStateLock) {
                    conn.releaseInterface(iface)
                    conn.close()
                    usbDeviceConnection = null
                    usbInterface = null
                    isConnecting = false
                }
                return@withContext false
            }

            synchronized(sStateLock) {
                endpointIn = epIn
                endpointOut = epOut
            }

            if (!isConnecting) {
                synchronized(sStateLock) {
                    conn.releaseInterface(iface)
                    conn.close()
                    usbDeviceConnection = null
                    usbInterface = null
                    endpointIn = null
                    endpointOut = null
                    isConnecting = false
                }
                return@withContext false
            }

            val native = UsbNative()
            if (!native.wrap(conn, epIn.address, epOut.address)) {
                AppLog.e("LibusbAccessoryConnection: Failed to wrap USB device via JNI")
                synchronized(sStateLock) {
                    native.close()
                    conn.releaseInterface(iface)
                    conn.close()
                    usbDeviceConnection = null
                    usbInterface = null
                    endpointIn = null
                    endpointOut = null
                    isConnecting = false
                }
                return@withContext false
            }

            synchronized(sStateLock) {
                if (!isConnecting) {
                    native.close()
                    conn.releaseInterface(iface)
                    conn.close()
                    usbDeviceConnection = null
                    usbInterface = null
                    endpointIn = null
                    endpointOut = null
                    isConnecting = false
                    return@withContext false
                }
                usbNative = native
                isConnectedVal = true
                isConnecting = false
            }
            AppLog.i("LibusbAccessoryConnection: Successfully connected via JNI Libusb")
            return@withContext true
        } catch (e: Exception) {
            AppLog.e("LibusbAccessoryConnection: Error during connect: ${e.message}")
            synchronized(sStateLock) {
                isConnecting = false
            }
            disconnect()
            return@withContext false
        }
    }

    override fun disconnect() {
        synchronized(sStateLock) {
            isConnecting = false
            isConnectedVal = false
        }

        // Wait for active JNI transfers (readers/writers) to finish before freeing context
        val start = System.currentTimeMillis()
        while (activeTransfers.get() > 0 && System.currentTimeMillis() - start < 6000) {
            try {
                Thread.sleep(50)
            } catch (e: InterruptedException) {
                break
            }
        }

        synchronized(sStateLock) {
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
            leftoverSize = 0
            leftoverPos = 0
        }
    }

    override fun sendBlocking(buf: ByteArray, length: Int, timeout: Int): Int {
        if (!isConnectedVal) return -1
        val native = usbNative ?: return -1
        activeTransfers.incrementAndGet()
        try {
            if (!isConnectedVal) return -1
            return native.write(buf, length, timeout)
        } finally {
            activeTransfers.decrementAndGet()
        }
    }

    override fun recvBlocking(buf: ByteArray, length: Int, timeout: Int, readFully: Boolean): Int {
        if (!isConnectedVal) return -1
        val native = usbNative ?: return -1
        activeTransfers.incrementAndGet()
        try {
            if (!isConnectedVal) return -1
            var totalReturned = 0

            while (totalReturned < length) {
                if (leftoverSize > 0) {
                    val available = leftoverSize - leftoverPos
                    val toCopy = minOf(length - totalReturned, available)
                    
                    readBuffer.position(leftoverPos)
                    readBuffer.get(buf, totalReturned, toCopy)
                    
                    leftoverPos += toCopy
                    totalReturned += toCopy

                    if (leftoverPos >= leftoverSize) {
                        leftoverSize = 0
                        leftoverPos = 0
                    }

                    if (totalReturned >= length || !readFully) break
                    continue
                }

                readBuffer.clear()
                val transferred = native.read(readBuffer, timeout)
                if (transferred < 0) {
                    isConnectedVal = false
                    return if (totalReturned > 0) totalReturned else -1
                }
                if (transferred == 0) {
                    return totalReturned
                }

                leftoverSize = transferred
                leftoverPos = 0
            }

            return totalReturned
        } finally {
            activeTransfers.decrementAndGet()
        }
    }

    companion object {
        private val sStateLock = Any()
    }
}
