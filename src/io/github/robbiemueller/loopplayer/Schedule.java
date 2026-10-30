package io.github.robbiemueller.loopplayer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * A daily schedule: a list of times, each of which can put the screen to sleep or wake it, set the brightness,
 * turn the sound on or off and change what loops. A rule takes effect once, at its time; anything changed by hand
 * after that stays until the next rule. Stored as JSON, which is also what the phone remote sends:
 *   {"enabled":true,"rules":[{"time":"22:00","screen":"off","sound":"keep","brightness":-2,"loop":"keep","names":[]}]}
 */
final class Schedule {
    static final int KEEP_BRIGHTNESS = -2;
    static final int AUTO_BRIGHTNESS = -1;
    private static final int MAX_RULES = 48;

    static final class Rule {
        int minute;                       // minute of the day, 0..1439
        String screen = "keep";           // on, off, keep
        String sound = "keep";            // on, off, keep
        int brightness = KEEP_BRIGHTNESS; // KEEP_BRIGHTNESS, AUTO_BRIGHTNESS or 1..100 percent
        String loop = "keep";             // keep, all, pick
        List<String> names = new ArrayList<>();

        JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("time", String.format(Locale.US, "%02d:%02d", minute / 60, minute % 60))
                    .put("screen", screen).put("sound", sound).put("brightness", brightness)
                    .put("loop", loop).put("names", new JSONArray(names));
        }
    }

    boolean enabled;
    final List<Rule> rules = new ArrayList<>();

    /** Parses and checks a schedule; throws IllegalArgumentException on anything out of range. */
    static Schedule parse(String json) {
        Schedule s = new Schedule();
        if (json == null || json.isEmpty()) return s;
        try {
            JSONObject o = new JSONObject(json);
            s.enabled = o.optBoolean("enabled", false);
            JSONArray list = o.optJSONArray("rules");
            if (list == null) return s;
            if (list.length() > MAX_RULES) throw new IllegalArgumentException("At most " + MAX_RULES + " times");
            for (int i = 0; i < list.length(); i++) {
                JSONObject r = list.getJSONObject(i);
                Rule rule = new Rule();
                String time = r.getString("time");
                if (!time.matches("([01]\\d|2[0-3]):[0-5]\\d")) throw new IllegalArgumentException("Bad time " + time);
                rule.minute = Integer.parseInt(time.substring(0, 2)) * 60 + Integer.parseInt(time.substring(3));
                rule.screen = oneOf(r.optString("screen", "keep"), "on", "off", "keep");
                rule.sound = oneOf(r.optString("sound", "keep"), "on", "off", "keep");
                rule.brightness = r.optInt("brightness", KEEP_BRIGHTNESS);
                if (rule.brightness == 0 || rule.brightness < KEEP_BRIGHTNESS || rule.brightness > 100) {
                    throw new IllegalArgumentException("Bad brightness " + rule.brightness);
                }
                rule.loop = oneOf(r.optString("loop", "keep"), "keep", "all", "pick");
                JSONArray names = r.optJSONArray("names");
                for (int j = 0; names != null && j < names.length(); j++) rule.names.add(names.getString(j));
                if (rule.loop.equals("pick") && rule.names.isEmpty()) rule.loop = "keep";
                s.rules.add(rule);
            }
        } catch (JSONException e) {
            throw new IllegalArgumentException("Bad schedule: " + e.getMessage());
        }
        Collections.sort(s.rules, new Comparator<Rule>() {
            @Override
            public int compare(Rule a, Rule b) {
                return a.minute - b.minute;
            }
        });
        return s;
    }

    /** Like parse, but a broken saved schedule just means no schedule. */
    static Schedule load(String json) {
        try {
            return parse(json);
        } catch (IllegalArgumentException e) {
            return new Schedule();
        }
    }

    JSONObject toJson() throws JSONException {
        JSONArray list = new JSONArray();
        for (Rule r : rules) list.put(r.toJson());
        return new JSONObject().put("enabled", enabled).put("rules", list);
    }

    /** The rule in force at this minute of the day: the latest one at or before it, else yesterday's last one. */
    Rule activeAt(int minuteOfDay) {
        if (rules.isEmpty()) return null;
        Rule active = rules.get(rules.size() - 1);
        for (Rule r : rules) {
            if (r.minute <= minuteOfDay) active = r;
        }
        return active;
    }

    private static String oneOf(String value, String... allowed) {
        for (String a : allowed) {
            if (a.equals(value)) return value;
        }
        throw new IllegalArgumentException("Bad value " + value);
    }
}
