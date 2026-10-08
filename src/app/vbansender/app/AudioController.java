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
    }

    public interface Listener {
        void onState(State state);
        void onError(Exception error);
    }

    public enum State { STARTING, STOPPING, ON, OFF }

    private final Object lock = new Object();
    private final Backend backend;
    private final Listener listener;
    private final ExecutorService executor;

    // Request and state fields are guarded by lock. Backend calls run on one worker only.
    private boolean desired;
    private boolean closed;
    private boolean workerScheduled;
    private boolean engineMayBeActive;
    private boolean backendStateKnown;
    private long requestVersion;
    private long offFence;
    private long failedStartVersion = -1;
    private long failedStopVersion = -1;
    private State state;

    public AudioController(Backend backend, Listener listener) {
        if (backend == null || listener == null) {
            throw new NullPointerException("backend and listener are required");
        }
        this.backend = backend;
        this.listener = listener;
        this.executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, "vban-audio-controller");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    /** Updates the target state and returns without waiting for backend work. */
    public void request(boolean enabled) {
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("AudioController is closed");
            }
            requestVersion++;
            desired = enabled;
            if (!enabled) {
                offFence++;
            }
            scheduleIfNeededLocked();
        }
    }

    /** Stops accepting requests and lets already-running backend work finish. */
    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            // A start that has not completed must observe close as a cancellation fence.
            offFence++;
        }
        executor.shutdown();
    }

    private void scheduleIfNeededLocked() {
        if (closed || workerScheduled || !needsWorkLocked()) {
            return;
        }
        workerScheduled = true;
        try {
            executor.execute(new Runnable() {
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
            return !engineMayBeActive && (!backendStateKnown || state != State.ON)
                    && requestVersion > failedStartVersion;
        }
        if (engineMayBeActive || !backendStateKnown) {
            return requestVersion > failedStopVersion;
        }
        return state != State.OFF;
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
            long startFence = 0;
            long stopVersion = 0;
            synchronized (lock) {
                if (closed) {
                    return;
                }
                target = desired;
                if (target) {
                    if (engineMayBeActive || !mayStart
                            || requestVersion <= failedStartVersion) {
                        return;
                    }
                    startFence = offFence;
                    engineMayBeActive = true;
                } else if (engineMayBeActive || !backendStateKnown) {
                    if (requestVersion <= failedStopVersion) {
                        return;
                    }
                    stopVersion = requestVersion;
                } else {
                    alreadyOff = true;
                }
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
            } else if (!stopEngine(stopVersion)) {
                return;
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
        long stopVersion;
        synchronized (lock) {
            stopVersion = requestVersion;
        }
        if (!stopEngine(stopVersion)) {
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

    private boolean stopEngine(long requestAtStart) {
        transition(State.STOPPING);
        try {
            backend.stop();
        } catch (Exception error) {
            synchronized (lock) {
                failedStopVersion = requestAtStart;
                engineMayBeActive = true;
                backendStateKnown = false;
            }
            notifyError(error);
            synchronized (lock) {
                // A new OFF request during the failed call is an explicit cleanup retry.
                return !closed && !desired && requestVersion > requestAtStart;
            }
        }

        synchronized (lock) {
            engineMayBeActive = false;
            backendStateKnown = true;
            failedStopVersion = -1;
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
