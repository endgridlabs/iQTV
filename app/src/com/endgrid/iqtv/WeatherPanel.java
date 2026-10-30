package com.endgrid.iqtv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.text.TextUtils;

/**
 * The weather on the Top Shelf, as tvOS-style launchers show it: a glass pill in the top bar (symbol, temperature,
 * the sky in words and the city), and OK on it opens the full Weather panel over the dimmed screen: now, the next
 * hours, the next days with their range, and eight tiles (feels like, UV, sunrise, sunset, air quality, rain chance,
 * humidity, wind). Open-Meteo's data (HomeData). Drawn in the 1920 × 1080 layout scaled by `k`.
 */
final class WeatherPanel {
    private final Context context;
    private float k = -1;
    private Paint pillTemp, pillLine1, pillLine2, label, city, big, sky, hiLo, hourName, hourTemp, dayName, dayTemp, rain,
            tileLabel, tileValue, tileNote, footer;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF(), panel = new RectF();
    /** Where the pill is now (empty when there's no weather): the top bar's focus and pointer use it. */
    final RectF pill = new RectF();

    WeatherPanel(Context context) {
        this.context = context;
        rim.setStyle(Paint.Style.STROKE);
    }

    private void paints(float k) {
        if (this.k == k) return;
        this.k = k;
        pillTemp = Ui.numbers(Ui.medium, 32 * k, Tokens.TEXT);
        pillLine1 = Ui.text(Ui.semibold, 21 * k, Tokens.TEXT);
        pillLine2 = Ui.text(Ui.medium, 19 * k, Tokens.TEXT_SECONDARY);
        label = Ui.text(Ui.semibold, 22 * k, Tokens.TEXT_TERTIARY);
        label.setLetterSpacing(0.06f);
        city = Ui.text(Ui.semibold, 38 * k, Tokens.TEXT);
        big = Ui.numbers(Ui.thin, 140 * k, Tokens.TEXT);
        sky = Ui.text(Ui.medium, 32 * k, Tokens.TEXT);
        hiLo = Ui.text(Ui.medium, 28 * k, Tokens.TEXT_SECONDARY);
        hourName = Ui.text(Ui.medium, 24 * k, Tokens.TEXT_SECONDARY);
        hourTemp = Ui.numbers(Ui.medium, 28 * k, Tokens.TEXT);
        dayName = Ui.text(Ui.medium, 30 * k, Tokens.TEXT);
        dayTemp = Ui.numbers(Ui.medium, 28 * k, Tokens.TEXT);
        rain = Ui.numbers(Ui.semibold, 18 * k, 0xFF64D2FF);
        tileLabel = Ui.text(Ui.semibold, 21 * k, Tokens.TEXT_TERTIARY);
        tileLabel.setLetterSpacing(0.06f);
        tileValue = Ui.numbers(Ui.medium, 50 * k, Tokens.TEXT);
        tileNote = Ui.text(Ui.medium, 22 * k, Tokens.TEXT_SECONDARY);
        footer = Ui.text(Ui.medium, 21 * k, Tokens.TEXT_DIM);
    }

    static boolean has(HomeData.Weather wx) {
        return wx != null && !Double.isNaN(wx.temp);
    }

    private static String cityName() {
        String c = Prefs.city();
        return c.isEmpty() ? Ui.tr("Weather") : c;
    }

    // MARK: - The pill in the top bar

    /**
     * Draws the pill with its right edge at `right`, centered on `cy`; `focus` 0..1 turns it white and lifts it.
     * Returns its left edge (or `right` when there's no weather to show).
     */
    float drawPill(Canvas c, HomeData.Weather wx, float right, float cy, float k, float focus, float alpha) {
        paints(k);
        if (!has(wx) || alpha <= 0.01f) {
            pill.setEmpty();
            return right;
        }
        String temp = HomeData.degrees(wx.temp);
        String line1 = HomeData.condition(wx.code, wx.isDay), line2 = cityName();
        float textW = Math.min(180 * k, Math.max(pillLine1.measureText(line1), pillLine2.measureText(line2)));
        float h = 72 * k, icon = 34 * k;
        float w = 22 * k + icon + 12 * k + pillTemp.measureText(temp) + 14 * k + textW + 26 * k;
        pill.set(right - w, cy - h / 2, right, cy + h / 2);
        float s = 1 + Tokens.FOCUS_GROW_TAB * focus;
        r.set(pill.centerX() - w * s / 2, cy - h * s / 2, pill.centerX() + w * s / 2, cy + h * s / 2);
        float rr = r.height() / 2;
        fill.setShader(null);
        fill.setColor(Ui.alpha(blend(0x2EFFFFFF, 0xFFFFFFFF, focus), alpha));
        c.drawRoundRect(r, rr, rr, fill);
        if (focus < 0.98f) {
            rim.setColor(Ui.alpha(0x33FFFFFF, alpha * (1 - focus)));
            rim.setStrokeWidth(1.5f * k);
            c.drawRoundRect(r, rr, rr, rim);
        }
        int ink = Ui.alpha(blend(Tokens.TEXT, Tokens.INK, focus), alpha);
        int ink2 = Ui.alpha(blend(Tokens.TEXT_SECONDARY, 0xFF6E6E73, focus), alpha);
        float x = r.left + 22 * k * s;
        Symbols.draw(context, c, Symbols.weather(wx.code, wx.isDay), x + icon / 2, cy, icon * s, ink);
        x += icon * s + 12 * k;
        pillTemp.setColor(ink);
        pillTemp.setTextAlign(Paint.Align.LEFT);
        c.drawText(temp, x, cy + Ui.halfCap(pillTemp), pillTemp);
        x += pillTemp.measureText(temp) + 14 * k;
        pillLine1.setColor(ink);
        pillLine2.setColor(ink2);
        c.drawText(fit(line1, pillLine1, textW), x, cy - 4 * k, pillLine1);
        c.drawText(fit(line2, pillLine2, textW), x, cy + 22 * k, pillLine2);
        return pill.left;
    }

    // MARK: - The full panel

    /** The Weather panel over the dimmed screen; `a` is its fade (0..1). */
    void drawPanel(Canvas c, HomeData.Weather wx, long updatedAt, float w, float h, float k, float a) {
        paints(k);
        fill.setShader(null);
        fill.setColor(Ui.alpha(Tokens.SCRIM, a));
        c.drawRect(0, 0, w, h, fill);
        float rise = (1 - a) * 30 * k;
        panel.set(100 * k, 50 * k + rise, w - 100 * k, h - 50 * k + rise);
        fill.setColor(Ui.alpha(Tokens.PANEL, a));
        c.drawRoundRect(panel, Tokens.RADIUS_PANEL * k, Tokens.RADIUS_PANEL * k, fill);
        rim.setColor(Ui.alpha(0x26FFFFFF, a));
        rim.setStrokeWidth(1.5f * k);
        c.drawRoundRect(panel, Tokens.RADIUS_PANEL * k, Tokens.RADIUS_PANEL * k, rim);
        if (!has(wx)) {
            sky.setAlpha(Math.round(255 * a));
            sky.setTextAlign(Paint.Align.CENTER);
            c.drawText(Ui.tr("No weather yet: it loads from Open-Meteo when the TV is online."), panel.centerX(), panel.centerY(), sky);
            sky.setTextAlign(Paint.Align.LEFT);
            return;
        }
        int alpha = Math.round(255 * a);
        float left = panel.left + 50 * k, colW = 720 * k, top = panel.top;

        // Now: the city, the temperature, the sky, high and low.
        float cx = left + colW / 2;
        for (Paint p : new Paint[] {city, big, sky, hiLo}) p.setTextAlign(Paint.Align.CENTER);
        city.setAlpha(alpha);
        c.drawText(cityName(), cx, top + 76 * k, city);
        big.setAlpha(alpha);
        c.drawText(HomeData.degrees(wx.temp), cx + 20 * k, top + 210 * k, big);
        sky.setAlpha(alpha);
        c.drawText(HomeData.condition(wx.code, wx.isDay), cx, top + 262 * k, sky);
        hiLo.setAlpha(Math.round(0xD9 * a));
        c.drawText(HomeData.highLow(wx, "   "), cx, top + 304 * k, hiLo);
        for (Paint p : new Paint[] {city, big, sky, hiLo}) p.setTextAlign(Paint.Align.LEFT);

        // The next hours.
        float y = top + 360 * k;
        sectionLabel(c, Ui.tr("HOURLY FORECAST"), left, y, colW, a);
        int n = Math.min(6, wx.hours.size());
        float step = colW / 6;
        for (Paint p : new Paint[] {hourName, hourTemp}) p.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < n; i++) {
            float hx = left + step * i + step / 2;
            hourName.setAlpha(alpha);
            c.drawText(HomeData.hour(wx.hours.get(i)), hx, y + 58 * k, hourName);
            Symbols.draw(context, c, Symbols.weather(wx.hourCode.get(i), wx.hourDay.get(i)), hx, y + 100 * k, 36 * k,
                    Ui.alpha(Tokens.TEXT, a));
            int rp = i < wx.hourRain.size() ? wx.hourRain.get(i) : -1;
            if (rp >= 20) {
                rain.setTextAlign(Paint.Align.CENTER);
                rain.setAlpha(alpha);
                c.drawText(rp + "%", hx, y + 136 * k, rain);
            }
            hourTemp.setAlpha(alpha);
            c.drawText(HomeData.degrees(wx.hourTemp.get(i)), hx, y + 172 * k, hourTemp);
        }
        for (Paint p : new Paint[] {hourName, hourTemp}) p.setTextAlign(Paint.Align.LEFT);

        // The next days, each with its range on the week's scale (tvOS's bars).
        y = top + 570 * k;
        int days = Math.min(5, wx.days.size());
        sectionLabel(c, Ui.trf("{n}-DAY FORECAST", "n", days), left, y, colW, a);
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (int i = 0; i < days; i++) {
            lo = Math.min(lo, wx.dayMin.get(i));
            hi = Math.max(hi, wx.dayMax.get(i));
        }
        float rowH = 62 * k, barL = left + 330 * k, barR = left + colW - 90 * k;
        for (int i = 0; i < days; i++) {
            float ry = y + 30 * k + rowH * i + rowH / 2;
            dayName.setAlpha(alpha);
            String name = i == 0 ? Ui.tr("Today") : Ui.datePattern("format|EEE").format(wx.days.get(i));
            c.drawText(name, left, ry + Ui.halfCap(dayName), dayName);
            int code = wx.dayCode.get(i), rp = wx.dayRain.get(i);
            boolean showRain = rp >= 20;
            Symbols.draw(context, c, Symbols.weather(code, true), left + 175 * k, showRain ? ry - 8 * k : ry, 34 * k, Ui.alpha(Tokens.TEXT, a));
            if (showRain) {
                rain.setTextAlign(Paint.Align.CENTER);
                rain.setAlpha(alpha);
                c.drawText(rp + "%", left + 175 * k, ry + 24 * k, rain);
            }
            dayTemp.setAlpha(Math.round(0xB3 * a));
            dayTemp.setTextAlign(Paint.Align.RIGHT);
            c.drawText(HomeData.degrees(wx.dayMin.get(i)), barL - 18 * k, ry + Ui.halfCap(dayTemp), dayTemp);
            dayTemp.setAlpha(alpha);
            dayTemp.setTextAlign(Paint.Align.LEFT);
            c.drawText(HomeData.degrees(wx.dayMax.get(i)), barR + 18 * k, ry + Ui.halfCap(dayTemp), dayTemp);
            // The track, and this day's part of the week's range in green to orange.
            float th = 8 * k;
            fill.setShader(null);
            fill.setColor(Ui.alpha(0x33FFFFFF, a));
            r.set(barL, ry - th / 2, barR, ry + th / 2);
            c.drawRoundRect(r, th / 2, th / 2, fill);
            double span = Math.max(1, hi - lo);
            float x0 = barL + (float) ((wx.dayMin.get(i) - lo) / span) * (barR - barL);
            float x1 = barL + (float) ((wx.dayMax.get(i) - lo) / span) * (barR - barL);
            r.set(x0, ry - th / 2, Math.max(x1, x0 + th), ry + th / 2);
            fill.setShader(new LinearGradient(barL, 0, barR, 0, 0xFF9BE15D, 0xFFFF9F0A, Shader.TileMode.CLAMP));
            fill.setAlpha(alpha);
            c.drawRoundRect(r, th / 2, th / 2, fill);
            fill.setShader(null);
            if (i == 0 && !Double.isNaN(wx.temp)) {
                // Now, on today's bar.
                float nx = barL + (float) Math.max(0, Math.min(1, (wx.temp - lo) / span)) * (barR - barL);
                fill.setColor(Ui.alpha(0xFFFFFFFF, a));
                c.drawCircle(nx, ry, 7 * k, fill);
            }
        }

        // Eight tiles at the right.
        float tl = left + colW + 60 * k, tr = panel.right - 40 * k;
        float tGap = 22 * k, tw = (tr - tl - tGap) / 2, th = 188 * k, tTop = top + 40 * k;
        String[][] tiles = tiles(wx);
        for (int i = 0; i < tiles.length; i++) {
            float x = tl + (i % 2) * (tw + tGap), ty = tTop + (i / 2) * (th + tGap);
            RectF t = new RectF(x, ty, x + tw, ty + th);
            fill.setColor(Ui.alpha(0x14FFFFFF, a));
            c.drawRoundRect(t, 26 * k, 26 * k, fill);
            tileLabel.setAlpha(alpha);
            c.drawText(tiles[i][0], x + 28 * k, ty + 46 * k, tileLabel);
            tileValue.setAlpha(alpha);
            c.drawText(fit(tiles[i][1], tileValue, tw - 56 * k), x + 28 * k, ty + 112 * k, tileValue);
            tileNote.setAlpha(Math.round(0xD9 * a));
            c.drawText(fit(tiles[i][2], tileNote, tw - 56 * k), x + 28 * k, ty + 156 * k, tileNote);
        }

        footer.setAlpha(Math.round(0x99 * a));
        footer.setTextAlign(Paint.Align.CENTER);
        String when = updatedAt == 0 ? "" : " · " + Ui.trf("Updated {time}", "time", Ui.time(updatedAt));
        c.drawText("Open-Meteo" + when + " · " + Ui.tr("Back closes"), left + colW / 2, panel.bottom - 32 * k, footer);
        footer.setTextAlign(Paint.Align.LEFT);
    }

    private void sectionLabel(Canvas c, String text, float x, float y, float width, float a) {
        label.setAlpha(Math.round(255 * a));
        c.drawText(text, x, y, label);
        fill.setShader(null);
        fill.setColor(Ui.alpha(0x26FFFFFF, a));
        c.drawRect(x, y + 14 * k, x + width, y + 14 * k + Math.max(1, 1.5f * k), fill);
    }

    /** {label, value, note} for the eight tiles. */
    private static String[][] tiles(HomeData.Weather wx) {
        long now = System.currentTimeMillis();
        String feelsNote = Double.isNaN(wx.feels) ? "" : Math.abs(wx.feels - wx.temp) < 2 ? Ui.tr("Similar to actual")
                : wx.feels > wx.temp ? Ui.tr("Feels warmer") : Ui.tr("Feels cooler");
        String uv = Double.isNaN(wx.uv) ? "—" : String.valueOf(Math.round(wx.uv));
        String uvNote = Double.isNaN(wx.uv) ? "" : wx.uv < 3 ? Ui.tr("uv|Low") : wx.uv < 6 ? Ui.tr("uv|Moderate")
                : wx.uv < 8 ? Ui.tr("uv|High") : wx.uv < 11 ? Ui.tr("uv|Very High") : Ui.tr("uv|Extreme");
        String daylight = wx.sunset > wx.sunrise ? Ui.trf("{h} h {m} m daylight", "h", (wx.sunset - wx.sunrise) / 3_600_000,
                "m", (wx.sunset - wx.sunrise) / 60_000 % 60) : "";
        String left = now < wx.sunset && now > wx.sunrise ? Ui.trf("{h} h {m} m left", "h", (wx.sunset - now) / 3_600_000,
                "m", (wx.sunset - now) / 60_000 % 60) : Ui.tr("After sunset");
        String aqi = wx.aqi < 0 ? "—" : String.valueOf(wx.aqi);
        String aqiNote = wx.aqi < 0 ? "" : wx.aqi <= 50 ? Ui.tr("aqi|Good") : wx.aqi <= 100 ? Ui.tr("aqi|Moderate")
                : wx.aqi <= 150 ? Ui.tr("Unhealthy for some") : wx.aqi <= 200 ? Ui.tr("Unhealthy")
                : wx.aqi <= 300 ? Ui.tr("Very unhealthy") : Ui.tr("Hazardous");
        int rp = wx.rainNext();
        String hum = wx.humidity < 0 ? "—" : wx.humidity + "%";
        String humNote = wx.humidity < 0 ? "" : wx.humidity < 30 ? Ui.tr("Dry") : wx.humidity < 60 ? Ui.tr("Comfortable") : Ui.tr("Humid");
        String windNote = Double.isNaN(wx.wind) ? "" : wx.wind < 6 ? Ui.tr("Calm") : wx.wind < 20 ? Ui.tr("Light breeze")
                : wx.wind < 39 ? Ui.tr("Breezy") : Ui.tr("Windy");
        return new String[][] {
            {Ui.tr("FEELS LIKE"), HomeData.degrees(wx.feels), feelsNote},
            {Ui.tr("UV INDEX"), uv, uvNote},
            {Ui.tr("SUNRISE"), wx.sunrise == 0 ? "—" : Ui.time(wx.sunrise), daylight},
            {Ui.tr("SUNSET"), wx.sunset == 0 ? "—" : Ui.time(wx.sunset), left},
            {Ui.tr("AIR QUALITY"), aqi, aqiNote},
            {Ui.tr("RAIN CHANCE"), rp < 0 ? "—" : rp + "%", Ui.tr("Next 6 hours")},
            {Ui.tr("HUMIDITY"), hum, humNote},
            {Ui.tr("WIND"), HomeData.wind(wx.wind), windNote},
        };
    }

    private static String fit(String s, Paint p, float width) {
        return TextUtils.ellipsize(s, new TextPaint(p), width, TextUtils.TruncateAt.END).toString();
    }

    private static int blend(int a, int b, float f) {
        f = Math.max(0, Math.min(1, f));
        int aa = a >>> 24, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = b >>> 24, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return Math.round(aa + (ba - aa) * f) << 24 | Math.round(ar + (br - ar) * f) << 16
                | Math.round(ag + (bg - ag) * f) << 8 | Math.round(ab + (bb - ab) * f);
    }
}
