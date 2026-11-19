package com.mediastudio.project;

import java.util.UUID;

/** A user-supplied reference image for a character (or object / mascot) that must look the same in every scene. */
public class CharacterRef {
    public String id = UUID.randomUUID().toString().substring(0, 8);
    /** Name used in the story and prompts, e.g. "Mai". */
    public String name = "";
    /** Optional notes: role, personality, details the image doesn't show. */
    public String description = "";
    /** File name inside assets/ (ref-<id>.<ext>). */
    public String file;
    /** Canva media id once uploaded (reused for imageReferences). */
    public String canvaMediaId;
    /** Made by the script step (the main figures it found); its picture is a Flow portrait. Dropped on a new script. */
    public boolean auto;
}
