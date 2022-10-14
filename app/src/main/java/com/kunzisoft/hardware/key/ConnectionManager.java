package com.kunzisoft.hardware.key;

import android.app.Activity;
import android.app.Application;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;

import com.kunzisoft.hardware.yubikey.challenge.DummyYubiKey;
import com.kunzisoft.hardware.yubikey.challenge.NfcYubiKey;
import com.kunzisoft.hardware.yubikey.challenge.UsbYubiKey;
import com.kunzisoft.hardware.yubikey.challenge.YubiKey;

/**
 * Manages the lifecycle of a YubiKey connection via USB or NFC.
 */
class ConnectionManager extends BroadcastReceiver implements Application.ActivityLifecycleCallbacks {
	private final Activity activity;
	private boolean isActivityResumed;

	private static final String ACTION_USB_PERMISSION_REQUEST = "android.yubikey.intent.action.USB_PERMISSION_REQUEST";

	public static final byte CONNECTION_VOID = 0b0;
	/**
	 * Flag used to indicate that support for USB host mode is present on the Android device.
	 */
	public static final byte CONNECTION_METHOD_USB = 0b1;
	/**
	 * Flag used to indicate that support for NFC is present on the Android device.
	 */
	public static final byte CONNECTION_METHOD_NFC = 0b10;

	private YubiKeyConnectReceiver   connectReceiver;
	private YubiKeyUsbUnplugReceiver unplugReceiver;

	/**
	 * Receiver interface that is called when a YubiKey was connected.
	 */
	interface YubiKeyConnectReceiver {
		/**
		 * Called when a YubiKey was connected via USB or NFC.
		 *
		 * @param yubiKey The YubiKey driver implementation, instantiated with a connection to the
		 *                YubiKey.
		 */
		void onYubiKeyConnected(YubiKey yubiKey);
	}

	/**
	 * Receiver interface that is called when a YubiKey connected via USB was unplugged.
	 */
	interface YubiKeyUsbUnplugReceiver {
		/**
		 * Called when a YubiKey connected via USB was unplugged.
		 */
		void onYubiKeyUnplugged();
	}

	/**
	 * May only be instantiated as soon as the basic initialization of a new activity is complete
	 *
	 * @param activity As the connection lifecycle depends on the activity lifecycle, an active
	 *                 {@link Activity} must be passed
	 */
	ConnectionManager(final Activity activity) {
		this.activity = activity;
		this.activity.getApplication().registerActivityLifecycleCallbacks(this);
	}

	@Override
	public void onActivityCreated(final Activity activity, final Bundle savedInstanceState) {
	}

	/**
	 * Waits for a YubiKey to be connected.
	 *
	 * @param receiver The receiver implementation to be called as soon as a YubiKey was connected.
	 */
	public void waitForYubiKey(final YubiKeyConnectReceiver receiver) {
		this.connectReceiver = receiver;
	}

	@Override
	public void onActivityStarted(final Activity activity) {
		// Debug with dummy connection if no supported connection
		if (BuildConfig.DEBUG && this.getSupportedConnectionMethods() == CONNECTION_VOID) {
			initDummyConnection();
		} else {
			if (this.connectReceiver == null || (this.getSupportedConnectionMethods() & CONNECTION_METHOD_USB) == 0)
				return;
			initUSBConnection();
		}
	}

	@Override
	public void onActivityResumed(final Activity activity) {
		if (this.connectReceiver == null || (this.getSupportedConnectionMethods() & CONNECTION_METHOD_NFC) == 0)
			return;
		initNFCConnection();
		this.isActivityResumed = true;
	}

	private void initDummyConnection() {
		// Debug by injecting a known byte array
		this.connectReceiver.onYubiKeyConnected(new DummyYubiKey(activity));
	}

	private void initUSBConnection() {

		final UsbManager usbManager = (UsbManager) this.activity.getSystemService(Context.USB_SERVICE);

		this.activity.registerReceiver(this, new IntentFilter(ACTION_USB_PERMISSION_REQUEST));
		this.activity.registerReceiver(this, new IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED));

		for (final UsbDevice device : usbManager.getDeviceList().values())
			this.requestPermission(device);
	}

	private void initNFCConnection() {

		final IntentFilter filter = new IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED);

		int flags = PendingIntent.FLAG_UPDATE_CURRENT;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
		}
		NfcAdapter.getDefaultAdapter(this.activity).enableForegroundDispatch(this.activity,
				PendingIntent.getActivity(
						this.activity,
						-1,
						new Intent(this.activity, this.activity.getClass()),
						flags
				),
				new IntentFilter[]{filter},
				new String[][]{new String[]{IsoDep.class.getName()}});
	}

	/**
	 * Waits until no YubiKey is connected.
	 *
	 * @param receiver The receiver implementation to be called as soon as no YubiKey is connected
	 *                 anymore.
	 */
	public void waitForYubiKeyUnplug(final YubiKeyUsbUnplugReceiver receiver) {
		if ((this.getSupportedConnectionMethods() & CONNECTION_METHOD_USB) == 0) {
			receiver.onYubiKeyUnplugged();
			return;
		}

		if (this.isYubiKeyNotPlugged()) {
			receiver.onYubiKeyUnplugged();
			return;
		}

		this.unplugReceiver = receiver;
		this.activity.registerReceiver(this, new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED));
	}

	private boolean isYubiKeyNotPlugged() {
		final UsbManager usbManager = (UsbManager) this.activity.getSystemService(Context.USB_SERVICE);

		for (final UsbDevice device : usbManager.getDeviceList().values()) {
			if (UsbYubiKey.Type.isDeviceKnown(device))
				return false;
		}

		return true;
	}

	@Override
	public void onReceive(final Context context, @NonNull Intent intent) {
		switch (intent.getAction()) {
			case ACTION_USB_PERMISSION_REQUEST:
				if(this.isYubiKeyNotPlugged()) // Do not keep asking for permission to access a YubiKey that was unplugged already
					break;
			case UsbManager.ACTION_USB_DEVICE_ATTACHED:
				this.requestPermission((UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE));
				break;
			case UsbManager.ACTION_USB_DEVICE_DETACHED:
				if (UsbYubiKey.Type.isDeviceKnown(((UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)))) {
					this.activity.unregisterReceiver(this);
					this.unplugReceiver.onYubiKeyUnplugged();
					this.unplugReceiver = null;
				}
				break;
			case NfcAdapter.ACTION_TECH_DISCOVERED:
				final IsoDep isoDep = IsoDep.get((Tag) intent.getParcelableExtra(NfcAdapter.EXTRA_TAG));

				if (isoDep == null) {
					// Not a YubiKey
					return;
				}

				if ((this.getSupportedConnectionMethods() & CONNECTION_METHOD_USB) != 0)
					this.activity.unregisterReceiver(this);

				this.connectReceiver.onYubiKeyConnected(new NfcYubiKey(isoDep));
				this.connectReceiver = null;
				break;
		}
	}

	@Override
	public void onActivityPaused(final Activity activity) {
		if (this.connectReceiver != null && (this.getSupportedConnectionMethods() & CONNECTION_METHOD_NFC) != 0)
			NfcAdapter.getDefaultAdapter(this.activity).disableForegroundDispatch(this.activity);

		this.isActivityResumed = false;
	}

	@Override
	public void onActivityStopped(final Activity activity) {
		try {
			if (this.connectReceiver != null || this.unplugReceiver != null)
				this.activity.unregisterReceiver(this);
		} catch (Exception e) {
			Log.e("ConnectionManager", "Error when unregister receiver", e);
		}
	}

	private void requestPermission(final UsbDevice device) {
		final UsbManager usbManager = (UsbManager) this.activity.getSystemService(Context.USB_SERVICE);

		if (!UsbYubiKey.Type.isDeviceKnown(device))
			return;

		if (usbManager.hasPermission(device)) {
			this.activity.unregisterReceiver(this);

			if ((this.getSupportedConnectionMethods() & CONNECTION_METHOD_NFC) != 0 && this.isActivityResumed)
				NfcAdapter.getDefaultAdapter(this.activity).disableForegroundDispatch(this.activity);

			this.connectReceiver.onYubiKeyConnected(new UsbYubiKey(device, usbManager.openDevice(device)));
			this.connectReceiver = null;
		} else {
			int flags = PendingIntent.FLAG_UPDATE_CURRENT;
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
				flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
			}
			usbManager.requestPermission(
					device,
					PendingIntent.getBroadcast(
							this.activity,
							0,
							new Intent(ACTION_USB_PERMISSION_REQUEST),
							flags
					)
			);
		}
	}

	/**
	 * Gets the connection methods (USB and/or NFC) that are supported on the Android device.
	 *
	 * @return A byte that may or may not have the {@link #CONNECTION_METHOD_USB} and
	 * {@link #CONNECTION_METHOD_USB} bits set.
	 */
	public byte getSupportedConnectionMethods() {
		final PackageManager packageManager = this.activity.getPackageManager();
		byte result = CONNECTION_VOID;

		if (packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST))
			result |= CONNECTION_METHOD_USB;

		if (packageManager.hasSystemFeature(PackageManager.FEATURE_NFC) && NfcAdapter.getDefaultAdapter(this.activity).isEnabled())
			result |= CONNECTION_METHOD_NFC;

		return result;
	}

	@Override
	public void onActivitySaveInstanceState(final Activity activity, final Bundle outState) {
	}

	@Override
	public void onActivityDestroyed(final Activity activity) {
		this.activity.getApplication().unregisterActivityLifecycleCallbacks(this);
	}
}
