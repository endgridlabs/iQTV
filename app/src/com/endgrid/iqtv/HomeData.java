package com.endgrid.iqtv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Weather, sunrise and sunset, and air quality (Open-Meteo, every 30 min), cached
 * so the screen opens with numbers. Only fetched while the home screen or the screensaver is visible (the screen asks).
 * Open-Meteo needs no key. The location comes from Settings (config.properties at first).
 */
final class HomeData {
    /** Now, the next hours and 6 days: enough for the top bar's pill and the full Weather panel. */
    private static final String WEATHER_QUERY = "&current=temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,uv_index,weather_code,is_day"
            + "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,precipitation_probability_max"
            + "&hourly=temperature_2m,weather_code,is_day,precipitation_probability&timezone=auto&forecast_days=6";

    /** Air quality (US AQI) for the Weather panel: Open-Meteo's air-quality service, same place. */
    private static String airUrl() {
        return "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=" + Prefs.latitude() + "&longitude=" + Prefs.longitude()
                + "&current=us_aqi&timezone=auto";
    }

    private static String weatherPlace() {
        return Prefs.latitude() + "," + Prefs.longitude();
    }

    private static String weatherUrl() {
        return "https://api.open-meteo.com/v1/forecast?latitude=" + Prefs.latitude() + "&longitude=" + Prefs.longitude() + WEATHER_QUERY;
    }
    private static final long WEATHER_EVERY = 30 * 60_000L;
    /** The saved forecast; a new name when what's asked changes, so an older one is fetched again at once. */
    private static final String WEATHER_KEY = "weather2";
    interface Listener { void onData(); }




    /** Settings changed (the city): fetch again now. */
    void settingsChanged() {
        weatherAt = 0;
        weatherFailedAt = 0;
    }





    static final class Weather {
        double temp = Double.NaN, max = Double.NaN, min = Double.NaN;
        int code = -1;
        boolean isDay = true;
        long sunrise, sunset;   // today's, in epoch ms
        // Next hours (starting at the next full hour): time, temperature, code and day/night.
        final List<Long> hours = new ArrayList<>();
        final List<Double> hourTemp = new ArrayList<>();
        final List<Integer> hourCode = new ArrayList<>();
        final List<Boolean> hourDay = new ArrayList<>();
        final List<Integer> hourRain = new ArrayList<>();   // chance of rain, %
        // The Weather panel's details (NaN or -1 when the forecast didn't have them).
        double feels = Double.NaN, wind = Double.NaN, uv = Double.NaN;
        int humidity = -1, aqi = -1;
        // Today and the next days: date (local midnight), code, low, high, chance of rain.
        final List<Long> days = new ArrayList<>();
        final List<Integer> dayCode = new ArrayList<>(), dayRain = new ArrayList<>();
        final List<Double> dayMin = new ArrayList<>(), dayMax = new ArrayList<>();

        /** The highest chance of rain in the next hours, or -1. */
        int rainNext() {
            int most = -1;
            for (int r : hourRain) most = Math.max(most, r);
            return most;
        }
    }

    private static HomeData instance;

    static synchronized HomeData get(Context c) {
        if (instance == null) instance = new HomeData(c.getApplicationContext());
        return instance;
    }

    volatile long weatherAt;
    volatile Weather weather = new Weather();

    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private volatile boolean fetchingWeather;
    private volatile long weatherFailedAt;
    /** The city of the forecast in memory: another process (ScreensaverAlone) can see the city change under it. */
    private volatile String weatherFor = "";

    private HomeData(Context c) {
        cache = c.getSharedPreferences("homedata", Context.MODE_PRIVATE);
        try {
            String w = cache.getString(WEATHER_KEY, null);
            // The cached forecast is for the city it was fetched for; another city means fetch again.
            if (w != null && weatherPlace().equals(cache.getString("weatherPlace", ""))) {
                weather = parseWeather(w);
                String air = cache.getString("air", null);
                if (air != null) weather.aqi = parseAqi(air);
                weatherAt = cache.getLong("weatherAt", 0);
                weatherFor = weatherPlace();
            }
        } catch (Exception ignored) {}
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** Fetches whatever is stale. The screen calls this when it appears and every minute while it's visible. */
    void refreshIfStale() {
        long now = System.currentTimeMillis();
        // A negative age (the TV's clock went back) counts as stale: otherwise the data would freeze.
        boolean moved = !weatherPlace().equals(weatherFor);
        if (!fetchingWeather && (moved || now - weatherAt > WEATHER_EVERY || now < weatherAt) && !failedRecently(weatherFailedAt, now)) {
            fetchingWeather = true;
            io.execute(() -> {
                try {
                    String place = weatherPlace();
                    String body = get(weatherUrl());
                    Weather w = parseWeather(body);
                    // Air quality is a detail: without it (the service down), the forecast still shows.
                    String air = null;
                    try {
                        air = get(airUrl());
                        w.aqi = parseAqi(air);
                    } catch (IOException e) {
                        AppLog.w("air quality: " + e.getMessage());
                    }
                    weather = w;
                    weatherAt = System.currentTimeMillis();
                    weatherFor = place;
                    cache.edit().putString(WEATHER_KEY, body).putString("air", air).putLong("weatherAt", weatherAt)
                            .putString("weatherPlace", place).apply();
                    notifyListeners();
                } catch (Exception e) {
                    weatherFailedAt = System.currentTimeMillis();
                    AppLog.w("weather: " + e.getMessage());
                } finally {
                    fetchingWeather = false;
                }
            });
        }
    }

    private static boolean failedRecently(long at, long now) {
        return at != 0 && now >= at && now - at < 2 * 60_000L;
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onData(); });
    }


    private static Weather parseWeather(String body) throws Exception {
        JSONObject o = new JSONObject(body);
        TimeZone zone = TimeZone.getTimeZone(o.optString("timezone", TimeZone.getDefault().getID()));
        Weather w = new Weather();
        JSONObject current = o.getJSONObject("current");
        w.temp = current.getDouble("temperature_2m");
        w.code = current.getInt("weather_code");
        w.isDay = current.optInt("is_day", 1) == 1;
        w.feels = current.optDouble("apparent_temperature", Double.NaN);
        w.wind = current.optDouble("wind_speed_10m", Double.NaN);
        w.uv = current.optDouble("uv_index", Double.NaN);
        w.humidity = current.has("relative_humidity_2m") ? current.optInt("relative_humidity_2m", -1) : -1;
        JSONObject daily = o.getJSONObject("daily");
        w.max = daily.getJSONArray("temperature_2m_max").getDouble(0);
        w.min = daily.getJSONArray("temperature_2m_min").getDouble(0);
        w.sunrise = localTime(daily.getJSONArray("sunrise").getString(0), zone);
        w.sunset = localTime(daily.getJSONArray("sunset").getString(0), zone);
        JSONArray dTimes = daily.optJSONArray("time"), dCodes = daily.optJSONArray("weather_code");
        JSONArray dMax = daily.optJSONArray("temperature_2m_max"), dMin = daily.optJSONArray("temperature_2m_min");
        JSONArray dRain = daily.optJSONArray("precipitation_probability_max");
        for (int i = 0; dTimes != null && dCodes != null && i < dTimes.length(); i++) {
            w.days.add(localTime(dTimes.getString(i) + "T00:00", zone));
            w.dayCode.add(dCodes.optInt(i, -1));
            w.dayMin.add(dMin.optDouble(i, Double.NaN));
            w.dayMax.add(dMax.optDouble(i, Double.NaN));
            w.dayRain.add(dRain == null ? -1 : dRain.optInt(i, -1));
        }
        JSONObject hourly = o.optJSONObject("hourly");
        if (hourly != null) {
            JSONArray times = hourly.getJSONArray("time");
            JSONArray temps = hourly.getJSONArray("temperature_2m");
            JSONArray codes = hourly.getJSONArray("weather_code");
            JSONArray days = hourly.optJSONArray("is_day");
            JSONArray rain = hourly.optJSONArray("precipitation_probability");
            long now = System.currentTimeMillis();
            for (int i = 0; i < times.length() && w.hours.size() < 6; i++) {
                long at = localTime(times.getString(i), zone);
                if (at <= now) continue;
                w.hours.add(at);
                w.hourTemp.add(temps.getDouble(i));
                w.hourCode.add(codes.getInt(i));
                w.hourDay.add(days == null || days.optInt(i, 1) == 1);
                w.hourRain.add(rain == null ? -1 : rain.optInt(i, -1));
            }
        }
        return w;
    }

    /** The US AQI from the air-quality service's answer, or -1. */
    private static int parseAqi(String body) {
        try {
            JSONObject c = new JSONObject(body).optJSONObject("current");
            return c == null || !c.has("us_aqi") || c.isNull("us_aqi") ? -1 : c.getInt("us_aqi");
        } catch (Exception e) {
            return -1;
        }
    }

    private static long localTime(String iso, TimeZone zone) throws ParseException {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
        f.setTimeZone(zone);
        return f.parse(iso).getTime();
    }

    static List<String[]> findCity(String name) throws Exception {
        String lang = Ui.language.replaceAll("-.*", "");   // Open-Meteo takes the language without the country
        JSONObject o = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=10&format=json&language=" + lang
                + "&name=" + URLEncoder.encode(name.trim(), "UTF-8")));
        JSONArray results = o.optJSONArray("results");
        List<String[]> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();   // places with the same name, state and country read the same
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            String city = r.optString("name", "");
            if (city.isEmpty() || !r.has("latitude") || !r.has("longitude")) continue;
            StringBuilder label = new StringBuilder(city);
            for (String part : new String[] {r.optString("admin1", ""), r.optString("country", "")}) {
                if (!part.isEmpty() && !part.equals(city)) label.append(", ").append(part);
            }
            if (!seen.add(label.toString())) continue;
            out.add(new String[] {label.toString(), city,
                    String.format(Locale.US, "%.4f", r.getDouble("latitude")), String.format(Locale.US, "%.4f", r.getDouble("longitude"))});
        }
        return out;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(6000);
        c.setRequestProperty("User-Agent", "EndGrid-iQTV");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }

    /**
     * Night at your location: from sunset to sunrise (like Apple's "Automatic" appearance: the light design darkens).
     * Uses the last weather fetched (it moves ~1 min a day); without data, 6:00 and 18:00.
     */
    boolean isNight(long now) {
        return isNight(Prefs.theme(), now, weather.sunrise, weather.sunset);
    }

    /** The same from what it depends on (SleepOverlay gets these in its Intent). */
    static boolean isNight(String theme, long now, long sunrise, long sunset) {
        if (theme.equals("dark")) return true;
        if (theme.equals("light")) return false;
        long t = Shaders.timeOfDay(now, 0);
        long rise = Shaders.timeOfDay(sunrise, 6 * 60 * 60_000L);
        long set = Shaders.timeOfDay(sunset, 18 * 60 * 60_000L);
        return t < rise || t >= set;
    }

    // MARK: - Formatting

    private static Locale locale() {
        return Ui.locale();
    }

    /** The weather in words, from Open-Meteo's WMO code. */
    static String condition(int code, boolean day) {
        if (code < 0) return "";
        if (code == 0) return day ? Ui.tr("Clear") : Ui.tr("night|Clear");
        if (code == 1) return day ? Ui.tr("Mostly Sunny") : Ui.tr("Mostly Clear");
        if (code == 2) return Ui.tr("Partly Cloudy");
        if (code == 3) return Ui.tr("Cloudy");
        if (code == 45 || code == 48) return Ui.tr("Fog");
        if (code >= 51 && code <= 57) return Ui.tr("Drizzle");
        if (code == 65 || code == 67 || code == 82) return Ui.tr("Heavy Rain");
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return Ui.tr("Snow");
        if (code >= 95) return Ui.tr("Thunderstorms");
        return Ui.tr("Rain");
    }

    /** "H:24°  L:15°" (in Portuguese, "Máx. 24°  Mín. 15°"), with the given space between the two. */
    static String highLow(Weather wx, String space) {
        return Ui.trf("H:{max}", "max", degrees(wx.max)) + space + Ui.trf("L:{min}", "min", degrees(wx.min));
    }


    /** Celsius or Fahrenheit (config.properties "temperature": C, F, or auto = Fahrenheit in the US). */
    static String degrees(double v) {
        if (Double.isNaN(v)) return "—";
        return Math.round(fahrenheit() ? v * 9 / 5 + 32 : v) + "°";
    }

    /** Wind speed from Open-Meteo's km/h: "9 km/h", or "6 mph" where temperatures are in Fahrenheit. */
    static String wind(double kmh) {
        if (Double.isNaN(kmh)) return "—";
        return fahrenheit() ? Math.round(kmh * 0.621371) + " mph" : Math.round(kmh) + " km/h";
    }

    private static boolean fahrenheit() {
        String unit = Prefs.temperature();
        if (unit.equals("F")) return true;
        if (unit.equals("C")) return false;
        return "US".equals(Locale.getDefault().getCountry());
    }


    static String hour(long at) {
        return Ui.once("hour", at, () -> Ui.datePattern("format|h a").format(at));
    }

}
