package io.freemytv;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * EndGrid iQTV+'s mark, drawn (the launcher icon and banner are the same drawing, as PNGs): a 3 × 3 grid of rounded
 * cells, the last one lit from cyan to violet, the grid's end. Kept small and quiet: the artwork is the show.
 */
final class Brand {
    private Brand() {}

    static final String NAME = "EndGrid iQTV+";
    static final int CYAN = 0xFF3CE0FF, VIOLET = 0xFF8B5CFF;

    private static final Paint CELL = new Paint(Paint.ANTI_ALIAS_FLAG), LIT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final RectF R = new RectF();

    /** The mark centered at (cx, cy), `size` wide. */
    static void drawMark(Canvas c, float cx, float cy, float size, float alpha) {
        float gap = size * 0.07f, cell = (size - 2 * gap) / 3, rad = cell * 0.32f;
        float left = cx - size / 2, top = cy - size / 2;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                float x = left + col * (cell + gap), y = top + row * (cell + gap);
                R.set(x, y, x + cell, y + cell);
                if (row == 2 && col == 2) {
                    // A soft glow under the lit cell, then the cell.
                    for (int i = 4; i >= 1; i--) {
                        float grow = cell * 0.1f * i;
                        CELL.setColor(Ui.alpha(CYAN, 0.06f * alpha));
                        c.drawRoundRect(x - grow, y - grow, x + cell + grow, y + cell + grow, rad + grow, rad + grow, CELL);
                    }
                    LIT.setShader(new LinearGradient(x, y, x + cell, y + cell, CYAN, VIOLET, Shader.TileMode.CLAMP));
                    LIT.setAlpha(Math.round(255 * alpha));
                    c.drawRoundRect(R, rad, rad, LIT);
                } else {
                    CELL.setColor(Ui.alpha(0xFFFFFFFF, (row + col <= 1 ? 0.28f : 0.18f) * alpha));
                    c.drawRoundRect(R, rad, rad, CELL);
                }
            }
        }
    }
}
