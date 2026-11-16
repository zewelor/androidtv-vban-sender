package app.vbansender.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.RouteInfo;
import android.os.Build;
import android.os.Handler;
import java.net.InetAddress;
import java.util.Collections;
import java.util.List;

/** Tracks default-network route changes and checks the live route on backend requests. */
final class NetworkMonitor implements AutoCloseable {
    private static final long CHANGE_QUIET_PERIOD_MS = 250L;

    private final ConnectivityManager connectivity;
    private final Handler handler;
    private final Runnable onChanged;
    private final Runnable notifyChanged;
    private final ConnectivityManager.NetworkCallback callback;
    private volatile Snapshot cached;
    private volatile boolean closed;

    NetworkMonitor(Context context, Handler handler, Runnable onChanged) {
        connectivity = context.getSystemService(ConnectivityManager.class);
        if (connectivity == null) {
            throw new IllegalStateException("ConnectivityManager is unavailable");
        }
        if (handler == null || onChanged == null) {
            throw new NullPointerException("handler and onChanged are required");
        }
        this.handler = handler;
        this.onChanged = onChanged;
        notifyChanged = new Runnable() {
            @Override public void run() {
                synchronized (NetworkMonitor.this) {
                    if (!closed) NetworkMonitor.this.onChanged.run();
                }
            }
        };
        callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                NetworkMonitor.this.onAvailable(network);
            }

            @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
                NetworkMonitor.this.onLinkPropertiesChanged(network, properties);
            }

            @Override public void onLost(Network network) {
                NetworkMonitor.this.onLost(network);
            }
        };

        Network initialNetwork = connectivity.getActiveNetwork();
        LinkProperties initialProperties = initialNetwork == null
                ? null : connectivity.getLinkProperties(initialNetwork);
        cached = new Snapshot(initialNetwork, initialProperties);
        connectivity.registerDefaultNetworkCallback(callback, handler);
    }

    /** Call from the backend worker; this reads the current default route for each attempt. */
    boolean hasRoute(InetAddress destination) {
        if (destination == null) {
            throw new NullPointerException("destination is required");
        }
        if (closed) return false;
        Network network = connectivity.getActiveNetwork();
        if (closed || network == null) return false;
        LinkProperties properties = connectivity.getLinkProperties(network);
        if (closed || properties == null) return false;
        int bestPrefix = -1;
        boolean bestRoutesAreUnicast = false;
        for (RouteInfo route : properties.getRoutes()) {
            if (!route.matches(destination)) continue;
            int prefix = route.getDestination().getPrefixLength();
            boolean unicast = isUsableUnicastRoute(route);
            if (prefix > bestPrefix) {
                bestPrefix = prefix;
                bestRoutesAreUnicast = unicast;
            } else if (prefix == bestPrefix && !unicast) {
                bestRoutesAreUnicast = false;
            }
        }
        return bestPrefix >= 0 && bestRoutesAreUnicast;
    }

    private synchronized void onAvailable(Network network) {
        if (closed) return;
        Snapshot previous = cached;
        if (network.equals(previous.network)) return;
        cached = new Snapshot(network, null);
        if (!routes(previous.properties).isEmpty()) scheduleChanged();
    }

    private synchronized void onLinkPropertiesChanged(Network network, LinkProperties properties) {
        if (closed) return;
        Snapshot previous = cached;
        if (!network.equals(previous.network)) return;
        cached = new Snapshot(network, properties);
        if (!routes(previous.properties).equals(routes(properties))) scheduleChanged();
    }

    private synchronized void onLost(Network network) {
        if (closed) return;
        Snapshot previous = cached;
        if (!network.equals(previous.network)) return;
        cached = Snapshot.empty();
        if (!routes(previous.properties).isEmpty()) scheduleChanged();
    }

    private void scheduleChanged() {
        handler.removeCallbacks(notifyChanged);
        handler.postDelayed(notifyChanged, CHANGE_QUIET_PERIOD_MS);
    }

    private static List<RouteInfo> routes(LinkProperties properties) {
        return properties == null ? Collections.<RouteInfo>emptyList() : properties.getRoutes();
    }

    private static boolean isUsableUnicastRoute(RouteInfo route) {
        // RouteInfo route types became public in API 33. With this app's targetSdk 31,
        // Android 12 and 12L expose only unicast routes through LinkProperties.getRoutes().
        return Build.VERSION.SDK_INT < 33 || route.getType() == RouteInfo.RTN_UNICAST;
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        cached = Snapshot.empty();
        handler.removeCallbacks(notifyChanged);
        connectivity.unregisterNetworkCallback(callback);
    }

    private static final class Snapshot {
        final Network network;
        final LinkProperties properties;

        Snapshot(Network network, LinkProperties properties) {
            this.network = network;
            this.properties = properties;
        }

        static Snapshot empty() { return new Snapshot(null, null); }
    }
}
