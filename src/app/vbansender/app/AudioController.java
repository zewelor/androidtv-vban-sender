package app.vbansender.app;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.BooleanSupplier;

/** Serializes requests to start and stop the audio backend. */
public final class AudioController implements AutoCloseable {
    public interface Backend {
        void start(BooleanSupplier stillEnabled) throws Exception;
        void stop() throws Exception;
        void check() throws Exception;
    }

    public interface Listener {
        void onState(State state);
        void onError(Exception error);
    }

    public enum State { STARTING, STOPPING, ON, OFF }

    private final Object lock = new Object();
    private final Backend backend;
    private final Listener listener;
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

    public AudioController(Backend backend, Listener listener) {
        if (backend == null || listener == null) {
            throw new NullPointerException("backend and listener are required");
        }
        this.backend = backend;
        this.listener = listener;
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
            if (closed || !desired || state != State.ON) return;
            checkRequested = true;
            scheduleIfNeededLocked();
        }
    }

    /** True only when the current OFF request has completed backend cleanup. */
    public boolean isOff() {
        synchronized (lock) {
            return !closed && !desired && state == State.OFF
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
        if (engineMayBeActive || !backendStateKnown) {
            return offFence > failedStopFence;
        }
        return state != State.OFF;
    }

    private boolean cleanupRequiredLocked() {
        return offFence > engineFence && (engineMayBeActive || !backendStateKnown);
    }

    private void runWorker() {
        try {
            reconcile();
        } finally {
            synchronized (lock) {
                workerScheduled = false;
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
                boolean cleanup = cleanupRequiredLocked() && offFence > failedStopFence;
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
        synchronized (lock) {
            if (closed || !desired || requestVersion != version) return;
            if (failure != null) {
                healthFailed = true;
                notifyError(failure);
            } else if (healthFailed) {
                healthFailed = false;
                try { listener.onState(State.ON); }
                catch (RuntimeException error) { notifyError(error); }
            }
        }
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
        if (startError != null) {
            notifyError(startError);
        }
        long stopFence;
        synchronized (lock) {
            stopFence = offFence;
        }
        if (!stopEngine(stopFence)) {
            return StartResult.STOP_FAILED;
        }

        synchronized (lock) {
            boolean laterOnAfterOff = desired && !closed && offFence != startFence;
            if (startError != null && !laterOnAfterOff) {
                // Surface one failed attempt without spinning; another explicit request retries.
                failedStartVersion = requestVersion;
            }
            return laterOnAfterOff ? StartResult.CANCELLED : StartResult.FAILED;
        }
    }

    private boolean stopEngine(long fenceAtStart) {
        transition(State.STOPPING);
        try {
            backend.stop();
        } catch (Exception error) {
            synchronized (lock) {
                failedStopFence = fenceAtStart;
                engineMayBeActive = true;
                backendStateKnown = false;
            }
            notifyError(error);
            synchronized (lock) {
                // A new OFF request during the failed call is an explicit cleanup retry.
                return offFence > fenceAtStart;
            }
        }

        synchronized (lock) {
            engineMayBeActive = false;
            backendStateKnown = true;
            failedStopFence = -1;
        }
        transition(State.OFF);
        return true;
    }

    private boolean isCurrentStart(long startFence) {
        synchronized (lock) {
            return !closed && desired && offFence == startFence;
        }
    }

    private boolean markOnIfCurrent(long startFence) {
        boolean changed;
        synchronized (lock) {
            if (closed || !desired || offFence != startFence) {
                return false;
            }
            changed = state != State.ON;
            state = State.ON;
            healthFailed = false;
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
