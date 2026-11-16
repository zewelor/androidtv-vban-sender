package android.system;

public final class Os {
    public static String strerror(int errno) {
        switch (errno) {
            case OsConstants.ENETUNREACH: return "Network is unreachable";
            case OsConstants.EHOSTUNREACH: return "No route to host";
            case OsConstants.ENETDOWN: return "Network is down";
            default: return "Permission denied";
        }
    }
}
