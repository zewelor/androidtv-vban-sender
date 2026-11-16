#!/usr/bin/env python3
"""NetworkMonitor behavior with controllable Android connectivity callbacks."""
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "build/e2e/network-monitor"

STUBS = {
    "android/content/Context.java": """
package android.content;
public class Context {
 private final android.net.ConnectivityManager manager;
 public Context(android.net.ConnectivityManager manager){this.manager=manager;}
 public <T> T getSystemService(Class<T> type){return type.cast(manager);}
}
""",
    "android/net/Network.java": """
package android.net;
public final class Network {
 public final int id;
 public Network(int id){this.id=id;}
 @Override public boolean equals(Object other){return other instanceof Network && ((Network)other).id==id;}
 @Override public int hashCode(){return id;}
 @Override public String toString(){return "Network("+id+")";}
}
""",
    "android/net/RouteInfo.java": """
package android.net;
import java.net.InetAddress;
import java.util.Arrays;
public final class RouteInfo {
 public static final int RTN_UNICAST=1, RTN_UNREACHABLE=7;
 private final InetAddress address; private final int prefix,type;
 public RouteInfo(InetAddress address,int prefix,int type){this.address=address;this.prefix=prefix;this.type=type;}
 public IpPrefix getDestination(){return new IpPrefix(prefix);}
 public int getType(){return type;}
 public boolean matches(InetAddress destination){
  byte[] route=address.getAddress(), target=destination.getAddress(); if(route.length!=target.length)return false;
  for(int i=0;i<route.length;i++){int bits=Math.min(8,Math.max(0,prefix-i*8));int mask=bits==0?0:(0xff<<(8-bits))&0xff;
   if((route[i]&mask)!=(target[i]&mask))return false;}
  return true;
 }
 @Override public boolean equals(Object other){if(!(other instanceof RouteInfo))return false;RouteInfo r=(RouteInfo)other;
  return prefix==r.prefix&&type==r.type&&address.equals(r.address);}
 @Override public int hashCode(){return Arrays.hashCode(address.getAddress())*31+prefix*7+type;}
}
""",
    "android/net/IpPrefix.java": "package android.net; public final class IpPrefix { private final int prefix; public IpPrefix(int prefix){this.prefix=prefix;} public int getPrefixLength(){return prefix;} }",
    "android/net/LinkProperties.java": """
package android.net;
import android.os.Build;
import java.util.*;
public final class LinkProperties {
 private final List<RouteInfo> routes=new ArrayList<>();
 public String metadata="";
 public void addRoute(RouteInfo route){routes.add(route);}
 public List<RouteInfo> getRoutes(){
  List<RouteInfo> result=new ArrayList<>();
  // API 31/32 expose only unicast routes to apps targeting Android S or below.
  for(RouteInfo route:routes) if(Build.VERSION.SDK_INT>=33||route.getType()==RouteInfo.RTN_UNICAST)result.add(route);
  return result;
 }
}
""",
    "android/net/ConnectivityManager.java": """
package android.net;
import android.os.Handler;
import java.util.*;
public final class ConnectivityManager {
 public abstract static class NetworkCallback {
  public void onAvailable(Network network){}
  public void onLinkPropertiesChanged(Network network,LinkProperties properties){}
  public void onLost(Network network){}
 }
 public Network active; public final Map<Network,LinkProperties> properties=new HashMap<>();
 public final List<String> events=new ArrayList<>(); public int activeQueries,linkPropertiesQueries,unregisterCalls;
 public NetworkCallback callback; public Handler handler;
 public Network getActiveNetwork(){events.add("active");activeQueries++;return active;}
 public LinkProperties getLinkProperties(Network network){events.add("properties:"+network.id);linkPropertiesQueries++;return properties.get(network);}
 public void registerDefaultNetworkCallback(NetworkCallback callback,Handler handler){
  events.add("register");this.callback=callback;this.handler=handler;
 }
 public void unregisterNetworkCallback(NetworkCallback callback){if(this.callback!=callback)throw new IllegalArgumentException();unregisterCalls++;}
 public void emitAvailable(final Network network){active=network;handler.post(new Runnable(){public void run(){callback.onAvailable(network);}});}
 public void emitLinkProperties(final Network network,final LinkProperties properties){this.properties.put(network,properties);handler.post(new Runnable(){public void run(){callback.onLinkPropertiesChanged(network,properties);}});}
 public void emitLost(final Network network){if(network.equals(active))active=null;handler.post(new Runnable(){public void run(){callback.onLost(network);}});}
}
""",
    "android/os/Handler.java": """
package android.os;
import java.util.*;
public final class Handler {
 private final List<Entry> queue=new ArrayList<>(); private long now,sequence;
 public boolean post(Runnable runnable){return postDelayed(runnable,0);}
 public boolean postDelayed(Runnable runnable,long delayMillis){queue.add(new Entry(runnable,now+Math.max(0,delayMillis),sequence++));return true;}
 public void removeCallbacks(Runnable runnable){for(Iterator<Entry> i=queue.iterator();i.hasNext();)if(i.next().runnable==runnable)i.remove();}
 public int pendingCount(){return queue.size();}
 public void runCurrent(){runUntil(now);}
 public void advanceBy(long millis){runUntil(now+millis);}
 public void runAll(){while(!queue.isEmpty())runUntil(next().when);}
 private void runUntil(long target){for(;;){Entry entry=next();if(entry==null||entry.when>target){now=target;return;}
  queue.remove(entry);now=entry.when;entry.runnable.run();}}
 private Entry next(){Entry best=null;for(Entry entry:queue)if(best==null||entry.when<best.when||(entry.when==best.when&&entry.sequence<best.sequence))best=entry;return best;}
 private static final class Entry {final Runnable runnable;final long when,sequence;
  Entry(Runnable runnable,long when,long sequence){this.runnable=runnable;this.when=when;this.sequence=sequence;}}
}
""",
    "android/os/Build.java": """
package android.os;
public final class Build {
 public static final class VERSION {public static int SDK_INT=31;}
}
""",
}


def main():
    source = OUT / "adapters"
    classes = OUT / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    for name, contents in STUBS.items():
        path = source / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(contents)
    try:
        subprocess.run(
            ["javac", "--release", "8", "-d", str(classes)]
            + [str(p) for p in source.rglob("*.java")]
            + [str(ROOT / "src/app/vbansender/app/NetworkMonitor.java"),
               str(ROOT / "tests/NetworkMonitorHarness.java")],
            check=True,
        )
        result = subprocess.run(
            ["java", "-cp", str(classes), "app.vbansender.app.NetworkMonitorHarness"],
            capture_output=True, text=True, timeout=15,
        )
    except subprocess.CalledProcessError as error:
        report = {"result": "FAIL", "phase": "compile", "returncode": error.returncode,
                  "scope": "Production monitor behavior with controlled Android connectivity adapters"}
        (OUT / "report.json").write_text(json.dumps(report, indent=2) + "\n")
        raise
    report = {"result": "PASS" if result.returncode == 0 else "FAIL", "stdout": result.stdout,
              "stderr": result.stderr,
              "scope": "Production monitor behavior with controlled Android connectivity adapters; no device proof"}
    (OUT / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    assert result.returncode == 0, report
    print("PASS: startup snapshot, LAN route, coalesced route bursts, stale callbacks, close, unreachable routes")


if __name__ == "__main__":
    main()
