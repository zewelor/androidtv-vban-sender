package app.vbansender.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.RouteInfo;
import android.os.Build;
import android.os.Handler;
import java.net.InetAddress;
import java.util.Arrays;

/** Exercises cached default-route state with controllable Android network callbacks. */
public final class NetworkMonitorHarness {
    public static void main(String[] args) throws Exception {
        disconnectedStartupAndLanWithoutInternet();
        initialSnapshotAndDefaultSwitch();
        coalescesMeaningfulBurst();
        liveQueryRecoversFromMissedCallbacks();
        closeIgnoresQueuedCallbacks();
        routeTypesAtApi33();
        System.out.println("NETWORK_MONITOR_PASS");
    }

    private static void disconnectedStartupAndLanWithoutInternet() throws Exception {
        Build.VERSION.SDK_INT = 31;
        Network target = network(1);
        ConnectivityManager manager = new ConnectivityManager();
        Handler handler = new Handler();
        Counter changed = new Counter();
        NetworkMonitor monitor = new NetworkMonitor(new Context(manager), handler, changed);

        require(manager.events.equals(Arrays.asList("active", "register")),
                "initial active network was not captured before callback registration: " + manager.events);
        require(!monitor.hasRoute(ip("192.168.50.20")), "disconnected startup reported a route");

        manager.emitAvailable(target);
        require(!monitor.hasRoute(ip("192.168.50.20")), "onAvailable alone created a route");
        handler.runAll();
        require(!monitor.hasRoute(ip("192.168.50.20")), "network without link properties reported a route");

        LinkProperties localLan = properties(route("192.168.50.0", 24, RouteInfo.RTN_UNICAST));
        manager.emitLinkProperties(target, localLan);
        int syncQueries = manager.activeQueries + manager.linkPropertiesQueries;
        handler.runAll();
        require(syncQueries == manager.activeQueries + manager.linkPropertiesQueries,
                "network callback performed a synchronous connectivity query");
        require(monitor.hasRoute(ip("192.168.50.20")),
                "local LAN route was rejected without internet validation");
        require(!monitor.hasRoute(ip("192.168.51.20")), "route matched an address outside its prefix");
        require(changed.count == 1, "initial route update did not produce one meaningful notification");

        LinkProperties sameRoutes = properties(route("192.168.50.0", 24, RouteInfo.RTN_UNICAST));
        sameRoutes.metadata = "different non-route link property";
        manager.emitLinkProperties(target, sameRoutes);
        handler.runAll();
        require(changed.count == 1, "non-route link property change notified the listener");

        manager.emitLinkProperties(target,
                properties(route("192.168.60.0", 24, RouteInfo.RTN_UNICAST)));
        handler.runAll();
        require(!monitor.hasRoute(ip("192.168.50.20")) && changed.count == 2,
                "changed route set was not cached and reported");
        monitor.close();
    }

    private static void initialSnapshotAndDefaultSwitch() throws Exception {
        Build.VERSION.SDK_INT = 31;
        Network wifi = network(10);
        Network cellular = network(11);
        ConnectivityManager manager = new ConnectivityManager();
        manager.active = wifi;
        manager.properties.put(wifi, properties(route("192.168.50.0", 24, RouteInfo.RTN_UNICAST)));
        Handler handler = new Handler();
        Counter changed = new Counter();
        NetworkMonitor monitor = new NetworkMonitor(new Context(manager), handler, changed);
        InetAddress destination = ip("192.168.50.20");
        require(manager.events.equals(Arrays.asList("active", "properties:" + wifi.id, "register")),
                "initial snapshot was not taken before registration: " + manager.events);
        require(monitor.hasRoute(destination), "initial default LinkProperties were not read live");

        manager.emitAvailable(wifi);
        handler.runAll();
        require(monitor.hasRoute(destination), "registration callback for same default erased initial routes");
        require(changed.count == 0, "same-network availability generated a route notification");

        manager.emitAvailable(cellular);
        handler.runAll();
        require(!monitor.hasRoute(destination), "new default network retained old network routes");
        int afterSwitch = changed.count;

        manager.emitLost(wifi);
        handler.runAll();
        require(!monitor.hasRoute(destination), "stale onLost changed the new default network state");

        manager.emitLinkProperties(cellular,
                properties(route("192.168.50.0", 24, RouteInfo.RTN_UNICAST)));
        handler.runAll();
        require(monitor.hasRoute(destination), "new default route was not adopted");
        manager.emitLost(wifi);
        handler.runAll();
        require(monitor.hasRoute(destination), "late stale onLost cleared current routes");
        require(changed.count == afterSwitch + 1,
                "new default route transition notification count was unexpected: " + changed.count);

        manager.emitLost(cellular);
        handler.runAll();
        require(!monitor.hasRoute(destination), "onLost did not clear the current default route");
        monitor.close();
    }

    private static void closeIgnoresQueuedCallbacks() throws Exception {
        Build.VERSION.SDK_INT = 31;
        Network network = network(20);
        ConnectivityManager manager = new ConnectivityManager();
        manager.active = network;
        manager.properties.put(network, properties(route("192.168.50.0", 24, RouteInfo.RTN_UNICAST)));
        Handler handler = new Handler();
        Counter changed = new Counter();
        NetworkMonitor monitor = new NetworkMonitor(new Context(manager), handler, changed);
        InetAddress destination = ip("192.168.50.20");
        require(monitor.hasRoute(destination), "close test initial route missing");

        manager.emitLinkProperties(network,
                properties(route("192.168.60.0", 24, RouteInfo.RTN_UNICAST)));
        handler.runCurrent();
        int beforeClose = changed.count;
        require(handler.pendingCount() == 1, "route change did not queue a delayed notification");
        monitor.close();
        monitor.close();
        require(handler.pendingCount() == 0, "close did not cancel the delayed notification");
        handler.runAll();
        require(!monitor.hasRoute(destination), "closed monitor continued exposing cached routes");
        require(changed.count == beforeClose, "queued callback notified after close");
        require(manager.unregisterCalls == 1, "close did not unregister exactly once");
    }

    private static void liveQueryRecoversFromMissedCallbacks() throws Exception {
        Build.VERSION.SDK_INT = 31;
        ConnectivityManager manager = new ConnectivityManager();
        Handler handler = new Handler();
        NetworkMonitor monitor = new NetworkMonitor(new Context(manager), handler, new Counter());
        Network wifi = network(25);
        manager.active = wifi;
        manager.properties.put(wifi, properties(route("192.168.50.0", 24, RouteInfo.RTN_UNICAST)));
        int queriesBefore = manager.activeQueries + manager.linkPropertiesQueries;
        require(monitor.hasRoute(ip("192.168.50.20")),
                "live route query did not recover when the availability callback was missed");
        require(manager.activeQueries + manager.linkPropertiesQueries == queriesBefore + 2,
                "hasRoute did not query current network and LinkProperties");

        manager.properties.put(wifi, properties(route("192.168.60.0", 24, RouteInfo.RTN_UNICAST)));
        require(!monitor.hasRoute(ip("192.168.50.20")),
                "live route query retained stale routes when the link-properties callback was missed");
        monitor.close();
    }

    private static void coalescesMeaningfulBurst() throws Exception {
        Build.VERSION.SDK_INT = 31;
        Network wifi = network(15);
        Network cellular = network(16);
        ConnectivityManager manager = new ConnectivityManager();
        manager.active = wifi;
        manager.properties.put(wifi, properties(route("192.168.50.0", 24, RouteInfo.RTN_UNICAST)));
        Handler handler = new Handler();
        Counter changed = new Counter();
        NetworkMonitor monitor = new NetworkMonitor(new Context(manager), handler, changed);

        manager.emitAvailable(cellular);
        handler.runCurrent();
        require(!monitor.hasRoute(ip("192.168.50.20")),
                "cached route state did not update immediately on default-network change");

        handler.advanceBy(100);
        manager.emitLinkProperties(cellular,
                properties(route("192.168.60.0", 24, RouteInfo.RTN_UNICAST)));
        handler.runCurrent();
        require(monitor.hasRoute(ip("192.168.60.20")),
                "live hasRoute query did not see the latest route before notification");
        require(!monitor.hasRoute(ip("192.168.50.20")),
                "live hasRoute query retained the previous default network route");

        handler.advanceBy(200);
        manager.emitLinkProperties(cellular,
                properties(route("192.168.70.0", 24, RouteInfo.RTN_UNICAST)));
        handler.runCurrent();
        require(monitor.hasRoute(ip("192.168.70.20")),
                "latest route was not visible immediately after its callback");

        handler.advanceBy(100);
        manager.emitLinkProperties(cellular,
                properties(route("192.168.70.0", 24, RouteInfo.RTN_UNICAST)));
        handler.runCurrent();
        handler.advanceBy(149);
        require(changed.count == 0,
                "route burst emitted callbacks before the quiet period: " + changed.count);
        handler.advanceBy(1);
        require(changed.count == 1,
                "meaningful route burst did not coalesce into one notification: " + changed.count);
        monitor.close();
    }

    private static void routeTypesAtApi33() throws Exception {
        Build.VERSION.SDK_INT = 33;
        Network network = network(30);
        ConnectivityManager manager = new ConnectivityManager();
        manager.active = network;
        manager.properties.put(network, properties(
                route("192.168.50.0", 24, RouteInfo.RTN_UNICAST),
                route("0.0.0.0", 0, RouteInfo.RTN_UNREACHABLE)));
        NetworkMonitor monitor = new NetworkMonitor(new Context(manager), new Handler(), new Counter());
        require(monitor.hasRoute(ip("192.168.50.20")),
                "specific unicast route was rejected in favor of a broader unreachable route");

        manager.properties.put(network, properties(
                route("0.0.0.0", 0, RouteInfo.RTN_UNICAST),
                route("192.168.50.20", 32, RouteInfo.RTN_UNREACHABLE)));
        manager.emitLinkProperties(network, manager.properties.get(network));
        manager.handler.runAll();
        require(!monitor.hasRoute(ip("192.168.50.20")),
                "more-specific unreachable route did not shadow the default unicast route");

        manager.properties.put(network, properties(
                route("192.168.50.0", 24, RouteInfo.RTN_UNREACHABLE)));
        manager.emitLinkProperties(network, manager.properties.get(network));
        manager.handler.runAll();
        require(!monitor.hasRoute(ip("192.168.50.20")), "unreachable route was treated as usable");
        monitor.close();
    }

    private static Network network(int id) { return new Network(id); }

    private static InetAddress ip(String value) throws Exception {
        String[] octets = value.split("\\.");
        byte[] bytes = new byte[4];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(octets[i]);
        return InetAddress.getByAddress(bytes);
    }

    private static RouteInfo route(String address, int prefix, int type) throws Exception {
        return new RouteInfo(ip(address), prefix, type);
    }

    private static LinkProperties properties(RouteInfo... routes) {
        LinkProperties properties = new LinkProperties();
        for (RouteInfo route : routes) properties.addRoute(route);
        return properties;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Counter implements Runnable {
        int count;
        @Override public void run() { count++; }
    }
}
