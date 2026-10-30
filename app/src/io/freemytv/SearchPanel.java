package io.freemytv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.text.TextUtils;

/**
 * The Search screen over the dimmed home screen (HomeView has its focus, keys, typing and listening): the question
 * in a big field, Speak and Type, suggestions to start from, and what's happening (listening, asking Gemini, an
 * error). With a Gemini key the question can be in plain words; without one it searches TMDB by name, as before.
 * Drawn in the 1920 × 1080 layout scaled by `k`.
 */
final class SearchPanel {
    static final int IDLE = 0, LISTENING = 1, ASKING = 2, ERROR = 3;
    /** Items: Speak, Type, then the suggestions (row two). */
    static final int SPEAK = 0, TYPE = 1, FIRST_CHIP = 2;

    private final Context context;
    private float k = -1;
    private Paint title, badge, field, placeholder, button, label, chip, status, footer, spinner;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF panel = new RectF(), r = new RectF();
    private LinearGradient badgeShader;

    SearchPanel(Context context) {
        this.context = context;
        rim.setStyle(Paint.Style.STROKE);
    }

    private void paints(float k) {
        if (this.k == k) return;
        this.k = k;
        title = Ui.text(Ui.displaySemibold, 52 * k, Tokens.TEXT);
        badge = Ui.text(Ui.semibold, 24 * k, Tokens.TEXT);
        field = Ui.text(Ui.medium, 40 * k, Tokens.TEXT);
        placeholder = Ui.text(Ui.regular, 36 * k, Tokens.TEXT_DIM);
        button = Ui.text(Ui.semibold, 30 * k, Tokens.TEXT);
        label = Ui.text(Ui.semibold, 22 * k, Tokens.TEXT_TERTIARY);
        label.setLetterSpacing(0.06f);
        chip = Ui.text(Ui.medium, 27 * k, Tokens.TEXT);
        status = Ui.text(Ui.medium, 28 * k, Tokens.TEXT_SECONDARY);
        footer = Ui.text(Ui.regular, 22 * k, Tokens.TEXT_DIM);
        spinner = new Paint(Paint.ANTI_ALIAS_FLAG);
        spinner.setStyle(Paint.Style.STROKE);
        spinner.setStrokeCap(Paint.Cap.ROUND);
        spinner.setStrokeWidth(3.5f * k);
        spinner.setColor(0xFFFFFFFF);
        badgeShader = null;
    }

    /** The suggestions, in the first content language when there is one: "Tamil thrillers"… */
    static String[] chips() {
        String lang = Recs.languageName();
        return new String[] {
            lang.isEmpty() ? Ui.tr("Gripping thrillers") : Ui.trf("{language} thrillers", "language", lang),
            Ui.tr("Feel-good family movies"),
            Ui.tr("Best new shows this month"),
            Ui.tr("Award-winning dramas"),
        };
    }

    /**
     * Draws it; `a` is its fade, `focus` the focused item, `text` the question so far, `state` what's happening and
     * `message` the error or Gemini's word, `seconds` for the spinner and the listening pulse.
     */
    void draw(Canvas c, float w, float h, float k, float a, int focus, String text, boolean typing, int state, String message,
              float seconds) {
        paints(k);
        int alpha = Math.round(255 * a);
        fill.setShader(null);
        fill.setColor(Ui.alpha(Tokens.SCRIM, a));
        c.drawRect(0, 0, w, h, fill);
        float rise = (1 - a) * 30 * k;
        panel.set(160 * k, 120 * k + rise, w - 160 * k, h - 120 * k + rise);
        fill.setColor(Ui.alpha(0xF7141416, a));   // nearly opaque: the home screen behind mustn't show through the text
        c.drawRoundRect(panel, Tokens.RADIUS_PANEL * k, Tokens.RADIUS_PANEL * k, fill);
        rim.setColor(Ui.alpha(0x26FFFFFF, a));
        rim.setStrokeWidth(1.5f * k);
        c.drawRoundRect(panel, Tokens.RADIUS_PANEL * k, Tokens.RADIUS_PANEL * k, rim);
        float left = panel.left + 70 * k, right = panel.right - 70 * k, top = panel.top;

        // The title and, with a key, the Gemini badge.
        title.setAlpha(alpha);
        c.drawText(Ui.tr("Search"), left, top + 110 * k, title);
        float bx = left + title.measureText(Ui.tr("Search")) + 28 * k;
        if (Gemini.on()) {
            String b = Ui.tr("with Gemini");
            float bw = 18 * k + 26 * k + 10 * k + badge.measureText(b) + 22 * k, bh = 48 * k, by = top + 110 * k - 36 * k;
            r.set(bx, by, bx + bw, by + bh);
            if (badgeShader == null) badgeShader = new LinearGradient(0, 0, 400 * k, 0, 0xFF4285F4, 0xFF9B72CB, Shader.TileMode.CLAMP);
            fill.setShader(badgeShader);
            fill.setAlpha(alpha);
            c.drawRoundRect(r, bh / 2, bh / 2, fill);
            fill.setShader(null);
            Symbols.draw(context, c, "sparkles", r.left + 18 * k + 13 * k, r.centerY(), 24 * k, Ui.alpha(0xFFFFFFFF, a));
            badge.setAlpha(alpha);
            c.drawText(b, r.left + 18 * k + 36 * k, r.centerY() + Ui.halfCap(badge), badge);
        } else {
            footer.setAlpha(Math.round(0xB3 * a));
            c.drawText(Ui.tr("Add a free Gemini key in Settings to ask in plain words."), bx, top + 110 * k - 4 * k, footer);
        }

        // The question.
        r.set(left, top + 160 * k, right, top + 270 * k);
        fill.setColor(Ui.alpha(typing ? 0x33FFFFFF : 0x1FFFFFFF, a));
        c.drawRoundRect(r, 26 * k, 26 * k, fill);
        if (typing) {
            rim.setColor(Ui.alpha(0xCCFFFFFF, a));
            rim.setStrokeWidth(2.5f * k);
            c.drawRoundRect(r, 26 * k, 26 * k, rim);
        }
        float tx = r.left + 34 * k, ty = r.centerY();
        Symbols.draw(context, c, state == LISTENING ? "mic" : "search", tx + 16 * k, ty, 30 * k, Ui.alpha(Tokens.TEXT_SECONDARY, a));
        tx += 56 * k;
        float room = r.right - 34 * k - tx;
        if (text.isEmpty()) {
            String hint = state == LISTENING ? Ui.tr("Listening… say what you'd like to watch")
                    : Gemini.on() ? Ui.tr("Ask anything: “a feel-good Tamil family movie”") : Ui.tr("A movie, show, actor or director");
            placeholder.setAlpha(Math.round(255 * a));
            c.drawText(fit(hint, placeholder, room), tx, ty + Ui.halfCap(placeholder), placeholder);
        } else {
            field.setAlpha(alpha);
            // The end of a long question shows (what was said or typed last).
            String shown = text;
            while (shown.length() > 1 && field.measureText(shown) > room) shown = shown.substring(1);
            if (!shown.equals(text)) shown = "…" + shown.substring(Math.min(1, shown.length()));
            c.drawText(shown, tx, ty + Ui.halfCap(field), field);
            if (typing && ((int) (seconds * 2)) % 2 == 0) {   // the caret
                float cx = tx + field.measureText(shown) + 4 * k;
                fill.setColor(Ui.alpha(0xFFFFFFFF, a));
                c.drawRect(cx, ty - 24 * k, cx + 3 * k, ty + 24 * k, fill);
            }
        }
        if (state == LISTENING) {
            // A pulse at the end of the field while the microphone listens.
            float p = 0.5f + 0.5f * (float) Math.sin(seconds * 6);
            fill.setColor(Ui.alpha(0xFFFF453A, a * (0.55f + 0.45f * p)));
            c.drawCircle(r.right - 50 * k, ty, (10 + 5 * p) * k, fill);
        }

        // Speak and Type.
        float y = top + 310 * k, bh = 84 * k, x = left;
        String[] names = {state == LISTENING ? Ui.tr("Listening…") : Ui.tr("Speak"), Ui.tr("Type")};
        String[] syms = {"mic", "search"};
        for (int i = 0; i < 2; i++) {
            float bw = 36 * k + 30 * k + 16 * k + button.measureText(names[i]) + 40 * k;
            r.set(x, y, x + bw, y + bh);
            boolean f = focus == i;
            if (f) {
                fill.setColor(Ui.alpha(0x66000000, a));
                c.drawRoundRect(r.left, r.top + 10 * k, r.right, r.bottom + 10 * k, bh / 2, bh / 2, fill);
            }
            fill.setColor(Ui.alpha(f ? 0xFFFFFFFF : 0x33FFFFFF, a));
            c.drawRoundRect(r, bh / 2, bh / 2, fill);
            int ink = Ui.alpha(f ? Tokens.INK : Tokens.TEXT, a);
            Symbols.draw(context, c, syms[i], r.left + 36 * k + 15 * k, r.centerY(), 30 * k, ink);
            button.setColor(ink);
            c.drawText(names[i], r.left + 36 * k + 30 * k + 16 * k, r.centerY() + Ui.halfCap(button), button);
            x = r.right + 24 * k;
        }

        // Suggestions.
        y = top + 460 * k;
        label.setAlpha(alpha);
        c.drawText(Ui.tr("TRY"), left, y, label);
        String[] chips = chips();
        x = left;
        float ch = 66 * k;
        for (int i = 0; i < chips.length; i++) {
            float cw = 30 * k + chip.measureText(chips[i]) + 30 * k;
            r.set(x, y + 24 * k, x + cw, y + 24 * k + ch);
            boolean f = focus == FIRST_CHIP + i;
            fill.setColor(Ui.alpha(f ? 0xFFFFFFFF : 0x1FFFFFFF, a));
            c.drawRoundRect(r, ch / 2, ch / 2, fill);
            chip.setColor(Ui.alpha(f ? Tokens.INK : Tokens.TEXT, a));
            c.drawText(chips[i], r.left + 30 * k, r.centerY() + Ui.halfCap(chip), chip);
            x = r.right + 18 * k;
        }

        // What's happening.
        y = top + 640 * k;
        if (state == ASKING) {
            spinner.setAlpha(alpha);
            Ui.spinner(c, left + 16 * k, y - 10 * k, 14 * k, seconds * 1.3f, spinner);
            status.setColor(Ui.alpha(Tokens.TEXT_SECONDARY, a));
            c.drawText(Gemini.on() ? Ui.tr("Asking Gemini…") : Ui.tr("Searching…"), left + 48 * k, y, status);
        } else if (state == ERROR && !message.isEmpty()) {
            status.setColor(Ui.alpha(0xFFFF9F0A, a));
            c.drawText(fit(message, status, right - left), left, y, status);
        } else {
            status.setColor(Ui.alpha(Tokens.TEXT_DIM, a));
            c.drawText(Ui.tr("OK chooses · Back closes"), left, y, status);
        }
        footer.setAlpha(Math.round(0x99 * a));
        c.drawText(Gemini.on() ? Ui.tr("Gemini suggests; each title is checked on TMDB for where it streams in your country.")
                : Ui.tr("Titles from TMDB, with where they stream in your country."), left, panel.bottom - 50 * k, footer);
    }

    private static String fit(String s, Paint p, float width) {
        return TextUtils.ellipsize(s, new TextPaint(p), width, TextUtils.TruncateAt.END).toString();
    }
}
