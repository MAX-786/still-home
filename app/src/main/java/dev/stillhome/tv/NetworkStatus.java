package dev.stillhome.tv;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;

/** How the TV is on the network right now: Wi-Fi or Ethernet, and whether the internet is actually reachable. */
final class NetworkStatus {
    static final int NONE = 0, WIFI = 1, ETHERNET = 2, OTHER = 3;

    interface Listener { void onNetwork(); }

    volatile int transport = NONE;
    volatile boolean online;
    private final ConnectivityManager manager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private ConnectivityManager.NetworkCallback callback;

    NetworkStatus(Context c) {
        manager = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        read();
    }

    /** "Wi-Fi", "Ethernet", "Wi-Fi · No internet", "Not connected". */
    String label() {
        String name = transport == WIFI ? "Wi-Fi" : transport == ETHERNET ? "Ethernet" : transport == OTHER ? "Connected" : "Not connected";
        return transport != NONE && !online ? name + " · No internet" : name;
    }

    void start(Listener l) {
        listener = l;
        read();
        if (manager == null || callback != null) return;
        callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { changed(); }
            @Override public void onLost(Network network) { changed(); }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) { changed(); }
        };
        try {
            manager.registerDefaultNetworkCallback(callback);
        } catch (RuntimeException e) {
            AppLog.w("network callback: " + e.getMessage());
            callback = null;
        }
    }

    void stop() {
        listener = null;
        if (manager != null && callback != null) {
            try {
                manager.unregisterNetworkCallback(callback);
            } catch (RuntimeException ignored) {
            }
        }
        callback = null;
    }

    private void changed() {
        main.post(() -> {
            int was = transport;
            boolean wasOnline = online;
            read();
            if ((was != transport || wasOnline != online) && listener != null) listener.onNetwork();
        });
    }

    private void read() {
        if (manager == null) return;
        try {
            Network active = manager.getActiveNetwork();
            NetworkCapabilities caps = active == null ? null : manager.getNetworkCapabilities(active);
            if (caps == null) {
                transport = NONE;
                online = false;
                return;
            }
            transport = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? ETHERNET
                    : caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? WIFI : OTHER;
            online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (RuntimeException e) {
            transport = NONE;
            online = false;
        }
    }
}
