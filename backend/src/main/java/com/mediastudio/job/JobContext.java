package com.mediastudio.job;

/** Handed to background work so it can stream log lines to the UI. */
public interface JobContext {
    Job job();

    void log(String line);

    /** Registers a process so the job can be cancelled. */
    void attach(Process process);

    /** Registers an action (e.g. closing an HTTP stream) to run if the job is cancelled. */
    void onCancel(Runnable action);

    boolean cancelled();
}
