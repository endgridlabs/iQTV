package io.freemytv;

/**
 * EndGrid iQTV+'s design tokens for the Top Shelf, More Info and Settings pages: colors, type sizes, spacing, radii,
 * focus and timing, in one place (pixels on a 1920 × 1080 layout; screens scale them by their `k`).
 *
 * The look follows tvOS's dark, layered style without its assets: white text in four strengths over artwork, a white
 * focus that grows and rises, translucent dark panels, rounded corners. The brand's cyan and violet stay in the mark
 * (Brand): the artwork is the color on screen.
 */
final class Tokens {
    private Tokens() {}

    // Colors (ARGB).
    static final int TEXT = 0xFFFFFFFF;             // titles, focused labels
    static final int TEXT_SECONDARY = 0xD9FFFFFF;   // details under a title
    static final int TEXT_TERTIARY = 0xB3FFFFFF;    // descriptions, row labels
    static final int TEXT_DIM = 0x99FFFFFF;         // unselected tabs, hints
    static final int INK = 0xFF1C1C1E;              // text on a focused (white) element
    static final int SURFACE = 0x40FFFFFF;          // a button at rest (translucent over the artwork)
    static final int PANEL = 0xE6141416;            // More Info's panel (opaque enough to read over any photo)
    static final int SCRIM = 0x99000000;            // behind More Info
    static final int PLACEHOLDER = 0xFF2C2C2E;      // a card whose picture isn't there
    static final int ACCENT = Brand.CYAN;           // the brand, sparingly

    // Type sizes.
    static final float GREETING = 48, TAB = 27, HERO_TITLE = 68, HERO_META = 28, HERO_OVERVIEW = 25, ROW_LABEL = 27,
            CARD_TITLE = 26, BUTTON = 28;

    // Spacing and size.
    static final float MARGIN = 80;           // tvOS safe area at the sides
    static final float GAP = 32, ROW_PITCH = 250, CARD_W = 268, CARD_H = 151;

    // Corners.
    static final float RADIUS_CARD = 14, RADIUS_PANEL = 34;

    // Focus: how much a focused element grows, and its shadow.
    static final float FOCUS_GROW_CARD = 0.1f, FOCUS_GROW_BUTTON = 0.06f, FOCUS_GROW_TAB = 0.05f;
    static final float SHADOW_ALPHA = 0.55f, GLOW_ALPHA = 0.12f;

    // Timing (ms): the photo's crossfade, the title's text fade, how long the focus rests before the photo follows
    // (browsing a row at a normal pace changes only the text; each photo change is a decode and a full-screen fade).
    static final long PHOTO_FADE = 450, TEXT_FADE = 260, SETTLE = 500;
}
