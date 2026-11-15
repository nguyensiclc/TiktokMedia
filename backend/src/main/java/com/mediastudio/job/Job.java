package com.mediastudio.job;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class Job {
    public enum Status { RUNNING, DONE, FAILED }

    public String id;
    /** storyboard, flow, canva, voice, render, agent */
    public String type;
    public String title;
    public String projectId;
    public String sceneId;
    public volatile Status status = Status.RUNNING;
    public String error;
    public Instant createdAt = Instant.now();
    public Instant finishedAt;
    /** Streamed over SSE; not serialized in list/get to avoid racing with appends. */
    @JsonIgnore
    public final List<String> logs = new ArrayList<>();
}
