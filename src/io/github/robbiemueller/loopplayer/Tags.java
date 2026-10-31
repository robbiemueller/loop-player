package io.github.robbiemueller.loopplayer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Labels for the videos, kept in tags.json beside them, so a whole group (say "Halloween") can be looped at once:
 * <pre>{"order": ["Halloween", "Music"], "videos": {"01-intro.mp4": ["Halloween", "Music"], ...}}</pre>
 * "order" is optional and only sets the order of the tag buttons; tags not in it follow, most used first.
 * Tags match without regard to case.
 */
final class Tags {
    static final String FILE_NAME = "tags.json";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final Map<String, List<String>> byVideo = new LinkedHashMap<>();
    private final List<String> order = new ArrayList<>();

    /** Reads the file; a missing or broken one just means no tags. */
    static Tags load(File file) {
        Tags t = new Tags();
        if (!file.isFile()) return t;
        try {
            InputStream in = new FileInputStream(file);
            byte[] data;
            try {
                data = new byte[(int) Math.min(file.length(), 4 * 1024 * 1024)];
                int read = 0;
                while (read < data.length) {
                    int n = in.read(data, read, data.length - read);
                    if (n < 0) break;
                    read += n;
                }
            } finally {
                in.close();
            }
            JSONObject json = new JSONObject(new String(data, UTF8));
            JSONArray order = json.optJSONArray("order");
            if (order != null) {
                for (int i = 0; i < order.length(); i++) t.order.add(order.getString(i).trim());
            }
            JSONObject videos = json.optJSONObject("videos");
            if (videos != null) {
                JSONArray names = videos.names();
                for (int i = 0; names != null && i < names.length(); i++) {
                    String name = names.getString(i);
                    JSONArray list = videos.optJSONArray(name);
                    List<String> tags = new ArrayList<>();
                    for (int j = 0; list != null && j < list.length(); j++) {
                        String tag = list.getString(j).trim();
                        if (!tag.isEmpty() && indexOf(tags, tag) < 0) tags.add(tag);
                    }
                    t.byVideo.put(name, tags);
                }
            }
        } catch (Exception ignored) {
            // Leave whatever was read; a bad file shouldn't stop the videos playing.
        }
        return t;
    }

    void save(File file) throws IOException {
        JSONObject json = new JSONObject();
        try {
            json.put("order", new JSONArray(order));
            JSONObject videos = new JSONObject();
            for (Map.Entry<String, List<String>> e : byVideo.entrySet()) {
                if (!e.getValue().isEmpty()) videos.put(e.getKey(), new JSONArray(e.getValue()));
            }
            json.put("videos", videos);
        } catch (Exception e) {
            throw new IOException("Couldn't write the tags");
        }
        File part = new File(file.getParentFile(), "." + file.getName() + ".partial");
        OutputStream out = new FileOutputStream(part);
        try {
            out.write(json.toString(1).getBytes(UTF8));
        } catch (Exception e) {
            throw new IOException("Couldn't write the tags");
        } finally {
            out.close();
        }
        if (file.exists() && !file.delete()) throw new IOException("Couldn't replace " + file.getName());
        if (!part.renameTo(file)) throw new IOException("Couldn't save " + file.getName());
    }

    List<String> of(String video) {
        List<String> tags = byVideo.get(video);
        return tags == null ? Collections.<String>emptyList() : tags;
    }

    void set(String video, List<String> tags) {
        List<String> clean = new ArrayList<>();
        for (String tag : tags) {
            String t = tag.trim();
            if (!t.isEmpty() && t.length() <= 40 && indexOf(clean, t) < 0) clean.add(t);
        }
        byVideo.put(video, clean);
    }

    /** True when the video has any of the tags. */
    boolean matches(String video, Collection<String> wanted) {
        for (String tag : of(video)) {
            if (indexOf(wanted, tag) >= 0) return true;
        }
        return false;
    }

    /** Every tag on the given videos, in button order: "order" first, then the rest, most used first. */
    List<String> present(List<File> library) {
        final Map<String, Integer> counts = new HashMap<>();
        final Map<String, String> spelling = new LinkedHashMap<>();
        for (File f : library) {
            for (String tag : of(f.getName())) {
                String key = tag.toLowerCase(Locale.ROOT);
                Integer c = counts.get(key);
                counts.put(key, c == null ? 1 : c + 1);
                if (!spelling.containsKey(key)) spelling.put(key, tag);
            }
        }
        List<String> result = new ArrayList<>();
        for (String tag : order) {
            String key = tag.toLowerCase(Locale.ROOT);
            if (counts.containsKey(key) && indexOf(result, tag) < 0) result.add(spelling.get(key));
        }
        List<String> rest = new ArrayList<>();
        for (String key : spelling.keySet()) {
            if (indexOf(result, key) < 0) rest.add(key);
        }
        Collections.sort(rest, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                int byCount = counts.get(b) - counts.get(a);
                return byCount != 0 ? byCount : a.compareTo(b);
            }
        });
        for (String key : rest) result.add(spelling.get(key));
        return result;
    }

    /** How many of the given videos have any of the tags. */
    int count(List<File> library, Set<String> wanted) {
        int n = 0;
        for (File f : library) {
            if (matches(f.getName(), wanted)) n++;
        }
        return n;
    }

    static int indexOf(Collection<String> list, String tag) {
        int i = 0;
        for (String t : list) {
            if (t.equalsIgnoreCase(tag)) return i;
            i++;
        }
        return -1;
    }
}
