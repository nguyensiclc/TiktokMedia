package com.mediastudio.project;

import com.mediastudio.render.FfmpegService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {
    private final ProjectService projects;
    private final FfmpegService ffmpeg;

    public ProjectController(ProjectService projects, FfmpegService ffmpeg) {
        this.projects = projects;
        this.ffmpeg = ffmpeg;
    }

    @GetMapping
    public List<Project> list() {
        return projects.list();
    }

    @PostMapping
    public Project create(@RequestBody Map<String, String> body) {
        return projects.create(body.get("name"), body.get("aspect"));
    }

    @GetMapping("/{id}")
    public Project get(@PathVariable String id) {
        return projects.get(id);
    }

    /**
     * Saves editable fields (name, aspect, topic, kenBurns, scene text/order).
     * Media/audio fields always come from the server copy, because background jobs write them
     * and the UI may hold a stale copy. Use DELETE .../media to clear them.
     */
    @PutMapping("/{id}")
    public Project update(@PathVariable String id, @RequestBody Project body) {
        return projects.mutate(id, p -> {
            p.name = body.name;
            p.aspect = body.aspect;
            p.topic = body.topic;
            p.kenBurns = body.kenBurns;
            if (body.niche != null && Project.NICHES.contains(body.niche)) p.niche = body.niche;
            p.styleBible = body.styleBible;
            if (body.tiktokCaption != null) p.tiktokCaption = body.tiktokCaption;
            if (body.tiktokTitle != null) p.tiktokTitle = body.tiktokTitle.strip();
            p.voice = body.voice;
            p.voiceSpeed = body.voiceSpeed;
            if (body.cast != null) p.cast = body.cast;
            p.scriptMode = true; // no voice-over any more
            if (body.musicVolume != null) p.musicVolume = Math.max(0, Math.min(1, body.musicVolume));
            p.musicStart = body.musicStart == null ? 0.0 : Math.max(0, body.musicStart);
            Map<String, Scene> server = new java.util.HashMap<>();
            for (Scene s : p.scenes) server.put(s.id, s);
            List<Scene> next = new java.util.ArrayList<>();
            for (Scene in : body.scenes) {
                Scene s = server.getOrDefault(in.id, new Scene());
                if (in.id != null) s.id = in.id;
                s.narration = in.narration;
                s.speaker = in.speaker == null || in.speaker.isBlank() ? null : in.speaker.trim();
                s.imagePrompt = in.imagePrompt;
                s.duration = in.duration;
                s.customDuration = in.customDuration;
                next.add(s);
            }
            p.scenes = next;
        });
    }

    // --- Source images (what the video is about) ---

    /** multipart: files (one or many images) */
    @PostMapping("/{id}/sources")
    public Project addSources(@PathVariable String id, @RequestParam("files") List<MultipartFile> files) throws IOException {
        List<String> names = new java.util.ArrayList<>();
        for (MultipartFile file : files) {
            String original = file.getOriginalFilename() == null ? "src.png" : file.getOriginalFilename();
            String ext = original.contains(".") ? original.substring(original.lastIndexOf('.')).toLowerCase() : ".png";
            if (!IMAGE_EXT.contains(ext)) throw new IllegalArgumentException("Chỉ nhận ảnh PNG, JPG hoặc WebP: " + original);
            String name = "src-" + java.util.UUID.randomUUID().toString().substring(0, 8) + ext;
            Files.copy(file.getInputStream(), projects.file(id, "assets", name), StandardCopyOption.REPLACE_EXISTING);
            names.add(name);
        }
        return projects.mutate(id, p -> p.sources.addAll(names));
    }

    @DeleteMapping("/{id}/sources/{name:.+}")
    public Project deleteSource(@PathVariable String id, @PathVariable String name) throws IOException {
        if (!name.startsWith("src-")) throw new IllegalArgumentException("Bad source name");
        Files.deleteIfExists(projects.file(id, "assets", name));
        return projects.mutate(id, p -> p.sources.remove(name));
    }

    // --- Background music ---

    private static final java.util.Set<String> AUDIO_EXT = java.util.Set.of(".mp3", ".wav", ".m4a", ".aac", ".ogg", ".flac", ".opus");

    /** multipart: file (one audio track, played under the whole video) */
    @PostMapping("/{id}/music")
    public Project setMusic(@PathVariable String id, @RequestParam("file") MultipartFile file) throws IOException {
        String original = file.getOriginalFilename() == null ? "music.mp3" : file.getOriginalFilename();
        String ext = original.contains(".") ? original.substring(original.lastIndexOf('.')).toLowerCase() : "";
        if (!AUDIO_EXT.contains(ext)) throw new IllegalArgumentException("Chỉ nhận file nhạc MP3, WAV, M4A, AAC, OGG, FLAC");
        String old = projects.get(id).musicFile;
        String name = "music-" + java.util.UUID.randomUUID().toString().substring(0, 6) + ext;
        Files.copy(file.getInputStream(), projects.file(id, "assets", name), StandardCopyOption.REPLACE_EXISTING);
        if (old != null) Files.deleteIfExists(projects.file(id, "assets", old));
        return projects.mutate(id, p -> {
            p.musicFile = name;
            p.musicStart = 0.0; // a new track starts from its beginning
        });
    }

    @DeleteMapping("/{id}/music")
    public Project deleteMusic(@PathVariable String id) throws IOException {
        String old = projects.get(id).musicFile;
        if (old != null) Files.deleteIfExists(projects.file(id, "assets", old));
        return projects.mutate(id, p -> {
            p.musicFile = null;
            p.musicStart = 0.0;
        });
    }

    // --- Character reference images ---

    private static final java.util.Set<String> IMAGE_EXT = java.util.Set.of(".png", ".jpg", ".jpeg", ".webp");

    /** multipart: file, name, description? */
    @PostMapping("/{id}/characters")
    public Project addCharacter(@PathVariable String id, @RequestParam("file") MultipartFile file,
                                @RequestParam("name") String name,
                                @RequestParam(value = "description", required = false) String description) throws IOException {
        String original = file.getOriginalFilename() == null ? "ref.png" : file.getOriginalFilename();
        String ext = original.contains(".") ? original.substring(original.lastIndexOf('.')).toLowerCase() : ".png";
        if (!IMAGE_EXT.contains(ext)) throw new IllegalArgumentException("Chỉ nhận ảnh PNG, JPG hoặc WebP");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Hãy đặt tên nhân vật");
        CharacterRef c = new CharacterRef();
        c.name = name.trim();
        c.description = description == null ? "" : description.trim();
        c.file = "ref-" + c.id + ext;
        Files.copy(file.getInputStream(), projects.file(id, "assets", c.file), StandardCopyOption.REPLACE_EXISTING);
        return projects.mutate(id, p -> p.characters.add(c));
    }

    /** {name, description} */
    @PutMapping("/{id}/characters/{cid}")
    public Project updateCharacter(@PathVariable String id, @PathVariable String cid, @RequestBody Map<String, String> body) {
        return projects.mutate(id, p -> {
            CharacterRef c = p.character(cid);
            if (body.get("name") != null && !body.get("name").isBlank()) c.name = body.get("name").trim();
            if (body.get("description") != null) c.description = body.get("description").trim();
        });
    }

    /** multipart: file — sets or replaces this character's picture. */
    @PostMapping("/{id}/characters/{cid}/image")
    public Project setCharacterImage(@PathVariable String id, @PathVariable String cid,
                                     @RequestParam("file") MultipartFile file) throws IOException {
        String original = file.getOriginalFilename() == null ? "ref.png" : file.getOriginalFilename();
        String ext = original.contains(".") ? original.substring(original.lastIndexOf('.')).toLowerCase() : ".png";
        if (!IMAGE_EXT.contains(ext)) throw new IllegalArgumentException("Chỉ nhận ảnh PNG, JPG hoặc WebP");
        String old = projects.get(id).character(cid).file;
        String name = "ref-" + cid + ext;
        Files.copy(file.getInputStream(), projects.file(id, "assets", name), StandardCopyOption.REPLACE_EXISTING);
        if (old != null && !old.equals(name)) Files.deleteIfExists(projects.file(id, "assets", old));
        return projects.mutate(id, p -> {
            CharacterRef c = p.character(cid);
            c.file = name;
            c.canvaMediaId = null; // the old upload shows the old picture
        });
    }

    @DeleteMapping("/{id}/characters/{cid}")
    public Project deleteCharacter(@PathVariable String id, @PathVariable String cid) throws IOException {
        Project p = projects.get(id);
        CharacterRef c = p.character(cid);
        if (c.file != null) Files.deleteIfExists(projects.file(id, "assets", c.file));
        return projects.mutate(id, pr -> pr.characters.removeIf(x -> x.id.equals(cid)));
    }

    /** Uses this scene's Canva image as the style/character reference for later scenes (or clears it). */
    @PostMapping("/{id}/style-ref")
    public Project styleRef(@PathVariable String id, @RequestBody Map<String, String> body) {
        String sceneId = body.get("sceneId");
        return projects.mutate(id, p -> {
            if (sceneId == null || sceneId.isBlank()) {
                p.styleRefMediaId = null;
                p.styleRefSceneId = null;
                return;
            }
            Scene s = p.scene(sceneId);
            if (s.canvaMediaId == null) throw new IllegalArgumentException("Cảnh này chưa có ảnh từ Canva");
            p.styleRefMediaId = s.canvaMediaId;
            p.styleRefSceneId = s.id;
        });
    }

    /** kind=media|audio|refPhoto (the real photo used as an image reference in modern history) */
    @DeleteMapping("/{id}/scenes/{sceneId}/media")
    public Project clearMedia(@PathVariable String id, @PathVariable String sceneId, @RequestParam String kind) {
        return projects.mutate(id, p -> {
            Scene s = p.scene(sceneId);
            if ("refPhoto".equals(kind)) {
                s.refPhoto = null;
                s.refPhotoSource = null;
            } else if ("audio".equals(kind)) {
                s.audioFile = null;
                s.audioDuration = null;
            } else {
                s.mediaFile = null;
                s.mediaSource = null;
                s.canvaMediaId = null;
                s.canvaDesignId = null;
            }
        });
    }

    /** Copies a scene (text, picture, voice) and puts the copy right after it. */
    @PostMapping("/{id}/scenes/{sceneId}/duplicate")
    public Project duplicate(@PathVariable String id, @PathVariable String sceneId) throws IOException {
        return projects.mutate(id, p -> {
            int i = p.scenes.indexOf(p.scene(sceneId));
            Scene a = p.scenes.get(i);
            Scene b = new Scene();
            b.narration = a.narration;
            b.imagePrompt = a.imagePrompt;
            b.refPhoto = a.refPhoto;
            b.refPhotoSource = a.refPhotoSource;
            b.duration = a.duration;
            b.customDuration = a.customDuration;
            b.mediaSource = a.mediaSource;
            b.audioDuration = a.audioDuration;
            try {
                // Files are copied so editing or replacing one scene never changes the other.
                if (a.mediaFile != null && Files.exists(projects.file(id, "assets", a.mediaFile))) {
                    String ext = a.mediaFile.contains(".") ? a.mediaFile.substring(a.mediaFile.lastIndexOf('.')) : "";
                    b.mediaFile = "scene-" + b.id + "-media-copy" + ext;
                    Files.copy(projects.file(id, "assets", a.mediaFile), projects.file(id, "assets", b.mediaFile));
                }
                if (a.audioFile != null && Files.exists(projects.file(id, "assets", a.audioFile))) {
                    String ext = a.audioFile.contains(".") ? a.audioFile.substring(a.audioFile.lastIndexOf('.')) : "";
                    b.audioFile = "scene-" + b.id + "-audio-copy" + ext;
                    Files.copy(projects.file(id, "assets", a.audioFile), projects.file(id, "assets", b.audioFile));
                }
            } catch (IOException e) {
                throw new IllegalStateException("Không nhân đôi được file: " + e.getMessage());
            }
            p.scenes.add(i + 1, b);
        });
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        projects.delete(id);
    }

    /** Upload an image/video (kind=media) or audio (kind=audio) for a scene. */
    @PostMapping("/{id}/scenes/{sceneId}/upload")
    public Project upload(@PathVariable String id, @PathVariable String sceneId,
                          @RequestParam("kind") String kind,
                          @RequestParam("file") MultipartFile file) throws IOException {
        String original = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        String ext = original.contains(".") ? original.substring(original.lastIndexOf('.')).toLowerCase() : "";
        String name = "scene-" + sceneId + "-" + kind + "-upload" + ext;
        Path target = projects.file(id, "assets", name);
        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        Double dur = "audio".equals(kind) ? ffmpeg.probeDuration(target) : null;
        return projects.mutate(id, p -> {
            Scene s = p.scene(sceneId);
            if ("audio".equals(kind)) {
                s.audioFile = name;
                s.audioDuration = dur;
            } else {
                s.mediaFile = name;
                s.mediaSource = "upload";
            }
        });
    }

    /** Serves assets/output files; Spring handles Range requests so video seeking works. */
    @GetMapping("/{id}/files/{folder}/{name:.+}")
    public ResponseEntity<Resource> file(@PathVariable String id, @PathVariable String folder,
                                         @PathVariable String name,
                                         @RequestParam(value = "download", required = false) String download) {
        Path p = projects.file(id, folder, name);
        if (!Files.exists(p)) return ResponseEntity.notFound().build();
        Resource r = new FileSystemResource(p);
        var res = ResponseEntity.ok()
                .contentType(MediaTypeFactory.getMediaType(r).orElse(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM));
        // ?download=1 makes the browser save the file instead of showing it.
        if (download != null) res.header("Content-Disposition", "attachment; filename=\"" + name.replace("\"", "") + "\"");
        return res.body(r);
    }

    /** Opens a project folder (assets / output) in the computer's file manager. Only from this computer. */
    @PostMapping("/{id}/open-folder")
    public Map<String, String> openFolder(@PathVariable String id, @RequestBody(required = false) Map<String, String> body) throws IOException {
        String folder = body == null ? "output" : body.getOrDefault("folder", "output");
        if (!folder.equals("output") && !folder.equals("assets")) throw new IllegalArgumentException("Thư mục không hợp lệ");
        Path dir = projects.folder(id, folder);
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) new ProcessBuilder("explorer.exe", dir.toString()).start();
        else if (os.contains("mac")) new ProcessBuilder("open", dir.toString()).start();
        else new ProcessBuilder("xdg-open", dir.toString()).start();
        return Map.of("path", dir.toString());
    }
}
