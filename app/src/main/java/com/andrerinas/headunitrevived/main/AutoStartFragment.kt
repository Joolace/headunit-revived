package com.andrerinas.headunitrevived.main

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.main.settings.SettingItem
import com.andrerinas.headunitrevived.main.settings.SettingsAdapter
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class AutoStartFragment : Fragment() {
    private lateinit var settings: Settings
    private lateinit var recyclerView: RecyclerView
    private lateinit var settingsAdapter: SettingsAdapter
    private lateinit var toolbar: MaterialToolbar
    private var saveButton: MaterialButton? = null

    private var pendingAutoStartOnBoot: Boolean? = null
    private var pendingAutoStartOnScreenOn: Boolean? = null
    private var pendingAutoStartOnUsb: Boolean? = null
    private var pendingAutoStartBtName: String? = null
    private var pendingAutoStartBtMac: String? = null
    private var pendingReopenOnReconnection: Boolean? = null

    private var hasChanges = false
    private val SAVE_ITEM_ID = 1001

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showBluetoothDeviceSelector()
        } else {
            showBluetoothPermissionDeniedDialog()
        }
    }

    private val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            showBluetoothDeviceSelector()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_auto_start, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        settings = App.provide(requireContext()).settings

        pendingAutoStartOnBoot = settings.autoStartOnBoot
        pendingAutoStartOnScreenOn = settings.autoStartOnScreenOn
        pendingAutoStartOnUsb = settings.autoStartOnUsb
        pendingAutoStartBtName = settings.autoStartBluetoothDeviceName
        pendingAutoStartBtMac = settings.autoStartBluetoothDeviceMac
        pendingReopenOnReconnection = settings.reopenOnReconnection

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPress()
            }
        })

        toolbar = view.findViewById(R.id.toolbar)
        settingsAdapter = SettingsAdapter()
        recyclerView = view.findViewById(R.id.recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = settingsAdapter

        updateSettingsList()
        setupToolbar()
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener {
            handleBackPress()
        }

        val saveItem = toolbar.menu.add(0, SAVE_ITEM_ID, 0, getString(R.string.save))
        saveItem.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        saveItem.setActionView(R.layout.layout_save_button)

        saveButton = saveItem.actionView?.findViewById(R.id.save_button_widget)
        saveButton?.setOnClickListener {
            saveSettings()
        }

        updateSaveButtonState()
    }

    private fun handleBackPress() {
        if (hasChanges) {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.unsaved_changes)
                .setMessage(R.string.unsaved_changes_message)
                .setPositiveButton(R.string.discard) { _, _ ->
                    navigateBack()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            navigateBack()
        }
    }

    private fun navigateBack() {
        try {
            val navController = findNavController()
            if (!navController.navigateUp()) {
                requireActivity().finish()
            }
        } catch (e: Exception) {
            requireActivity().finish()
        }
    }

    private fun updateSaveButtonState() {
        saveButton?.isEnabled = hasChanges
        saveButton?.text = getString(R.string.save)
    }

    private fun saveSettings() {
        pendingAutoStartOnBoot?.let {
            settings.autoStartOnBoot = it
            Settings.syncAutoStartOnBootToDeviceStorage(requireContext(), it)
        }
        pendingAutoStartOnScreenOn?.let {
            settings.autoStartOnScreenOn = it
            Settings.syncAutoStartOnScreenOnToDeviceStorage(requireContext(), it)
        }
        pendingAutoStartOnUsb?.let {
            settings.autoStartOnUsb = it
            Settings.syncAutoStartOnUsbToDeviceStorage(requireContext(), it)
        }
        pendingAutoStartBtName?.let { settings.autoStartBluetoothDeviceName = it }
        pendingAutoStartBtMac?.let {
            settings.autoStartBluetoothDeviceMac = it
            Settings.syncAutoStartBtMacToDeviceStorage(requireContext(), it)
        }
        pendingReopenOnReconnection?.let { settings.reopenOnReconnection = it }

        // Check for Overlay permission if any auto-start is configured
        if ((!pendingAutoStartBtMac.isNullOrEmpty() || pendingAutoStartOnUsb == true || pendingAutoStartOnBoot == true || pendingAutoStartOnScreenOn == true) && Build.VERSION.SDK_INT >= 23) {
            if (!android.provider.Settings.canDrawOverlays(requireContext())) {
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.overlay_permission_title)
                    .setMessage(R.string.overlay_permission_description)
                    .setPositiveButton(R.string.open_settings) { _, _ ->
                        val intent = Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:${requireContext().packageName}")
                        )
                        startActivity(intent)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }

        // Start the foreground service immediately when wake-detection settings
        // are enabled so it can register the dynamic SCREEN_ON receiver.
        if (settings.autoStartOnScreenOn || settings.autoStartOnBoot) {
            ContextCompat.startForegroundService(requireContext(),
                Intent(requireContext(), AapService::class.java))
        }

        hasChanges = false
        updateSaveButtonState()

        Toast.makeText(context, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
    }

    private fun checkChanges() {
        hasChanges = pendingAutoStartOnBoot != settings.autoStartOnBoot ||
                pendingAutoStartOnScreenOn != settings.autoStartOnScreenOn ||
                pendingAutoStartOnUsb != settings.autoStartOnUsb ||
                pendingAutoStartBtMac != settings.autoStartBluetoothDeviceMac ||
                pendingReopenOnReconnection != settings.reopenOnReconnection

        updateSaveButtonState()
    }

    private fun updateSettingsList() {
        val scrollState = recyclerView.layoutManager?.onSaveInstanceState()
        val items = mutableListOf<SettingItem>()

        items.add(SettingItem.CategoryHeader("autoStart", R.string.auto_start_settings))

        items.add(SettingItem.InfoBanner(
            stableId = "autoStartOemWarning",
            textResId = R.string.auto_start_oem_warning
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "autoStartOnBoot",
            nameResId = R.string.auto_start_on_boot_label,
            descriptionResId = R.string.auto_start_on_boot_description,
            isChecked = pendingAutoStartOnBoot!!,
            onCheckedChanged = { isChecked ->
                pendingAutoStartOnBoot = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "autoStartOnScreenOn",
            nameResId = R.string.auto_start_screen_on_label,
            descriptionResId = R.string.auto_start_screen_on_description,
            isChecked = pendingAutoStartOnScreenOn!!,
            onCheckedChanged = { isChecked ->
                pendingAutoStartOnScreenOn = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "autoStartUsb",
            nameResId = R.string.auto_start_usb_label,
            descriptionResId = R.string.auto_start_usb_description,
            isChecked = pendingAutoStartOnUsb!!,
            onCheckedChanged = { isChecked ->
                pendingAutoStartOnUsb = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        if (pendingAutoStartOnUsb == true) {
            items.add(SettingItem.ToggleSettingEntry(
                stableId = "reopenOnReconnection",
                nameResId = R.string.reopen_on_reconnection_label,
                descriptionResId = R.string.reopen_on_reconnection_description,
                isChecked = pendingReopenOnReconnection!!,
                onCheckedChanged = { isChecked ->
                    pendingReopenOnReconnection = isChecked
                    checkChanges()
                }
            ))
        }

        items.add(SettingItem.SettingEntry(
            stableId = "autoStartBt",
            nameResId = R.string.auto_start_bt_label,
            value = if (pendingAutoStartBtName.isNullOrEmpty()) getString(R.string.bt_device_not_set) else pendingAutoStartBtName!!,
            onClick = {
                showBluetoothDeviceSelector()
            }
        ))

        settingsAdapter.submitList(items) {
            scrollState?.let { recyclerView.layoutManager?.onRestoreInstanceState(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check overlay permission. If the user returned from system settings
        // without granting it, disable auto-start settings that require it.
        if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(requireContext())) {
            var disabled = false
            if (settings.autoStartOnBoot) {
                settings.autoStartOnBoot = false
                pendingAutoStartOnBoot = false
                disabled = true
            }
            if (settings.autoStartOnScreenOn) {
                settings.autoStartOnScreenOn = false
                Settings.syncAutoStartOnScreenOnToDeviceStorage(requireContext(), false)
                pendingAutoStartOnScreenOn = false
                disabled = true
            }
            if (settings.autoStartOnUsb) {
                settings.autoStartOnUsb = false
                pendingAutoStartOnUsb = false
                disabled = true
            }
            if (!settings.autoStartBluetoothDeviceMac.isNullOrEmpty()) {
                settings.autoStartBluetoothDeviceMac = ""
                settings.autoStartBluetoothDeviceName = ""
                pendingAutoStartBtMac = ""
                pendingAutoStartBtName = ""
                disabled = true
            }
            if (disabled) {
                AppLog.w("Overlay permission not granted, disabling auto-start settings")
                Toast.makeText(requireContext(), getString(R.string.overlay_permission_denied_auto_start_disabled), Toast.LENGTH_LONG).show()
                checkChanges()
                updateSettingsList()
            }
        }
    }

    private fun showBluetoothDeviceSelector() {
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            bluetoothPermissionLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
            return
        }

        val adapter = if (Build.VERSION.SDK_INT >= 18) {
            (requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
        } else {
            @Suppress("DEPRECATION")
            android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        }

        if (adapter == null || !adapter.isEnabled) {
            val enableIntent = Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)
            bluetoothEnableLauncher.launch(enableIntent)
            return
        }

        val bondedDevices = adapter.bondedDevices.toList()

        if (bondedDevices.isEmpty()) {
            Toast.makeText(requireContext(), "No paired Bluetooth devices found", Toast.LENGTH_LONG).show()
            return
        }

        val deviceNames = bondedDevices.map { it.name ?: "Unknown Device" }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.select_bt_device)
            .setItems(deviceNames) { _, which ->
                val device = bondedDevices[which]
                pendingAutoStartBtMac = device.address
                pendingAutoStartBtName = device.name
                checkChanges()
                updateSettingsList()
            }
            .setNeutralButton(R.string.remove) { _, _ ->
                pendingAutoStartBtMac = ""
                pendingAutoStartBtName = ""
                checkChanges()
                updateSettingsList()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showBluetoothPermissionDeniedDialog() {
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.bt_permission_denied_title)
            .setMessage(R.string.bt_permission_denied_message)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                val intent = Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:${requireContext().packageName}")
                )
                startActivity(intent)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
