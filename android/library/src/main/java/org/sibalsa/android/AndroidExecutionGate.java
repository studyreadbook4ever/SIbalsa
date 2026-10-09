// SPDX-License-Identifier: Unlicense
package org.sibalsa.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.PowerManager;
import org.sibalsa.core.Cancellation;
import org.sibalsa.core.DeviceState;
import org.sibalsa.core.ExecutionGate;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** An idle permit exists only while the platform is running our constrained JobService. */
public final class AndroidExecutionGate implements ExecutionGate, AutoCloseable {
    private final Context context;
    private final ConnectivityManager connectivity;
    private final PowerManager power;
    private final boolean requireUnmetered;
    private final Cancellation cancellation;
    private final AtomicBoolean permit = new AtomicBoolean(true);
    private boolean receiverRegistered, callbackRegistered;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { checkTransition(); }
    };
    private final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
        @Override public void onLost(Network network) { checkTransition(); }
        @Override public void onAvailable(Network network) { checkTransition(); }
        @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) { checkTransition(); }
    };

    // Package-private construction prevents ordinary foreground callers from issuing an idle permit.
    AndroidExecutionGate(Context context, boolean requireUnmetered, Cancellation cancellation) {
        this.context = context.getApplicationContext();
        this.connectivity = this.context.getSystemService(ConnectivityManager.class);
        this.power = this.context.getSystemService(PowerManager.class);
        this.requireUnmetered = requireUnmetered;
        this.cancellation = cancellation;
    }
    void watch() throws IOException {
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_BATTERY_CHANGED);
            filter.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
            context.registerReceiver(receiver, filter);
            receiverRegistered = true;
            connectivity.registerDefaultNetworkCallback(callback);
            callbackRegistered = true;
            requireEligible(cancellation);
        } catch (RuntimeException e) {
            close();
            throw new IOException("Cannot monitor device conditions", e);
        }
    }
    private void checkTransition() { if (!eligible()) cancellation.cancel(); }
    @Override public boolean eligible() {
        if (!permit.get() || cancellation.isCancelled() || connectivity == null || power == null) return false;
        try {
            Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery == null) return false;
            int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            boolean charging = plugged != 0 && (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL);
            Network active = connectivity.getActiveNetwork();
            NetworkCapabilities caps = active == null ? null : connectivity.getNetworkCapabilities(active);
            if (caps == null) return false;
            DeviceState state = new DeviceState(true, permit.get(), power.isInteractive(), charging,
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
            return state.eligible(requireUnmetered);
        } catch (RuntimeException e) { return false; }
    }
    @Override public void requireEligible(Cancellation cancellation) throws IOException {
        cancellation.check();
        if (!eligible()) {
            this.cancellation.cancel();
            throw new IOException("Requires a running idle job, screen off, validated Wi-Fi and charging");
        }
    }
    Network requireNetwork(Cancellation cancellation) throws IOException {
        requireEligible(cancellation);
        Network network = connectivity.getActiveNetwork();
        if (network == null) throw new IOException("Wi-Fi disappeared");
        return network;
    }
    void requireNetwork(Network network, Cancellation cancellation) throws IOException {
        requireEligible(cancellation);
        if (!network.equals(connectivity.getActiveNetwork())) {
            this.cancellation.cancel();
            throw new IOException("Active Wi-Fi network changed");
        }
    }
    @Override public void close() {
        permit.set(false);
        cancellation.cancel();
        if (receiverRegistered) {
            receiverRegistered = false;
            try { context.unregisterReceiver(receiver); } catch (RuntimeException ignored) {}
        }
        if (callbackRegistered) {
            callbackRegistered = false;
            try { connectivity.unregisterNetworkCallback(callback); } catch (RuntimeException ignored) {}
        }
    }
}
