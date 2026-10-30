package com.endgrid.iqtv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Search in plain words with Gemini (Google's Gemini API, with the person's own free key from Google AI Studio,
 * typed in Settings > Personalize > Recommendations): "a feel-good Tamil family movie", "shows like Suzhal". Gemini
 * answers with titles; Recs then checks each one on TMDB, so only what really exists (and, first, what streams on the
 * TV's apps) is shown. Only the words asked are sent, with the country and the content languages; never the watch
 * history or any setting. One request per search. Called off the main thread.
 */
final class Gemini {
    private Gemini() {}

    /**
     * The models tried in order: a retired name answers 404 (the 2.5 models are closed to new keys since 2026), a busy one
     * 503, and the next is tried. Flash-Lite first: a list of titles needs no thinking, and on a TV the answer must come
     * in seconds (measured: Flash-Lite 3.6 s; 3.8 Flash over a minute, 33 s with thinking set to low).
     */
    private static final String[] MODELS = {"gemini-flash-lite-latest", "gemini-3.5-flash-lite", "gemini-3.8-flash"};

    static final class Answer {
        String text = "";                              // Gemini's one-line answer
        final List<String[]> titles = new ArrayList<>();   // {title, year or "", "movie" | "tv"}
    }

    static boolean on() {
        return !Prefs.geminiKey().isEmpty();
    }

    static Answer ask(String question, String region, List<String> languages) throws IOException {
        String prompt = "You help someone choose what to watch on their TV in country " + region
                + (languages.isEmpty() ? "" : ", who prefers titles in these languages (ISO 639-1): " + String.join(", ", languages))
                + ". They asked: \"" + question + "\".\n"
                + "Answer with JSON only: {\"answer\": one short friendly sentence (at most 20 words) in the language of the question, "
                + "\"titles\": up to 10 real movies or TV series that fit, best first, each {\"title\": the original release title, "
                + "\"year\": the release year as a number, \"type\": \"movie\" or \"tv\"}}. "
                + "Only titles that really exist; prefer ones available on streaming services in that country.";
        JSONObject body;
        try {
            body = new JSONObject()
                    .put("contents", new JSONArray().put(new JSONObject().put("role", "user")
                            .put("parts", new JSONArray().put(new JSONObject().put("text", prompt)))))
                    .put("generationConfig", new JSONObject().put("responseMimeType", "application/json").put("temperature", 0.4));
        } catch (Exception e) {
            throw new IOException(e.toString());
        }
        IOException last = null;
        for (String model : MODELS) {
            try {
                return parse(post(model, body.toString()));
            } catch (NotFound e) {
                last = e;   // retired name: the next one
            }
        }
        throw last;
    }

    private static final class NotFound extends IOException {
        NotFound(String m) { super(m); }
    }

    private static String post(String model, String json) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("https://generativelanguage.googleapis.com/v1beta/models/" + model
                + ":generateContent").openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(45000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("x-goog-api-key", Prefs.geminiKey());
        try (OutputStream out = c.getOutputStream()) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
        try {
            int code = c.getResponseCode();
            if (code == 404) throw new NotFound("Gemini has no model " + model);
            if (code == 500 || code == 503) throw new NotFound(Ui.tr("Gemini is busy right now. Try again in a minute."));
            if (code == 400 || code == 401 || code == 403) throw new IOException(Ui.tr("Gemini didn't accept the key"));
            if (code == 429) throw new IOException(Ui.tr("Gemini's free limit is used up for now"));
            if (code != 200) throw new IOException("Gemini returned HTTP " + code);
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        return b.toString("UTF-8");
    }

    private static Answer parse(String response) throws IOException {
        try {
            JSONObject r = new JSONObject(response);
            JSONArray parts = r.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts");
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) text.append(parts.getJSONObject(i).optString("text"));
            String json = text.toString().trim();
            // JSON mode answers bare JSON; a fenced block is tolerated too.
            int open = json.indexOf('{'), close = json.lastIndexOf('}');
            if (open < 0 || close <= open) throw new IOException("Gemini's answer had no titles");
            JSONObject o = new JSONObject(json.substring(open, close + 1));
            Answer a = new Answer();
            a.text = o.optString("answer", "").trim();
            JSONArray t = o.optJSONArray("titles");
            for (int i = 0; t != null && i < t.length() && a.titles.size() < 10; i++) {
                JSONObject x = t.optJSONObject(i);
                if (x == null || x.optString("title").trim().isEmpty()) continue;
                String type = x.optString("type").toLowerCase(java.util.Locale.ROOT).contains("tv")
                        || x.optString("type").toLowerCase(java.util.Locale.ROOT).contains("series") ? "tv" : "movie";
                int year = x.optInt("year", 0);
                a.titles.add(new String[] {x.optString("title").trim(), year > 1900 ? String.valueOf(year) : "", type});
            }
            return a;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Gemini's answer couldn't be read");
        }
    }
}
