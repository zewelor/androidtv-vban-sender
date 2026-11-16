package app.vbansender.app;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.BooleanSupplier;

/** Serializes requests to start and stop the audio backend. */
public final class AudioController implements AutoCloseable {
    /** Only receiver-route failures opt into automatic recovery; ADB I/O stays fatal. */
    public static final class NetworkUnavailableException extends Exception {
        public NetworkUnavailableException(String message) { super(message); }
    }
    public interface Backend {
        void start(BooleanSupplier stillEnabled) throws Exception;
        void stop() throws Exception;
        void check() throws Exception;
        default String failedCleanupToken() { return ""; }
    }

    public interface Requests { Request read(); }

    public static final class Request {
        final boolean enabled;
        final String offToken;
        final boolean cleanupPending;
        public Request(boolean enabled, String offToken, boolean cleanupPending) {
            this.enabled = enabled;
            this.offToken = offToken;
            this.cleanupPending = cleanupPending;
        }
    }

    public interface Listener {
        void onState(State state);
        void onError(Exception error);
        default void onNetworkWait(long retryMillis) { }
    }

    public enum State { STARTING, STOPPING, ON, OFF, WAITING_NETWORK }

    private final Object lock = new Object();
    private final Backend backend;
    private final Listener listener;
    private final Requests requests;
    private final java.util.function.LongSupplier nanoTime;
    private static final long INITIAL_NETWORK_RETRY_MILLIS = 10000;
    private static final long STABLE_NETWORK_NANOS = 30000000000L;
    private String observedOffToken = "";
    private boolean durablePending;
    private boolean cleanupIncomplete;
    private String recoveryToken;
    private boolean initialCleanup;
    private boolean firstWork = true;

    private void refreshLocked() {
        if (requests == null) return;
        Request current = requests.read();
        desired = current.enabled;
        boolean wasPending = durablePending;
        durablePending = !current.offToken.isEmpty();
        if (wasPending && !durablePending && !engineMayBeActive && !backendStateKnown) {
            // A preceding owner on the shared worker has acknowledged this token.
            engineFence = offFence;
        }
        cleanupIncomplete = current.cleanupPending;
        if (durablePending && !current.offToken.equals(observedOffToken)) {
            observedOffToken = current.offToken;
            offFence++;
            if (recoveryToken != null && !recoveryToken.equals(current.offToken)) recoveryToken = null;
        }
    }

    // An unfinished job may outlive its service. Replacement controllers must wait for it.
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "vban-audio-controller");
            thread.setDaemon(true);
            return thread;
        }
    });

    // Request and state fields are guarded by lock. Backend calls run on one worker only.
    private boolean desired;
    private boolean checkRequested;
    private boolean healthFailed;
    private boolean closed;
    private boolean workerScheduled;
    private boolean engineMayBeActive;
    private boolean backendStateKnown;
    private long requestVersion;
    private long offFence;
    private long engineFence;
    private long failedStartVersion = -1;
    private long failedStopFence = -1;
    private State state;
    private long networkRetryMillis = INITIAL_NETWORK_RETRY_MILLIS;
    private boolean healthWindowActive;
    private long healthySinceNanos;

    /** A timer or changed route may retry only a confirmed network wait. */
    public void retryNetwork() {
        synchronized (lock) {
            if (closed || state != State.WAITING_NETWORK) return;
            refreshLocked();
            if (!desired) return;
            requestVersion++;
            scheduleIfNeededLocked();
        }
    }

    public AudioController(Backend backend, Listener listener) {
        this(backend, listener, null);
    }

    public AudioController(Backend backend, Listener listener, Requests requests) {
        this(backend, listener, requests, new java.util.function.LongSupplier() {
            @Override public long getAsLong() { return System.nanoTime(); }
        });
    }

    AudioController(Backend backend, Listener listener, Requests requests,
            java.util.function.LongSupplier nanoTime) {
        if (backend == null || listener == null) {
            throw new NullPointerException("backend and listener are required");
        }
        this.backend = backend;
        this.listener = listener;
        this.requests = requests;
        this.nanoTime = nanoTime;
        if (requests != null) {
            Request initial = requests.read();
            initialCleanup = initial.cleanupPending;
            recoveryToken = initial.cleanupPending ? initial.offToken : null;
            refreshLocked();
        }
    }

    /** Dispatch is a wakeup; persisted intent is the source of truth. */
    public void reconcileSaved() {
        synchronized (lock) {
            if (closed) throw new IllegalStateException("AudioController is closed");
            refreshLocked();
            requestVersion++;
            checkRequested = desired && state == State.ON;
            scheduleIfNeededLocked();
        }
    }

    /** Updates the target state and returns without waiting for backend work. */
    public void request(boolean enabled) {
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("AudioController is closed");
            }
            requestVersion++;
            desired = enabled;
            checkRequested = enabled && state == State.ON;
            if (!enabled) {
                offFence++;
            }
            scheduleIfNeededLocked();
        }
    }

    /** Checks the detached engine on the same worker as Start and Stop. */
    public void check() {
        synchronized (lock) {
            // A network event/timer during cleanup must not interpret the
            // backend's internal cleanup token as a newly accepted user OFF.
            if (closed || state != State.ON) return;
            refreshLocked();
            if (!desired) return;
            checkRequested = true;
            scheduleIfNeededLocked();
        }
    }

    /** True only when the current OFF request has completed backend cleanup. */
    public boolean isOff() {
        synchronized (lock) {
            refreshLocked();
            return !closed && !desired && !durablePending && !cleanupIncomplete && state == State.OFF
                    && backendStateKnown && !engineMayBeActive;
        }
    }

    /** Stops accepting requests; pending Start and OFF cleanup finish on the shared worker. */
    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
        }
    }

    private void scheduleIfNeededLocked() {
        if (closed || workerScheduled || !needsWorkLocked()) {
            return;
        }
        workerScheduled = true;
        try {
            WORKER.execute(new Runnable() {
                @Override
                public void run() {
                    runWorker();
                }
            });
        } catch (RuntimeException error) {
            workerScheduled = false;
            throw error;
        }
    }

    private boolean needsWorkLocked() {
        if (desired) {
            if (cleanupRequiredLocked()) return offFence > failedStopFence;
            if (engineMayBeActive && state == State.ON && checkRequested) return true;
            return !engineMayBeActive && (!backendStateKnown || state != State.ON)
                    && requestVersion > failedStartVersion;
        }
        if (durablePending || engineMayBeActive || !backendStateKnown) {
            return offFence > failedStopFence;
        }
        return state != State.OFF;
    }

    private boolean cleanupRequiredLocked() {
        return durablePending || offFence > engineFence && (engineMayBeActive || !backendStateKnown);
    }

    private void runWorker() {
        try {
            reconcile();
        } finally {
            synchronized (lock) {
                workerScheduled = false;
                refreshLocked();
                scheduleIfNeededLocked();
            }
        }
    }

    private void reconcile() {
        boolean mayStart = true;
        while (true) {
            boolean target;
            boolean alreadyOff = false;
            boolean checking = false;
            long checkedVersion = 0;
            long startFence = 0;
            long stopFence = 0;
            synchronized (lock) {
                refreshLocked();
                if (firstWork) {
                    firstWork = false;
                    if (requests != null && !initialCleanup && cleanupIncomplete) recoveryToken = observedOffToken;
                }
                boolean cleanup = cleanupRequiredLocked() && offFence > failedStopFence;
                if (cleanupRequiredLocked() && !cleanup) return;
                if (closed && !cleanup) {
                    return;
                }
                target = desired && !closed;
                if (cleanup) {
                    target = false;
                    stopFence = offFence;
                } else if (target) {
                    if (engineMayBeActive) {
                        if (state != State.ON || !checkRequested) return;
                        checkRequested = false;
                        checking = true;
                        checkedVersion = requestVersion;
                    } else {
                        if (!mayStart || requestVersion <= failedStartVersion) return;
                        startFence = offFence;
                        engineFence = startFence;
                        engineMayBeActive = true;
                    }
                } else if (engineMayBeActive || !backendStateKnown) {
                    if (offFence <= failedStopFence) {
                        return;
                    }
                    stopFence = offFence;
                } else {
                    alreadyOff = true;
                }
            }

            if (checking) {
                checkBackend(checkedVersion);
                continue;
            }
            if (alreadyOff) {
                transition(State.OFF);
                return;
            }
            if (target) {
                StartResult result = start(startFence);
                if (result == StartResult.FAILED) {
                    mayStart = false;
                } else if (result == StartResult.STOP_FAILED) {
                    return;
                }
            } else if (!stopEngine(stopFence)) {
                return;
            }
        }
    }

    private void checkBackend(long version) {
        Exception failure = null;
        try { backend.check(); } catch (Exception error) { failure = error; }
        long fence;
        synchronized (lock) {
            refreshLocked();
            if (closed || !desired || durablePending || requestVersion != version) return;
            fence = offFence;
            if (failure != null) healthWindowActive = false;
            if (failure instanceof NetworkUnavailableException) {
                // Cleanup still runs on the serialized worker before any retry.
            } else if (failure != null) {
                healthFailed = true;
                notifyError(failure);
                return;
            } else {
                long now = nanoTime.getAsLong();
                if (!healthWindowActive) {
                    healthWindowActive = true;
                    healthySinceNanos = now;
                }
                if (now - healthySinceNanos >= STABLE_NETWORK_NANOS) {
                    networkRetryMillis = INITIAL_NETWORK_RETRY_MILLIS;
                }
                if (healthFailed) {
                    healthFailed = false;
                    try { listener.onState(State.ON); }
                    catch (RuntimeException error) { notifyError(error); }
                }
                return;
            }
        }
        if (stopEngine(fence)) waitForNetwork(fence);
    }

    private StartResult start(final long startFence) {
        transition(State.STARTING);
        if (!isCurrentStart(startFence)) {
            synchronized (lock) {
                engineMayBeActive = false;
            }
            return StartResult.CANCELLED;
        }

        Exception startError = null;
        try {
            backend.start(new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                    return isCurrentStart(startFence);
                }
            });
        } catch (Exception error) {
            startError = error;
        }

        if (startError == null && markOnIfCurrent(startFence)) {
            return StartResult.STARTED;
        }

        transition(State.STOPPING);
        if (startError != null && !(startError instanceof NetworkUnavailableException)) {
            notifyError(startError);
        }
        long stopFence;
        synchronized (lock) {
            stopFence = offFence;
        }
        if (!stopEngine(stopFence)) {
            return StartResult.STOP_FAILED;
        }

        if (startError instanceof NetworkUnavailableException) waitForNetwork(startFence);

        synchronized (lock) {
            boolean laterOnAfterOff = desired && !closed && offFence != startFence;
            if (startError != null && !laterOnAfterOff
                    && !(startError instanceof NetworkUnavailableException)) {
                // Surface one failed attempt without spinning; another explicit request retries.
                failedStartVersion = requestVersion;
            }
            return laterOnAfterOff ? StartResult.CANCELLED : StartResult.FAILED;
        }
    }

    private void waitForNetwork(long fence) {
        synchronized (lock) {
            refreshLocked();
            if (closed || !desired || durablePending || cleanupIncomplete
                    || offFence != fence || engineMayBeActive) return;
            failedStartVersion = requestVersion;
            transition(State.WAITING_NETWORK);
            long delay = networkRetryMillis;
            networkRetryMillis = Math.min(300000, networkRetryMillis * 2);
            try { listener.onNetworkWait(delay); }
            catch (RuntimeException error) { notifyError(error); }
        }
    }

    private boolean stopEngine(long fenceAtStart) {
        transition(State.STOPPING);
        try {
            backend.stop();
        } catch (Exception error) {
            synchronized (lock) {
                refreshLocked();
                failedStopFence = !backend.failedCleanupToken().isEmpty()
                        && backend.failedCleanupToken().equals(observedOffToken)
                        ? offFence : fenceAtStart;
                engineMayBeActive = true;
                backendStateKnown = false;
            }
            notifyError(error);
            synchronized (lock) {
                // A new OFF request during the failed call is an explicit cleanup retry.
                return offFence > failedStopFence;
            }
        }

        synchronized (lock) {
            refreshLocked();
            engineMayBeActive = false;
            backendStateKnown = true;
            failedStopFence = -1;
            if (durablePending) return true;
            if (recoveryToken != null) {
                failedStartVersion = requestVersion;
                recoveryToken = null;
                notifyError(new IllegalStateException("Interrupted cleanup recovered. Turn OFF, then ON to retry."));
            }
        }
        transition(State.OFF);
        return true;
    }

    private boolean isCurrentStart(long startFence) {
        synchronized (lock) {
            refreshLocked();
            return !closed && desired && !durablePending && offFence == startFence;
        }
    }

    private boolean markOnIfCurrent(long startFence) {
        boolean changed;
        synchronized (lock) {
            refreshLocked();
            if (closed || !desired || durablePending || offFence != startFence) {
                return false;
            }
            changed = state != State.ON;
            state = State.ON;
            healthFailed = false;
            healthWindowActive = true;
            healthySinceNanos = nanoTime.getAsLong();
            backendStateKnown = true;
        }
        if (changed) {
            try {
                listener.onState(State.ON);
            } catch (RuntimeException listenerError) {
                notifyError(listenerError);
            }
        }
        return true;
    }

    private void transition(State next) {
        boolean changed;
        synchronized (lock) {
            changed = state != next;
            state = next;
            if (next != State.ON) healthWindowActive = false;
            if (next == State.OFF && !desired) networkRetryMillis = INITIAL_NETWORK_RETRY_MILLIS;
        }
        if (changed) {
            try {
                listener.onState(next);
            } catch (RuntimeException listenerError) {
                notifyError(listenerError);
            }
        }
    }

    private void notifyError(Exception error) {
        try {
            listener.onError(error);
        } catch (RuntimeException listenerError) {
            // Listener failures must not strand the serialized backend worker.
            listenerError.printStackTrace(System.err);
        }
    }

    private enum StartResult { STARTED, CANCELLED, FAILED, STOP_FAILED }
}
