package android.system;

public final class ErrnoException extends Exception {
    public final int errno;
    public ErrnoException(String function, int errno) {
        super(function + " failed: errno=" + errno);
        this.errno = errno;
    }
}
