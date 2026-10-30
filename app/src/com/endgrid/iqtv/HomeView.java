package com.endgrid.iqtv;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.tv.TvInputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The home screen, styled after the Apple TV app with the macOS 27 glass:
 *  - at the top left, the "For You" title and the tabs (Explore, Movies, Shows); at the top right, icon-only glass
 *    buttons (Live TV, HDMI Inputs, Settings), with the focused one's name below it, and the small crypto quotes
 *    (logo + price) at the right end of the tab row;
 *  - three cards with the titles recommended for right now in the selected tab (Recs, from TMDB); the background is
 *    the first card's photo, heavily blurred and almost white (Backdrop); without picks, one of your own photos
 *    (Photos) the same way, or else a light gradient;
 *  - at the bottom, the glass shelf with the apps (icons in each brand's color, like the tvOS Dock; the focused one
 *    grows);
 *  - ↓ on the shelf: everything moves up and the bottom drawer appears (inputs, shortcuts, weather for the next hours
 *    and quotes with the 24 h change); the background lightens (darkens at night). ↑ or Back closes it;
 *  - from sunset to sunrise, a night look: dark background, smoked glass and white text.
 * Measurements are in a 1920×1080 space (k = width / 1920). The screen is split into layers so the TV's weak GPU only
 * redraws what changed.
 */
final class HomeView extends ViewGroup implements Choreographer.FrameCallback, HomeData.Listener, Recs.Listener {
    // tvOS grid (Human Interface Guidelines): content inside the safe area, 80 px from the sides and 60 from top and
    // bottom; 40 px between items; 7 columns of 217 px (the apps). The shelf glass extends 36 px past the tiles (the
    // same padding on all four sides) and the tile corners are concentric with it (glass radius = radius + padding).
    private static final float MARGIN = 80, CONTENT_R = 1840;
    // Hero, anchored at the bottom (a two-line title grows upward).
    private static final float HERO_TITLE_W = 900;
    // Shelf: tiles with corners concentric with the glass (glass radius = tile radius + padding).
    // Shelf like the tvOS 27 Dock: icons in each brand's color, 48 apart, and the glass bar hugging them with 40 of
    // padding (the focused icon grows 20% without touching its edge). The icons are 16:9, the shape of the apps' TV
    // banners, so those are never stretched: up to 224 × 126, 178 × 100 with eight apps, 133 × 75 with ten
    // (HomeApps.shelfVisible; past that the shelf scrolls).
    private static final float SHELF_PAD = 40, TILE_GAP = 48;
    private static final float TILE_RADIUS = 22;
    // Tile focus (Apple TV style): it grows from the center and its shadow drops (it "rises" toward the viewer).
    private static final float FOCUS_GROW = 0.2f;
    // Corner capsule.
    // Corner capsule: the height and top offset of the tvOS tab bar (68 and 46).
    private static final float CAPSULE_TOP = 46, CAPSULE_H = 68, BUTTON = 56;
    // Bottom drawer (measured with it open; everything moves up by SHIFT).
    private static final float SHIFT = 560, ROW_TITLE = 518, ROW_TOP = 540, ROW_H = 126, CARDS_TOP = 708, CARDS_H = 300;
    // "All Apps" sheet.
    private static final int COLUMNS = 5, VISIBLE_ROWS = 3;
    private static final float GRID_W = 300, GRID_H = 169, GRID_GAP = 40, GRID_ROW = 239, GRID_TOP = 196, GRID_RADIUS = 22;
    private static final int SMOKE = 0xFF111113, INK = 0xFF1C1C1E, WHITE = 0xFFF5F5F7;
    private static final int UP_COLOR = 0xFF30D158, DOWN_COLOR = 0xFFFF453A;
    private static final long TOAST_MS = 6000, FADE_MS = 800;
    /** Blur radii of the shadow and shadowDeep paints (Shadows needs them to draw the same blur on older TVs). */
    private static final float SHADOW_BLUR = 26, DEEP_BLUR = 30;
    private static final int SKY = 0, HERO = 1, STATUS = 2, PLATTER = 3, TILES = 4, DRAWER = 5, SHEET = 6, TOAST = 7;
    private static final int Z_STATUS = 0, Z_HERO = 1, Z_SHELF = 2, Z_DRAWER = 3;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    // Light design (the L_ constants): title, tab bar, three recommendation cards and the shelf.
    // The tab bar is a 68 px capsule; the cards sit on the 5-column grid (the wide one spans 3).
    private static final float L_TITLE_BASE = 130, L_TABBAR_TOP = 158, L_TABBAR_H = 68, L_CARDS_TOP = 262, L_CARD_H = 442;
    private static final float L_TALL_W = 320, L_CARD_GAP = 40, L_BUTTON = 68, L_BUTTON_GAP = 20, L_BUTTON_CY = 103;
    private static final float L_PLATTER_TOP = 814, L_TILE_H = 126;
    private static final float L_DATE_BASE = 330, L_CLOCK_BASE = 540, L_WEATHER_BASE = 626, L_QUOTES_BASE = 694;
    private static final int L_INK = 0xFF1D1D1F, L_DIM = 0xFF6E6E73, L_FAINT = 0xFF8E8E93;
    private static String[] tabs;
    private static int tabsGeneration = -1;
    private static final String[] TAB_KIND = {null, "movie", "tv"};
    private static final int Z_TOP = 4, Z_CARDS = 5;
    /**
     * Top Shelf design (config.properties "design", the default), like the tvOS home screen: the focused title fills the
     * screen (its photo with the scrims already in it: one opaque texture); the greeting and the round buttons at the
     * top; the title, its details and Play / Not for me at the left; below, a vertical stack that scrolls: the app
     * dock, then the rows (Continue Watching, Featured, the Tamil rows, Trending, New Releases), each scrolling on its
     * own and remembering its place. Always dark, as tvOS. Only the focused item casts a shadow and the dock has no
     * glass: a TV GPU runs out of fill rate first, so a frame is the photo plus small things.
     */
    static final boolean TOP_SHELF = !"cards".equalsIgnoreCase(Config.DESIGN.trim());
    private static final int Z_ACTIONS = 6, Z_TABS = 7;
    private static final float S_GREET_BASE = 96, S_TABS_TOP = 124, S_TAB_H = 56, S_TITLE_BOTTOM = 372, S_TITLE_W = 1000,
            S_META_BASE = 412, S_OVERVIEW_BASE = 452, S_OVERVIEW_LINE = 34;
    private static final float S_ACTIONS_TOP = 512, S_ACTION_H = 60, S_ACTION_GAP = 20;
    /** The top tabs: Home, Movies, TV Shows, Continue Watching, Discover (filters of the rows), and Apps (All Apps). */
    private static final int TAB_HOME = 0, TAB_MOVIES = 1, TAB_SHOWS = 2, TAB_APPS = 3, TAB_SEARCH = 4;
    private final Spring[] tabFocus = new Spring[5];
    private final RectF[] tabRects = new RectF[5];
    private int shelfTab, tabIndex;
    private boolean infoOpen;       // More Info: the title's details over the screen (Back closes it)
    // The weather pill at the end of the top bar (→ from Settings), and the Weather panel it opens (shares More Info's
    // fade, `info`; `overlayWeather` says which of the two the fade draws, until it has faded out).
    private boolean weatherFocus, weatherOpen, overlayWeather;
    // The Search screen (SearchPanel): open, what it draws over (as overlayWeather), typing with the TV's keyboard
    // into searchTyped, its focus, what's happening and the question; the results came from Gemini (their row's name).
    private boolean searchOpen, overlaySearch, searchTyping, searchByGemini;
    private int searchFocus, searchState;
    private String searchText = "", searchMessage = "";
    private final android.text.SpannableStringBuilder searchTyped = new android.text.SpannableStringBuilder();
    private SearchPanel searchPanel;
    private android.speech.SpeechRecognizer recognizer;
    private long searchOpenedAt;
    // Your initial at the right of the top bar (→ after the weather): OK opens Settings.
    private boolean profileFocus;
    private final Spring profilePill = new Spring(0.26f, 0.85f, 0);
    private final Spring weatherPill = new Spring(0.26f, 0.85f, 0);
    private WeatherPanel weatherPanel;
    private final Spring info = new Spring(0.3f, 0.9f, 0);
    private StaticLayout infoText;
    private String infoFor = "";
    // The stack: the dock's icons at S_DOCK_TOP; the rows S_ROW_PITCH apart; a focused row rests at S_ANCHOR (just
    // under the buttons), and what scrolls above it fades out over S_FADE.
    private static final float S_DOCK_TOP = 640, S_DOCK_GAP = 70, S_ROW_PITCH = 250, S_ANCHOR = 600, S_FADE = 150;
    /** The Top Shelf's dock: apps shown at once, the tiles' height (16:9: 252 × 142), and its glass's margins. */
    private static final int S_DOCK_APPS = 6;
    private static final float S_TILE_H = 142, S_TRAY_TOP = 26, S_TRAY_BOTTOM = 74, S_TRAY_RADIUS = 44;
    private static final float S_LABEL_DY = 28, S_THUMB_DY = 48;
    private static final float S_THUMB_W = Tokens.CARD_W, S_THUMB_H = Tokens.CARD_H, S_THUMB_GAP = Tokens.GAP;
    private static final float S_GROW = Tokens.FOCUS_GROW_CARD;
    private static final long SHELF_FADE_MS = Tokens.PHOTO_FADE, SHELF_TEXT_MS = Tokens.TEXT_FADE, SHELF_SETTLE_MS = Tokens.SETTLE;
    private static final int ROW_SLOTS = 3;     // the cards design's arrays (the Top Shelf's rows have their own)
    private final Spring[] actionFocus = new Spring[3];
    private final RectF[] actionRects = {new RectF(), new RectF(), new RectF()};

    /** A row of the stack: its titles, which one has (or last had) the focus, and its own scroll. */
    private static final class Row {
        final String id, title;
        List<Recs.Pick> items;
        Spring[] focus;
        int index, first;
        final Spring scroll = new Spring(0.32f, 0.92f, 0, 400);   // px

        Row(String id, String title, List<Recs.Pick> items) {
            this.id = id;
            this.title = title;
            setItems(items);
        }

        void setItems(List<Recs.Pick> list) {
            items = list;
            Spring[] next = new Spring[list.size()];
            for (int i = 0; i < next.length; i++) next[i] = new Spring(0.32f, 0.78f, 0);
            focus = next;
            index = Math.min(index, Math.max(0, list.size() - 1));
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private int stackIndex;                 // 0 the dock, i ≥ 1 rows.get(i - 1)
    private final Spring stack = new Spring(0.34f, 0.94f, 0, 400);   // the stack's scroll, px
    private int actionIndex;
    private Recs.Pick shelfPick;            // the title on screen: the row's focused one (or the last one focused)
    private Bitmap shelfImage, shelfImageFrom;
    private long shelfFadeAt, shelfTextAt;
    private StaticLayout shelfTitle;
    private String shelfTitleKey = "";
    private Paint sProfile, sGreet, sMeta, sRowLabel, sThumbTitle, sAction, sTab, sTabOn, sBrand, sBrandAccent, sClock, sAvatar, sHeroLabel,
            sCardTitle, sCardSub;
    private LinearGradient brandShader, avatarShader;
    private float brandShaderX, avatarX;
    private TextPaint sTitle, sOverview, sInfoTitle, sInfoBody;
    private StaticLayout shelfOverview;
    private List<Recs.Pick> searchResults;  // a voice search's results: the first row until Back
    private String searchQuery = "";
    private final Runnable settleShelf = this::updateShelfImage;   // the photo follows the focus once it stops

    private final Activity activity;
    private final Scene scene = new Scene();
    private final HomeData data;
    private final Recs recs;
    private final Click click;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences ui;
    private final TvInputManager tvInputs;
    private final Spoken spoken = new Spoken(this, new Spoken.Source() {
        @Override public void items(List<Integer> out) { a11yItems(out); }
        @Override public String text(int id) { return a11yText(id); }
        @Override public boolean bounds(int id, Rect out) { return a11yBounds(id, out); }
        @Override public int focused() { return a11yFocused(); }
        @Override public void focus(int id) { a11yFocus(id); }
        @Override public void click(int id) { activate(); }
        @Override public boolean clickable(int id) { return (id - 1) / 1000 != WIDGETS; }
        @Override public boolean holdable(int id) { return (id - 1) / 1000 == GRID && canToggleShelf((id - 1) % 1000); }
        @Override public void hold(int id) { a11yHold(); }
    });
    private final RectF a11yRect = new RectF();
    private final Layer sky, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, toastLayer;
    private int zone = Z_SHELF;

    // Night look (like Apple's "Automatic" appearance): darker from sunset to sunrise, with a 2 s transition.
    private static final long NIGHT_MS = 2000;
    private boolean nightTarget;
    private float night, nightFrom;     // 0 = day, 1 = night
    private long nightAt;
    private final int[] glassNight = new int[Backdrop.GW * Backdrop.GH], frostNight = new int[Backdrop.FW * Backdrop.FH];
    private final int[] neutralGlassPx = new int[Backdrop.GW * Backdrop.GH], neutralFrostPx = new int[Backdrop.FW * Backdrop.FH];
    private boolean nightPair;          // the glass holds both photos (day and night), blended by `night`
    private Shader nightGradient;

    // At the top: tabs (0 to 2) and icon-only buttons (3 to 5); the three cards
    private int tab, topIndex, cardIndex;
    private int widgetIndex;    // with TalkBack on, the drawer's weather (0) or quotes (1) card can take the focus
    private List<Recs.Pick> cards = new ArrayList<>();
    // The three tabs, then the round buttons at the top right (up to HomeApps.MAX_TOP).
    private final Spring[] topFocus = new Spring[4 + HomeApps.MAX_TOP];   // + Search on the Top Shelf
    private final RectF[] topRects = new RectF[4 + HomeApps.MAX_TOP];
    // The three cards, or on the Top Shelf the row (cardIndex is then the row's focused title).
    private final Spring[] cardFocus = new Spring[TOP_SHELF ? ROW_SLOTS : 3];
    private final RectF[] cardRects = new RectF[TOP_SHELF ? ROW_SLOTS : 3];
    private final List<HomeApps.Item> lightButtons = new ArrayList<>();
    private Paint lTitle, lTab, lTabOn, lCardTitle, lCardMeta, lQuote, lTip, lDate, lClock, lWeather, lClockQuote;
    private Shader lightGradient;

    // Shelf
    private List<HomeApps.Item> shelf = new ArrayList<>();
    private Spring[] tileFocus = new Spring[0];
    private RectF[] tileRects = new RectF[0];
    private final Map<String, Bitmap> crispLogos = new HashMap<>();   // logos already sized for the focused tile
    /** Each app's tile like its Apple TV icon: brand colors (top, bottom) and logo (0 = as is, 1 = white, 2 = dark). */
    private static final Map<String, int[]> TILE_STYLE = new HashMap<>();
    static {
        TILE_STYLE.put("netflix", new int[] {0xFF0A0A0A, 0xFF000000, 0});
        TILE_STYLE.put("youtube", new int[] {0xFF1C1C1E, 0xFF09090A, 0});
        TILE_STYLE.put("globoplay", new int[] {0xFFFF8A1F, 0xFFFF2D5A, 1});
        TILE_STYLE.put("prime", new int[] {0xFF2AA8FF, 0xFF0A68D4, 1});
        TILE_STYLE.put("disney", new int[] {0xFF173584, 0xFF08143F, 0});
        TILE_STYLE.put("max", new int[] {0xFF5C30E8, 0xFF221684, 0});
        TILE_STYLE.put("appletv", new int[] {0xFF1C1C1E, 0xFF000000, 0});
    }
    private final android.graphics.PorterDuffColorFilter whiteLogo =
            new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
    private final RectF platterRect = new RectF();
    private int shelfIndex;
    /** The first app the shelf shows: with more apps than fit, it scrolls with the focus. */
    private int shelfFirst;
    private final int shelfVisible;

    // Moving apps on the shelf (as on Apple TV): hold OK on a tile, then ← → move it, OK drops it, Back cancels. The
    // lifted tile wiggles; the others slide to their new places (each slot's x offset is a spring that returns to 0).
    private static final long HOLD_MS = 500, WIGGLE_MS = 300;
    private static final float LIFT_GROW = 0.08f, WIGGLE_DEG = 1.5f;
    private boolean reordering, reorderAnim, okHeld, holdArmed;
    private int liftIndex = -1;
    private long reorderAt;
    private float wiggle;
    private Spring[] tileSlide = new Spring[0];
    private final Spring moveLift = new Spring(0.3f, 0.72f, 0);
    private List<HomeApps.Item> reorderOrigin;
    private final Runnable holdStart = () -> { if (this.okDown && this.holdArmed) hold(); };

    // Corner capsule
    private List<HomeApps.Item> statusItems = new ArrayList<>();
    private Spring[] statusFocus = new Spring[0];
    private RectF[] statusRects = new RectF[0];
    private final RectF capsuleRect = new RectF();
    private float capsuleInfoW;
    private int statusIndex;

    // Hero and background
    private List<Recs.Pick> picks = new ArrayList<>();
    private int pickIndex, heroSlide = 1;
    private Hero hero, heroFrom;
    private long heroFadeAt, bgFadeAt;
    private final Spring heroFocus = new Spring(0.28f, 0.85f, 0);
    private final RectF heroButton = new RectF();
    private Backdrop bgFrom, bgTo;     // null = gradient (no photo)
    private final int[] frostMix = new int[Backdrop.FW * Backdrop.FH];

    // Bottom drawer
    private List<HomeApps.Item> drawerItems = new ArrayList<>();
    private int drawerInputs;          // how many leading items are inputs (the rest are shortcuts)
    private Spring[] drawerFocus = new Spring[0];
    private RectF[] drawerRects = new RectF[0];
    private final RectF weatherCard = new RectF(), quotesCard = new RectF();
    private final Spring drawer = new Spring(0.46f, 0.92f, 0);
    private boolean drawerOpen;
    private int drawerIndex;

    // "All Apps" sheet
    private List<HomeApps.Item> others = new ArrayList<>();
    private Spring[] gridFocus = new Spring[0];
    private String[] gridLabels;
    private final Spring sheet = new Spring(0.38f, 0.9f, 0);
    private final Spring scroll = new Spring(0.34f, 1f, 0);
    private final RectF focusedCell = new RectF();
    private boolean sheetOpen, shelfEdited;
    private int gridIndex;
    private long toggledAt;

    // Toasts
    private final ArrayDeque<String> toasts = new ArrayDeque<>();
    private StaticLayout toast;
    private long toastAt;

    // Frames
    private boolean running, tilesDirty, statusDirty, heroDirty, drawerDirty, gridDirty, motionDirty = true;
    private boolean drawerWasMoving, idle;
    private long lastNanos, resumedAt, lastStep;
    private static final long BURST_MS = 1500;
    private int w, h;
    private float k = 1;
    private String clockText = "", dateText = "", weatherLine = "";
    /** "Off in 42 min" next to the round buttons while the sleep timer runs (Settings > Sleep Timer); "" otherwise. */
    private String sleepText = "";
    private long weatherLineAt = -1;
    private int weatherLinePrefs = -1;
    private String weatherLineLanguage = "";
    private long clockMinute = -1;
    private boolean okDown, backDown;

    private Paint labelPaint, metaPaint, clockPaint, buttonPaint, capsuleText, tipPaint, quotePaint, staleQuote;
    private Paint rowTitle, drawerLabel, cardTitle, cardText, cardDim, cardDimRight, bigTemp, hourPaint, hourTemp;
    private Paint pricePaint, changePaint, sheetTitle;
    private TextPaint titlePaint, gridLabel, toastPaint;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowSoft = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowDeep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shadows shadows = new Shadows();
    private final Paint imageFill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tileFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF(), cell = new RectF(), screen = new RectF();
    private final TextPaint fitPaint = new TextPaint();
    private final RectF gridCell = new RectF();
    private LinearGradient[] tileShaders = new LinearGradient[0];
    private LinearGradient tileGlow, cardSheen;
    private float tileShaderH, cardSheenH;
    private LinearGradient[] lightSheens = new LinearGradient[0], lightRims = new LinearGradient[0];
    private float[] lightShaderW = new float[0];
    private android.graphics.ColorFilter veilFilter;
    private float veilAmount = -1;
    private int veilColor;
    private HomeData.Weather textWeather;
    private long textPricesAt = -1, textMinute = -1;
    private int textPrefs = -1;
    private String textLanguage;
    private String weatherTitle, nowTemp, highLow, sunriseText, sunsetText, staleText;
    private final String[] hourText = new String[6], hourTempText = new String[6];
    private final String[] priceText = new String[HomeData.COINS.length], changeText = new String[HomeData.COINS.length];

    /** What the hero shows: a recommendation or, without any, the date, the time and the weather. */
    private static final class Hero {
        String label = "", title = "", meta = "";
        boolean clock, button;
        int dots, dot;
        StaticLayout titleLayout;

        boolean sameText(Hero o) {
            return o != null && clock == o.clock && label.equals(o.label) && title.equals(o.title) && meta.equals(o.meta);
        }
    }

    /** One layer of the screen. It draws in full-screen coordinates: the glass needs each pixel's real position. */
    private final class Layer extends View {
        private final int kind;

        Layer(Context c, int kind) {
            super(c);
            this.kind = kind;
        }

        /** The Top Shelf's dock fades by the view's alpha: per draw, without an offscreen buffer for the whole layer. */
        @Override public boolean hasOverlappingRendering() {
            return !(TOP_SHELF && kind == TILES);
        }

        @Override protected void onDraw(Canvas c) {
            if (labelPaint == null) return;            c.translate(-getLeft(), -getTop());
            // The layer may be shifted (bottom drawer): the glass reads the background from where it really is. The
            // drawer and the top buttons are always drawn as if resting in place (they are only visible there and are
            // not redrawn while they move).
            scene.setOffset(0, kind == DRAWER || kind == STATUS ? 0 : getTranslationY());
            shadows.dither = dark() > 0.5f;
            switch (kind) {
                case SKY: if (TOP_SHELF) drawShelfSky(c); else drawSky(c); break;   // opaque: nothing under it shows
                case HERO: if (TOP_SHELF) drawTopShelf(c); else drawLightTop(c); break;
                case STATUS: drawLightButtons(c); break;
                case PLATTER: drawPlatter(c); break;
                case TILES: drawTiles(c); break;
                case DRAWER: drawDrawer(c); break;
                case SHEET: drawSheet(c); break;
                default: drawToast(c); break;
            }
            scene.setOffset(0, 0);
        }
    }

    private final Runnable reloadSoon = this::reload;
    private static final long APPS_MAX_AGE_MS = 15 * 60_000;
    private final LauncherApps launcherApps;
    private boolean appsStale = true;
    private int appsVersion, appsGeneration;
    private long appsAt;

    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) { reloadSoon(); }
        @Override public void onInputAdded(String inputId) { reloadSoon(); }
        @Override public void onInputRemoved(String inputId) { reloadSoon(); }
    };

    private void reloadSoon() {
        handler.removeCallbacks(reloadSoon);
        handler.postDelayed(reloadSoon, 500);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            wake();
            data.refreshIfStale();
            recs.refreshIfStale();
            checkNotices();
            updatePalette();
            checkNight(true);
            updatePicks();      // three new ones every hour
            updateSleep();
            handler.postDelayed(this, 15_000);
        }
    };

    HomeView(Activity a) {
        super(a);
        activity = a;
        data = HomeData.get(a);
        recs = Recs.get(a);
        click = new Click(a);
        // The Top Shelf's dock: 6 apps at a time, big enough to read across the room (the rest scroll).
        shelfVisible = TOP_SHELF ? Math.min(S_DOCK_APPS, HomeApps.shelfVisible(a)) : HomeApps.shelfVisible(a);
        ui = a.getSharedPreferences("home-ui", Context.MODE_PRIVATE);
        tvInputs = (TvInputManager) a.getSystemService(Context.TV_INPUT_SERVICE);
        launcherApps = (LauncherApps) a.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        if (launcherApps != null) launcherApps.registerCallback(packages, handler);
        setFocusable(true);
        sky = new Layer(a, SKY);
        heroLayer = new Layer(a, HERO);
        status = new Layer(a, STATUS);
        platter = new Layer(a, PLATTER);
        tiles = new Layer(a, TILES);
        drawerLayer = new Layer(a, DRAWER);
        sheetLayer = new Layer(a, SHEET);
        toastLayer = new Layer(a, TOAST);
        // Hardware layers on the GPU: only the one that changed is redrawn; moving, fading and scaling stay cheap.
        // Not on Android 8 to 11: on older GPUs (a Mi Box 3's Mali-450) redrawing and compositing a big layer on every
        // frame cost more than drawing straight to the screen (measured: focus on the picks 33 → 12 ms a frame, the
        // drawer 19 → 17 ms).
        for (View v : new View[] {heroLayer, status, platter, tiles, drawerLayer, sheetLayer}) v.setLayerType(Ui.LEGACY ? LAYER_TYPE_NONE : LAYER_TYPE_HARDWARE, null);
        // The Top Shelf's dock glass (the platter layer) is redrawn only when the dock moves: a texture even on older TVs,
        // so its blurred fill isn't painted again on every frame (measured on a OnePlus TV: 12% janky frames, 6.5% without).
        if (TOP_SHELF) platter.setLayerType(LAYER_TYPE_HARDWARE, null);
        for (View v : new View[] {sky, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, toastLayer}) {
            v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);   // TalkBack reads the items (Spoken)
            addView(v);
        }
        setContentDescription("EndGrid iQTV+");
        drawerLayer.setVisibility(INVISIBLE);
        for (int i = 0; i < topFocus.length; i++) {
            topFocus[i] = new Spring(0.26f, 0.85f, 0);
            topRects[i] = new RectF();
        }
        for (int i = 0; i < cardFocus.length; i++) {
            cardFocus[i] = new Spring(0.32f, 0.78f, 0);
            cardRects[i] = new RectF();
        }
        for (int i = 0; i < actionFocus.length; i++) actionFocus[i] = new Spring(0.3f, 0.82f, 0);
        for (int i = 0; i < tabFocus.length; i++) {
            tabFocus[i] = new Spring(0.26f, 0.85f, 0);
            tabRects[i] = new RectF();
        }
        setLightButtons(HomeApps.topButtons());
        if (TOP_SHELF) recs.setShelf(); else recs.setLight(true);
        checkNight(false);
        sheetLayer.setVisibility(INVISIBLE);
        scene.setMotion(0.6f, 0f);
        updatePalette();
        // First time: right away (a cold start opens with the apps already there); missing logos come from reload().
        setShelf(HomeApps.shelf(a, false));
        setStatusItems(statusList(a));
        setDrawerItems(drawerList(a));
        if (TOP_SHELF) {
            // Opens as Home does (after a restart, or a setting that redraws everything): at the top, not on Your Apps.
            updatePicks();
            zone = actionCount() > 0 ? Z_ACTIONS : Z_TABS;
            tabIndex = TAB_HOME;
        }
    }

    // MARK: - Lifecycle (called by HomeActivity)

    void resume() {
        if (running) return;
        running = true;
        checkNight(false);
        long now = SystemClock.uptimeMillis();
        // Some remotes (Xiaomi's) send one Home press as a burst of 5 to 8, and each one pauses and resumes the home
        // screen: those after the first change nothing, so the lists are read only once.
        boolean again = resumedAt != 0 && now - resumedAt < BURST_MS;
        resumedAt = now;
        lastNanos = 0;
        lastStep = 0;
        data.addListener(this);
        recs.addListener(this);
        data.refreshIfStale();
        recs.refreshIfStale();
        Photos.importInbox(getContext(), ownReady);   // photos a computer just sent
        if (tvInputs != null) tvInputs.registerCallback(inputCallback, handler);
        handler.postDelayed(tick, 15_000);
        if (TOP_SHELF) {
            handler.removeCallbacks(rotateHero);
            handler.postDelayed(rotateHero, HERO_ROTATE_MS);
        }
        checkNotices();
        if (!again) reload();
        updatePalette();
        updatePicks();
        updateSleep();      // a timer just set in Settings shows at once, not at the next tick
        if (sheetOpen) closeSheet();
        sheet.snap(sheet.target);
        motionDirty = true;
        invalidateAll();
        idle = false;
        Choreographer.getInstance().postFrameCallback(this);
        spoken.readAgain();
    }

    void pause() {
        if (!running) return;
        running = false;
        cancelReorder(true);
        handler.removeCallbacks(holdStart);
        okHeld = holdArmed = false;
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tick);
        handler.removeCallbacks(rotateHero);
        handler.removeCallbacks(reloadSoon);
        data.removeListener(this);
        recs.removeListener(this);
        if (tvInputs != null) tvInputs.unregisterCallback(inputCallback);
    }

    void destroy() {
        pause();
        if (launcherApps != null) launcherApps.unregisterCallback(packages);
        click.release();
    }

    /** Starting state (Home pressed while the home screen is showing): first app focused, drawer and sheet closed. */
    void reset(boolean animated) {
        wake();
        untouched = true;
        cancelReorder(!animated);
        zone = Z_SHELF;
        shelfIndex = 0;
        sheetOpen = false;
        drawerOpen = false;
        if (animated) {
            sheet.target = 0;
            drawer.target = 0;
        } else {
            sheet.snap(0);
            drawer.snap(0);
        }
        if (pickIndex != 0 && !picks.isEmpty()) {
            pickIndex = 0;
            updatePicks();
        }
        if (TOP_SHELF) {
            // Home: the Home tab at the top of the stack, every row back at its start, the search and More Info gone.
            searchResults = null;
            searchQuery = "";
            infoOpen = false;
            weatherOpen = false;
            weatherFocus = false;
            profileFocus = false;
            if (searchOpen) closeSearch();
            if (shelfTab != TAB_HOME) {
                shelfTab = TAB_HOME;
                rows.clear();
            }
            stackIndex = 0;
            heroIndex = 0;
            for (Row r : rows) {
                r.index = 0;
                r.first = 0;
            }
            zone = Z_ACTIONS;    // as tvOS opens: on the featured title's main button
            tabIndex = TAB_HOME;
            updatePicks();
            if (actionCount() == 0) zone = Z_TABS;
        }
        if (tab != 0) selectTab(0);
        retarget(!animated);
        motionDirty = true;
    }

    /** The round buttons at the top right (Settings > Top Buttons), with their names in the current language. */
    private void setLightButtons(List<HomeApps.Item> list) {
        lightButtons.clear();
        if (!TOP_SHELF) lightButtons.addAll(list);   // the Top Shelf's top bar has Search and Settings; Live TV is an app
        if (zone == Z_TOP && topIndex >= 3 + lightButtons.size()) {
            topIndex = 2 + lightButtons.size();
            if (topIndex < 3 && !picksOn()) zone = Z_SHELF;   // without the picks the tabs can't hold focus
            else if (topIndex < 3) selectTab(topIndex);
            retarget(true);
        }
        layoutLight();
        statusDirty = true;
    }

    /** The tab names, made again only when the language changes (they are drawn on every frame). */
    private static String[] tabs() {
        if (tabsGeneration != Ui.generation) {
            tabs = new String[] {Ui.tr("Explore"), Ui.tr("Movies"), Ui.tr("Shows")};
            tabsGeneration = Ui.generation;
        }
        return tabs;
    }

    @Override public void onData() {
        wake();
        updatePalette();
        checkNight(true);
        layoutStatus();
        heroDirty = statusDirty = drawerDirty = true;
        platter.invalidate();
    }

    @Override public void onRecs() {
        wake();
        updatePicks();
    }

    private void invalidateAll() {
        for (View v : new View[] {sky, heroLayer, status, platter, tiles, drawerLayer}) v.invalidate();
    }

    // MARK: - Size and layers

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        float kk = width / 1920f;
        measure(sky, width, height);
        measure(heroLayer, width, TOP_SHELF ? height : Math.round(790 * kk));   // the Top Shelf's rows go to the bottom
        measure(status, Math.round(1160 * kk), Math.round(240 * kk));
        // Sized to what they draw (the glass and its shadow; the grown tiles and their shadows): smaller layers are
        // cheaper for the GPU to composite on every frame of the drawer animation.
        measure(platter, width, Math.round((TOP_SHELF ? 360 : 320) * kk));   // the Top Shelf's: the dock's glass tray
        measure(tiles, width, Math.round((TOP_SHELF ? 360 : 270) * kk));   // the Top Shelf's: bigger tiles, their tray
        measure(drawerLayer, width, Math.round(600 * kk));
        measure(sheetLayer, width, height);
        measure(toastLayer, Math.round(1000 * kk), Math.round(220 * kk));
        setMeasuredDimension(width, height);
    }

    private static void measure(View v, int width, int height) {
        v.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l, height = b - t;
        float kk = width / 1920f;
        sky.layout(0, 0, width, height);
        place(heroLayer, 0, 0);
        place(status, width - status.getMeasuredWidth(), 0);
        place(platter, 0, Math.round((TOP_SHELF ? S_DOCK_TOP - 80 : 750) * kk));   // the Top Shelf's: with the dock
        place(tiles, 0, Math.round((TOP_SHELF ? S_DOCK_TOP - 80 : 790) * kk));   // the Top Shelf's dock is higher
        place(drawerLayer, 0, Math.round(470 * kk));
        sheetLayer.layout(0, 0, width, height);
        sheetLayer.setPivotX(width / 2f);
        sheetLayer.setPivotY(height / 2f);
        place(toastLayer, (width - toastLayer.getMeasuredWidth()) / 2, 0);
    }

    private static void place(View v, int x, int y) {
        v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        screen.set(0, 0, w, h);
        scene.setSize(w, h);
        makePaints();
        gridLabels = null;
        clockMinute = -1;
        updateClock();
        Symbols.warm(getContext(), "bell", 26 * k);
        Symbols.warm(getContext(), "bedtime", 21 * k);   // the sleep timer's "Off in", at lQuote's size
        layoutShelf();
        layoutStatus();
        layoutDrawer();
        layoutLight();
        hero = null;
        heroFrom = null;
        updatePicks();
        motionDirty = true;
        wake();
    }

    private void makePaints() {
        labelPaint = Ui.text(Ui.medium, 25 * k, 0xC7FFFFFF);
        labelPaint.setLetterSpacing(0.1f);
        titlePaint = new TextPaint(Ui.text(Ui.displayMedium, 76 * k, 0xFFFFFFFF));
        titlePaint.setLetterSpacing(0.005f);
        metaPaint = Ui.text(Ui.medium, 29 * k, 0xD1FFFFFF);
        metaPaint.setLetterSpacing(0.014f);
        clockPaint = Ui.numbers(Ui.thin, 176 * k, 0xFFFFFFFF);
        clockPaint.setLetterSpacing(-0.01f);
        for (Paint p : new Paint[] {labelPaint, titlePaint, metaPaint, clockPaint}) p.setShadowLayer(22 * k, 0, 2 * k, 0x59000000);
        lTitle = Ui.text(Ui.displaySemibold, 76 * k, L_INK);
        lTitle.setLetterSpacing(0.004f);
        lTab = Ui.text(Ui.medium, 30 * k, L_DIM);
        lTabOn = Ui.text(Ui.semibold, 30 * k, L_INK);
        lCardTitle = Ui.text(Ui.semibold, 31 * k, L_INK);
        lCardTitle.setLetterSpacing(0.012f);
        lCardMeta = Ui.text(Ui.medium, 25 * k, L_DIM);
        lQuote = Ui.numbers(Ui.regular, 21 * k, L_FAINT);
        lTip = Ui.text(Ui.semibold, 25 * k, L_INK);
        lTip.setTextAlign(Paint.Align.CENTER);
        lDate = Ui.text(Ui.semibold, 34 * k, L_DIM);
        lClock = Ui.numbers(Ui.light, 200 * k, L_INK);
        lClock.setLetterSpacing(-0.01f);
        lWeather = Ui.text(Ui.medium, 34 * k, L_DIM);
        lClockQuote = Ui.numbers(Ui.medium, 28 * k, L_DIM);
        lightGradient = null;
        // Top Shelf: no text shadows (the photo's scrims give the contrast; a shadow is a blur per glyph run).
        sGreet = Ui.text(Ui.displaySemibold, 48 * k, Tokens.TEXT);
        sGreet.setLetterSpacing(0.004f);
        sTab = Ui.text(Ui.medium, 25 * k, Tokens.TEXT_SECONDARY);
        sTabOn = Ui.text(Ui.semibold, 25 * k, Tokens.TEXT);
        sBrand = Ui.text(Ui.displaySemibold, 40 * k, Tokens.TEXT);
        sBrandAccent = Ui.text(Ui.displaySemibold, 40 * k, Tokens.TEXT);
        sClock = Ui.numbers(Ui.medium, 34 * k, Tokens.TEXT);
        sAvatar = Ui.text(Ui.semibold, 26 * k, Tokens.TEXT);
        sProfile = Ui.text(Ui.semibold, 22 * k, Tokens.TEXT);
        sHeroLabel = Ui.text(Ui.semibold, 28 * k, Tokens.TEXT);
        sCardTitle = Ui.text(Ui.semibold, 25 * k, Tokens.TEXT);
        sCardSub = Ui.text(Ui.medium, 21 * k, Tokens.TEXT);
        sTitle = new TextPaint(Ui.text(Ui.displaySemibold, 84 * k, Tokens.TEXT));
        sTitle.setLetterSpacing(0.002f);
        sMeta = Ui.text(Ui.medium, 28 * k, Tokens.TEXT_SECONDARY);
        sMeta.setLetterSpacing(0.012f);
        sOverview = new TextPaint(Ui.text(Ui.regular, 25 * k, Tokens.TEXT_TERTIARY));
        sInfoTitle = new TextPaint(Ui.text(Ui.displaySemibold, 54 * k, Tokens.TEXT));
        sInfoBody = new TextPaint(Ui.text(Ui.regular, 28 * k, Tokens.TEXT_SECONDARY));
        sRowLabel = Ui.text(Ui.semibold, 30 * k, 0xF2FFFFFF);
        sRowLabel.setLetterSpacing(0.01f);
        sThumbTitle = Ui.text(Ui.semibold, 26 * k, 0xFFFFFFFF);
        sAction = Ui.text(Ui.semibold, 28 * k, Tokens.TEXT);
        shelfTitleKey = "";     // the title's layout is made again with the new paint
        buttonPaint = Ui.text(Ui.semibold, 29 * k, INK);
        capsuleText = Ui.numbers(Ui.medium, 29 * k, 0xF2FFFFFF);
        tipPaint = Ui.text(Ui.semibold, 25 * k, 0xF2FFFFFF);
        tipPaint.setTextAlign(Paint.Align.CENTER);
        tipPaint.setShadowLayer(16 * k, 0, 1 * k, 0x99000000);
        // Quotes: a subtle detail, smaller and dimmer than the rest.
        quotePaint = Ui.numbers(Ui.regular, 21 * k, 0xB8FFFFFF);
        quotePaint.setShadowLayer(14 * k, 0, 1 * k, 0x99000000);
        staleQuote = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        staleQuote.setTextAlign(Paint.Align.RIGHT);
        rowTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        drawerLabel = Ui.text(Ui.medium, 23 * k, 0xEBFFFFFF);
        drawerLabel.setTextAlign(Paint.Align.CENTER);
        cardTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        cardText = Ui.text(Ui.medium, 29 * k, 0xF2FFFFFF);
        cardDim = Ui.numbers(Ui.medium, 23 * k, 0xADFFFFFF);
        cardDimRight = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        cardDimRight.setTextAlign(Paint.Align.RIGHT);
        bigTemp = Ui.numbers(Ui.light, 100 * k, 0xFFFFFFFF);
        hourPaint = Ui.numbers(Ui.medium, 22 * k, 0xADFFFFFF);
        hourPaint.setTextAlign(Paint.Align.CENTER);
        hourTemp = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        hourTemp.setTextAlign(Paint.Align.CENTER);
        pricePaint = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        pricePaint.setTextAlign(Paint.Align.RIGHT);
        changePaint = Ui.numbers(Ui.semibold, 21 * k, 0xFFFFFFFF);
        changePaint.setTextAlign(Paint.Align.CENTER);
        sheetTitle = Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF);
        sheetTitle.setTextAlign(Paint.Align.CENTER);
        gridLabel = new TextPaint(Ui.text(Ui.medium, 24 * k, 0x99FFFFFF));
        gridLabel.setTextAlign(Paint.Align.CENTER);
        toastPaint = new TextPaint(Ui.text(Ui.medium, 26 * k, 0xF2FFFFFF));
        shadow.setMaskFilter(new BlurMaskFilter(SHADOW_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        shadowSoft.setMaskFilter(new BlurMaskFilter(12 * k, BlurMaskFilter.Blur.NORMAL));
        shadowDeep.setMaskFilter(new BlurMaskFilter(DEEP_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        halo.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2 * k);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2.5f * k);
    }

    // MARK: - Frames: 60 per second while something moves; nothing is redrawn when still

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (frameTimeNanos - lastNanos) / 1e9f);
        lastNanos = frameTimeNanos;
        long up = SystemClock.uptimeMillis();

        boolean tilesMoving = step(tileFocus, dt);
        if (reorderAnim) tilesMoving |= stepReorder(dt, up);
        boolean statusMoving = step(statusFocus, dt);
        boolean heroMoving = heroFocus.step(dt);
        boolean topMoving = step(topFocus, dt), cardsMoving = step(cardFocus, dt);
        if (TOP_SHELF) {
            boolean infoMoving = info.step(dt);
            if (infoMoving) motionDirty = true;   // the dock and the buttons fade with it (updateMotion)
            cardsMoving |= step(actionFocus, dt) | step(tabFocus, dt) | weatherPill.step(dt) | profilePill.step(dt) | infoMoving | stepStack(dt) | tickShelfText(up);
        }
        if (TOP_SHELF && tickShelfFade(up)) sky.invalidate();
        if (topMoving) status.invalidate();
        heroMoving |= topMoving | cardsMoving;
        boolean drawerItemsMoving = step(drawerFocus, dt);
        boolean gridMoving = step(gridFocus, dt) | scroll.step(dt);
        boolean drawerMoving = drawer.step(dt);
        boolean sheetMoving = sheet.step(dt);
        boolean toastFading = tickToast(up);
        boolean heroFading = tickHeroFade(up);
        boolean bgFading = tickBackgroundFade(up);
        boolean nightMoving = tickNight(up);

        if (drawerMoving || sheetMoving || motionDirty) updateMotion();
        // Opening and closing the drawer only moves the cached layers: redrawing the glass on every frame made the TV
        // drop frames (measured: ~50 ms of GPU per frame). The app tiles have no glass; the top buttons fade out as
        // they rise; the drawer is drawn resting in place; the glass shelf is redrawn once the drawer stops, so the
        // glass shows the background where it ended up.
        if (tilesMoving || tilesDirty) tiles.invalidate();
        if (statusMoving || statusDirty || bgFading) status.invalidate();
        if (heroMoving || heroDirty || heroFading) heroLayer.invalidate();
        if (drawerItemsMoving || drawerDirty || bgFading) drawerLayer.invalidate();
        if (gridMoving || gridDirty) sheetLayer.invalidate();
        if (drawerMoving || bgFading) sky.invalidate();
        if (bgFading || (drawerWasMoving && !drawerMoving)) platter.invalidate();
        drawerWasMoving = drawerMoving;
        tilesDirty = statusDirty = heroDirty = drawerDirty = gridDirty = motionDirty = false;
        if (updateClock()) {
            layoutStatus();
            status.invalidate();
            if (hero != null && hero.clock) {
                refreshHero(false);
                heroLayer.invalidate();
            }
            if (!picksOn() || TOP_SHELF) heroLayer.invalidate();   // the Top Shelf's time is in its top bar
        }

        // Busy: something is moving, or the sheet or the drawer is open.
        boolean busy = tilesMoving || statusMoving || heroMoving || drawerItemsMoving || gridMoving || drawerMoving
                || sheetMoving || toastFading || heroFading || bgFading || nightMoving || shelfFadeAt != 0 || sheetOpen || sheet.value > 0.001f
                || drawerOpen || drawer.value > 0.001f || toast != null || !toasts.isEmpty() || searchAnimating();
        if (searchAnimating()) heroLayer.invalidate();   // the caret, the listening pulse, the spinner
        idle = !busy;
        if (busy) Choreographer.getInstance().postFrameCallback(this);
        else Choreographer.getInstance().postFrameCallbackDelayed(this, 1000);
    }

    private void wake() {
        if (!running || !idle) return;
        idle = false;
        lastNanos = 0;
        Choreographer.getInstance().removeFrameCallback(this);
        Choreographer.getInstance().postFrameCallback(this);
    }

    private static boolean step(Spring[] springs, float dt) {
        boolean moving = false;
        for (Spring s : springs) moving |= s.step(dt);
        return moving;
    }

    private final float[] dockAt = new float[2];

    /** Drawer and sheet: the layers' positions and opacity (without redrawing their content). */
    private void updateMotion() {
        float d = drawer.value, dc = Ui.clamp(d), s = Ui.clamp(sheet.value);
        float home = 1 - s, top = home * (1 - smoothstep(0, 0.32f, dc));
        float shift = SHIFT * k;
        float lift = -d * shift;
        show(heroLayer, top, lift);
        show(status, top, lift);
        show(platter, home, lift);
        if (TOP_SHELF) {
            // Your Apps is the dock layer, moved (not redrawn) to sit under the hero as the stack scrolls; it fades
            // under the top bar like the rows, and while More Info is open. No round buttons: the top bar has them.
            float shown = 1 - Ui.clamp(info.value);
            dockMotion(dockAt);
            show(tiles, home * shown * dockAt[1], lift + dockAt[0]);
            // The dock's glass tray is the platter layer, moved with it; redrawn only when it moved (the glass shows
            // the photo where it is now), not when the focus moves along the apps.
            float trayY = lift + dockAt[0];
            show(platter, home * shown * dockAt[1], trayY);
            if (dockGlass() && trayY != platterDrawnAt) {
                platterDrawnAt = trayY;
                platter.invalidate();
            }
            show(status, 0, lift);
        } else {
            show(tiles, home, lift);
        }
        float da = home * smoothstep(0.1f, 0.6f, dc);
        drawerLayer.setTranslationY((1 - d) * shift);
        drawerLayer.setAlpha(da);
        drawerLayer.setVisibility(da > 0.002f ? VISIBLE : INVISIBLE);
        sheetLayer.setVisibility(s > 0.001f || sheetOpen ? VISIBLE : INVISIBLE);
        sheetLayer.setAlpha(s);
        float scale = 1.04f - 0.04f * s;
        sheetLayer.setScaleX(scale);
        sheetLayer.setScaleY(scale);
    }

    private static void show(View v, float alpha, float translationY) {
        v.setAlpha(alpha);
        v.setTranslationY(translationY);
        v.setVisibility(alpha > 0.002f ? VISIBLE : INVISIBLE);
    }

    private void updateSleep() {
        boolean here = Prefs.sleepShown() && !android.provider.Settings.canDrawOverlays(getContext());   // else SleepOverlay
        int left = here ? SleepTimer.minutesLeft(getContext()) : 0;
        String text = left == 0 ? "" : Ui.trf("Off in {time}", "time", SleepTimer.duration(left));
        if (text.equals(sleepText)) return;
        sleepText = text;
        statusDirty = true;
    }

    private boolean updateClock() {
        long now = System.currentTimeMillis();
        long minute = now / 60_000;
        if (minute == clockMinute || clockPaint == null) return false;
        clockMinute = minute;
        clockText = Ui.clock(now);
        dateText = Ui.date(now);
        return true;
    }

    private void updatePalette() {
        HomeData.Weather wx = data.weather;
        Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
        scene.applyPalette();
    }

    // MARK: - Recommendations: hero and background

    private Recs.Pick currentPick() {
        return picks.isEmpty() ? null : picks.get(Math.min(pickIndex, picks.size() - 1));
    }

    /** This hour's picks: the cards and the background image (called when Recs notifies and every 15 s). */
    private void updatePicks() {
        if (labelPaint == null) return;
        updateCards();
    }

    private static boolean samePicks(List<Recs.Pick> a, List<Recs.Pick> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!Recs.key(a.get(i)).equals(Recs.key(b.get(i)))) return false;
        return true;
    }

    /**
     * Rebuilds the hero. With `animate`, a different title slides in while the previous one slides out; details that
     * arrive later (genre, runtime) just appear.
     */
    private void refreshHero(boolean animate) {
        Hero next = makeHero();
        if (next.sameText(hero) && hero.dots == next.dots && hero.dot == next.dot && hero.button == next.button) return;
        boolean other = hero != null && (hero.clock != next.clock || !hero.title.equals(next.title));
        if (animate && other) {
            heroFrom = hero;
            heroFadeAt = SystemClock.uptimeMillis();
        }
        boolean layoutChanged = hero == null || hero.clock != next.clock;
        hero = next;
        heroDirty = true;
        if (layoutChanged) layoutStatus();
    }

    private Hero makeHero() {
        Hero h = new Hero();
        Recs.Pick p = currentPick();
        if (p == null) {
            h.clock = true;
            h.label = Ui.date(System.currentTimeMillis()).toUpperCase(Ui.locale());
            return h;
        }
        h.label = Recs.periodLabel();
        h.title = p.title == null ? "" : p.title;
        h.meta = p.meta == null ? "" : p.meta;
        h.button = p.pkg != null;
        h.dots = picks.size();
        h.dot = pickIndex;
        h.titleLayout = StaticLayout.Builder.obtain(h.title, 0, h.title.length(), titlePaint, Math.max(1, Math.round(HERO_TITLE_W * k)))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(0, 0.94f)
                .setIncludePad(false)
                .build();
        return h;
    }

    /** ←/→ on the hero: the next (or previous) of this hour's picks. */
    private boolean browse(int dir) {
        if (picks.size() < 2) return false;
        int next = pickIndex;
        for (int i = 0; i < picks.size(); i++) {
            next = (next + dir + picks.size()) % picks.size();
            if (zone != Z_HERO || picks.get(next).pkg != null) break;
        }
        if (next == pickIndex) return false;
        pickIndex = next;
        heroSlide = dir;
        refreshHero(true);
        Backdrop b = recs.backdrop(currentPick());
        if (b != null) showBackdrop(b);
        spoken.focusMoved();
        return true;
    }

    /** Swaps the background with a cross-fade (from one image to another, from the gradient to an image, or back). */
    private void showBackdrop(Backdrop b) {
        if (b == bgTo) return;
        if (TOP_SHELF) {
            // Only the glass reads this (the drawer, the round buttons; the photo itself is drawShelfSky's): swapped at
            // once. A fade here redrew the photo, the buttons and the drawer on every frame of each change.
            bgFrom = null;
            bgTo = b;
            bgFadeAt = 0;
            nightPair = false;
            if (b == null || glassOf(b) == null) {
                neutralGlass();
            } else {
                scene.setFrost(frostOf(b));
                scene.setGlassImage(glassOf(b));
            }
            statusDirty = drawerDirty = true;
            platter.invalidate();   // the dock's glass shows the new photo too
            return;
        }
        long up = SystemClock.uptimeMillis();
        // In the middle of another swap, start from whichever is more visible.
        if (bgFadeAt == 0 || fadeEase(bgFadeAt, up) >= 0.5f) bgFrom = bgTo;
        bgTo = b;
        bgFadeAt = up;
        nightPair = false;
        if (b == null || glassOf(b) == null) return;
        if (bgFrom == null || glassOf(bgFrom) == null) {
            scene.setFrost(frostOf(b));
            scene.setGlassImage(glassOf(b));
        } else {
            scene.setGlassImages(glassOf(bgFrom), glassOf(b));
        }
    }

    /** What the glass shows: the photo lightened (by day), darkened (at night) or blended in between. */
    private int[] glassOf(Backdrop b) {
        if (b.nightGlass == null || night <= 0.001f) return b.lightGlass;
        if (night >= 0.999f) return b.nightGlass;
        // During the transition, the blend (only for the current background; a photo fading in uses the nearer side).
        if (b != bgTo) return night < 0.5f ? b.lightGlass : b.nightGlass;
        for (int i = 0; i < glassNight.length; i++) glassNight[i] = mix(b.lightGlass[i], b.nightGlass[i], night);
        return glassNight;
    }

    /** No photo: the glass reads the background's own gray (not Scene's default aurora, which isn't on screen). */
    private void neutralGlass() {
        if (!scene.shaded()) {   // Android 8 to 12: a gray image would be one more full-area pass for the flat look
            scene.clearFrost();
            scene.clearGlass();
            nightPair = false;
            return;
        }
        int color = blend(0xFFEBEBF0, 0xFF141416, night);
        java.util.Arrays.fill(neutralFrostPx, color);
        java.util.Arrays.fill(neutralGlassPx, color);
        scene.setFrost(neutralFrostPx);
        scene.setGlassImage(neutralGlassPx);
        nightPair = false;
    }

    /** What the frosted glass shows (drawer, toasts): the blurred photo, darkened at night. */
    private int[] frostOf(Backdrop b) {
        if (b.nightFrost == null || night <= 0.001f) return b.frost;
        if (night >= 0.999f) return b.nightFrost;
        if (b != bgTo) return night < 0.5f ? b.frost : b.nightFrost;
        for (int i = 0; i < frostNight.length; i++) frostNight[i] = mix(b.frost[i], b.nightFrost[i], night);
        return frostNight;
    }

    // MARK: - Night look

    /** Day or night right now (from the sun at your location); `animate`: with the transition. */
    private void checkNight(boolean animate) {
        boolean want = TOP_SHELF || data.isNight(System.currentTimeMillis());   // the Top Shelf is always dark
        if (want == nightTarget) return;
        nightTarget = want;
        if (animate && running) {
            nightFrom = night;
            nightAt = SystemClock.uptimeMillis();
        } else {
            night = want ? 1 : 0;
            nightAt = 0;
            nightChanged();
        }
    }

    /** Advances the day ⇄ night transition; true while it runs. */
    private boolean tickNight(long up) {
        if (nightAt == 0) return false;
        float t = Ui.clamp((up - nightAt) / NIGHT_MS), to = nightTarget ? 1 : 0;
        night = nightFrom + (to - nightFrom) * t * t * (3 - 2 * t);
        if (t >= 1) {
            night = to;
            nightAt = 0;
        }
        nightChanged();
        return true;
    }

    /** Redraws for the current night value (the glass now reads the lightened, blended or darkened photo). */
    private void nightChanged() {
        if (bgFadeAt == 0 && bgTo != null && bgTo.lightGlass != null) {
            scene.setFrost(frostOf(bgTo));
            if (nightAt != 0 && bgTo.nightGlass != null) {
                if (!nightPair) {
                    scene.setGlassImages(bgTo.lightGlass, bgTo.nightGlass);
                    nightPair = true;
                }
                scene.setGlassMix(night);
            } else {
                scene.setGlassImage(glassOf(bgTo));
                nightPair = false;
            }
        } else if (bgFadeAt == 0 && bgTo == null) {
            neutralGlass();
        }
        heroDirty = statusDirty = tilesDirty = drawerDirty = true;
        sky.invalidate();
        platter.invalidate();
    }

    /** How dark it is: 0 = day, 1 = night (in between during the transition). */
    private float dark() {
        return night;
    }

    /** The day color or the night color, depending on the night value (blended during the transition). */
    private int tone(int onLight, int onDark) {
        return blend(onLight, onDark, dark());
    }

    private float tone(float onLight, float onDark) {
        return onLight + (onDark - onLight) * dark();
    }

    /** Text colors for the current night value (dark by day, white at night). */
    private void applyTone() {
        lTitle.setColor(tone(L_INK, WHITE));
        lTab.setColor(tone(L_DIM, 0xFF8E8E93));
        lTabOn.setColor(tone(L_INK, WHITE));
        lCardTitle.setColor(tone(L_INK, WHITE));
        lCardMeta.setColor(tone(L_DIM, 0xFF98989D));
        lQuote.setColor(tone(L_FAINT, 0xB8FFFFFF));
        lTip.setColor(tone(L_INK, WHITE));
        lDate.setColor(tone(L_DIM, 0xFF98989D));
        lClock.setColor(tone(L_INK, WHITE));
        lWeather.setColor(tone(L_DIM, 0xFF98989D));
        lClockQuote.setColor(tone(L_DIM, 0xFF98989D));
        staleQuote.setColor(tone(L_FAINT, 0x99FFFFFF));
    }

    private boolean tickBackgroundFade(long up) {
        if (bgFadeAt == 0) return false;
        float e = fadeEase(bgFadeAt, up);
        if (bgTo != null && bgFrom != null && glassOf(bgFrom) != null && glassOf(bgTo) != null) {
            if (drawerLayer.getVisibility() == VISIBLE || toast != null) {
                int[] from = frostOf(bgFrom), to = frostOf(bgTo);
                for (int i = 0; i < frostMix.length; i++) frostMix[i] = mix(from[i], to[i], e);
                scene.setFrost(frostMix);
            }
            scene.setGlassMix(e);
        }
        if (e >= 1) {
            bgFadeAt = 0;
            bgFrom = null;
            if (bgTo == null) {
                neutralGlass();
            } else {
                scene.setFrost(frostOf(bgTo));
                if (glassOf(bgTo) != null) scene.setGlassImage(glassOf(bgTo));
            }
        }
        return true;
    }

    private boolean tickHeroFade(long up) {
        if (heroFadeAt == 0) return false;
        if (fadeEase(heroFadeAt, up) >= 1) {
            heroFadeAt = 0;
            heroFrom = null;
        }
        return true;
    }

    private float fadeEase(long start, long up) {
        return fadeEase(start, up, FADE_MS);
    }

    private static float fadeEase(long start, long up, float duration) {
        float t = Ui.clamp((up - start) / duration);
        return t * t * (3 - 2 * t);
    }

    private static int mix(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // MARK: - Background

    private void drawSky(Canvas c) {
        drawLightSky(c);
    }

    /** Blends a color on top (the drawer's veil) as part of drawing the image, without another layer. */
    private android.graphics.ColorFilter veil(float amount, int color) {
        if (amount <= 0.002f) return null;
        amount = Math.round(amount * 255) / 255f;
        if (amount == veilAmount && color == veilColor) return veilFilter;
        veilAmount = amount;
        veilColor = color;
        float keep = 1 - amount;
        return veilFilter = new android.graphics.ColorMatrixColorFilter(new float[] {
            keep, 0, 0, 0, ((color >> 16) & 255) * amount,
            0, keep, 0, 0, ((color >> 8) & 255) * amount,
            0, 0, keep, 0, (color & 255) * amount,
            0, 0, 0, 1, 0});
    }

    private void drawImage(Canvas c, Bitmap image, float alpha) {
        if (image == null || alpha <= 0.004f) return;
        imagePaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawBitmap(image, null, screen, imagePaint);
    }

    // MARK: - Hero

    private static void text(Canvas c, String s, float x, float y, Paint p, int color, float alpha) {
        if (s == null || s.isEmpty()) return;
        p.setColor(Ui.alpha(color, alpha));
        c.drawText(s, x, y, p);
    }

    // MARK: - Corner capsule: time and weather (when the hero shows a recommendation) and icon-only buttons

    /** Live TV, the inputs with a device on, All Apps and Settings. */
    private static List<HomeApps.Item> statusList(Context c) {
        List<HomeApps.Item> list = new ArrayList<>();
        list.addAll(HomeApps.shortcuts(c));
        list.add(HomeApps.settings());
        return list;
    }

    private void setStatusItems(List<HomeApps.Item> list) {
        boolean same = list.size() == statusItems.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(statusItems.get(i).key);
        String focusedKey = statusIndex < statusItems.size() ? statusItems.get(statusIndex).key : null;
        statusItems = list;
        if (!same || statusFocus.length != list.size()) {
            statusFocus = new Spring[list.size()];
            statusRects = new RectF[list.size()];
            int index = Math.min(statusIndex, Math.max(0, list.size() - 1));
            for (int i = 0; i < statusFocus.length; i++) {
                statusFocus[i] = new Spring(0.26f, 0.85f, 0);
                statusRects[i] = new RectF();
                if (list.get(i).key.equals(focusedKey)) index = i;   // a device came on: keep focus on the same button
            }
            statusIndex = index;
            if (zone == Z_STATUS && list.isEmpty()) zone = Z_SHELF;
            aim(statusFocus, zone == Z_STATUS ? statusIndex : -1, true);   // only the capsule's springs
            statusDirty = true;
        }
        layoutStatus();
    }

    private boolean showInfo() {
        return hero != null && !hero.clock;
    }

    private void layoutStatus() {
        if (capsuleText == null) return;
        int n = statusItems.size();
        float b = BUTTON * k, gap = 4 * k, pad = 6 * k;
        float info = 0;
        if (showInfo()) {
            info = 26 * k + capsuleText.measureText(clockText);
            HomeData.Weather wx = data.weather;
            if (!Double.isNaN(wx.temp) && Prefs.weather()) {
                info += 22 * k + Symbols.width(getContext(), Symbols.weather(wx.code, wx.isDay), 24 * k) + 8 * k
                        + capsuleText.measureText(HomeData.degrees(wx.temp));
            }
            info += 22 * k;
        }
        capsuleInfoW = info;
        float buttons = n * b + Math.max(0, n - 1) * gap;
        float right = CONTENT_R * k, top = CAPSULE_TOP * k;
        float left = right - pad - buttons - (info > 0 ? info + 12 * k : pad);
        capsuleRect.set(left, top, right, top + CAPSULE_H * k);
        float x = right - pad - buttons, cy = capsuleRect.centerY();
        for (int i = 0; i < n; i++) {
            statusRects[i].set(x, cy - b / 2, x + b, cy + b / 2);
            x += b + gap;
        }
        statusDirty = true;
    }

    // MARK: - Quotes and shelf

    private void setShelf(List<HomeApps.Item> list) {
        wake();
        cancelReorder(true);    // the list changed under the move (an app installed or removed)
        tileShaders = new LinearGradient[0];
        boolean same = list.size() == shelf.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(shelf.get(i).key);
        shelf = list;
        if (!same || tileFocus.length != list.size()) {
            tileFocus = new Spring[list.size()];
            tileSlide = new Spring[list.size()];
            tileRects = new RectF[list.size()];
            for (int i = 0; i < tileFocus.length; i++) {
                tileFocus[i] = new Spring(0.34f, 0.74f, 0);
                tileSlide[i] = new Spring(0.32f, 0.86f, 0);
                tileRects[i] = new RectF();
            }
            shelfIndex = Math.min(shelfIndex, Math.max(0, list.size() - 1));
            retarget(true);
            layoutShelf();
        }
        tilesDirty = true;
        platter.invalidate();
    }

    private void layoutShelf() {
        int n = Math.min(shelf.size(), shelfVisible);
        float pad = SHELF_PAD * k, gap = TILE_GAP * k;
        float full = (CONTENT_R - MARGIN) * k;
        // Tiles in the TV banners' own 16:9, so an app's art is never stretched; never taller than L_TILE_H. The glass
        // keeps its bottom edge, so with more apps it only gets lower.
        float tw = Math.min((TOP_SHELF ? S_TILE_H : L_TILE_H) * 16f / 9 * k, (full - Math.max(0, n - 1) * gap) / Math.max(1, n));
        float th = tw * 9f / 16;
        float width = n * tw + Math.max(0, n - 1) * gap, left = (w - width) / 2f;
        float top = TOP_SHELF ? S_DOCK_TOP * k - pad : (L_PLATTER_TOP + L_TILE_H) * k - th;
        platterRect.set(left - pad, top, left + width + pad, top + th + 2 * pad);
        snap(platterRect);
        shelfFirst = Math.max(0, Math.min(shelfFirst, shelf.size() - n));
        float x = left - shelfFirst * (tw + gap), y = top + pad;
        for (int i = 0; i < shelf.size(); i++) {
            tileRects[i].set(x, y, x + tw, y + th);
            snap(tileRects[i]);
            x += tw + gap;
        }
        crispLogos.clear();
    }

    /** Scrolls the shelf, one app at a time, so the focused app is shown: the tiles slide there (unless `snap`). */
    private void revealShelf(boolean snap) {
        int n = Math.min(shelf.size(), shelfVisible);
        int first = Math.max(0, Math.min(Math.max(shelfFirst, shelfIndex - n + 1), Math.min(shelfIndex, shelf.size() - n)));
        if (first == shelfFirst || tileRects.length == 0) return;
        float dx = (shelfFirst - first) * (tileRects[0].width() + TILE_GAP * k);
        for (int i = 0; i < tileRects.length; i++) {
            tileRects[i].offset(dx, 0);
            if (!snap && i < tileSlide.length) tileSlide[i].value -= dx;
        }
        shelfFirst = first;
        tilesDirty = true;
        if (snap) return;
        reorderAnim = true;   // the same slides as moving an app
        wake();
    }

    /** The Top Shelf's dock sits in a glass tray (Settings > Personalize > Dock glass), unless it's off. */
    private boolean dockGlass() {
        return TOP_SHELF && !shelf.isEmpty() && !Prefs.dockGlass().equals("off");
    }

    private final RectF trayRect = new RectF();
    private float platterDrawnAt = Float.NaN;

    /**
     * The tray behind Your Apps, as tvOS-style docks have it: Liquid Glass (the photo shows through, lightly blurred,
     * with a bright rim) or Frosted Glass (heavily blurred, softer). It holds the tiles and their names; the layer is
     * moved with the stack, so the glass reads the photo where the layer really is (Layer.onDraw's offset).
     */
    private void drawDockGlass(Canvas c) {
        if (tileRects.length == 0) return;
        RectF t = tileRects[Math.min(shelfFirst, tileRects.length - 1)];
        trayRect.set(platterRect.left, t.top - S_TRAY_TOP * k, platterRect.right, t.bottom + S_TRAY_BOTTOM * k);
        float radius = S_TRAY_RADIUS * k;
        if (Prefs.dockGlass().equals("frosted")) {
            scene.glass(c, trayRect, radius, SMOKE, 0.42f, 0, 0, 0.05f, 1);
        } else {
            scene.liquid(c, trayRect, radius, SMOKE, 0.12f, 0.08f, 28 * k, 22 * k, 0.95f, 1);
        }
    }

    /** Shelf layer: the glass bar and its shadow (it only changes with the background). */
    private void drawPlatter(Canvas c) {
        if (TOP_SHELF) {
            if (dockGlass()) drawDockGlass(c);   // the Top Shelf's dock: its glass tray, if any
            return;
        }
        if (shelf.isEmpty()) return;
        float radius = (TILE_RADIUS + SHELF_PAD) * k;
        tmp.set(platterRect);
        tmp.offset(0, 18 * k);
        shadow.setColor(tone(0x1F000000, 0x52000000));
        shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
        // White glass by day; smoked glass at night.
        scene.liquid(c, platterRect, radius, tone(0xFFFFFFFF, SMOKE), tone(0.42f, 0.12f), tone(0.03f, 0.08f),
                tone(30, 34) * k, tone(22, 28) * k, tone(0.65f, 0.95f), 1);
    }

    /** The quotes row's text and sizes, for one set of coins, prices and text size. */
    private final class Quotes {
        final int[] coins;
        final double[] prices;
        final float size, cap, halfCap;
        final String[] values;
        final float[] glyphs, widths;

        Quotes(int[] coins, double[] all, Paint text) {
            this.coins = coins;
            size = text.getTextSize();
            cap = Ui.cap(text);
            halfCap = Ui.halfCap(text);
            int n = coins.length;
            prices = new double[n];
            values = new String[n];
            glyphs = new float[n];
            widths = new float[n];
            for (int i = 0; i < n; i++) {
                prices[i] = all[coins[i]];
                values[i] = HomeData.compact(prices[i]);
                glyphs[i] = Glyphs.width(getContext(), HomeData.COINS[coins[i]], cap);
                widths[i] = text.measureText(values[i]);
            }
        }

        boolean matches(int[] now, double[] all, float textSize) {
            if (now != coins || textSize != size) return false;
            for (int i = 0; i < coins.length; i++) if (Double.compare(all[coins[i]], prices[i]) != 0) return false;
            return true;
        }
    }

    private Quotes quotes;

    /** Coin logo + price in the configured currency, in a row at the right end of the tab bar. */
    private void drawQuotes(Canvas c) {
        boolean clock = !picksOn();
        // At the right margin, under the round buttons: the shelf's glass is narrower and centred with few apps.
        float right = CONTENT_R * k;
        applyTone();
        Paint text = clock ? lClockQuote : lQuote;
        // The corner buttons' names appear in this row: the quotes fade out while one of them has focus.
        float fade = 1;
        for (int i = 3; i < 3 + lightButtons.size() && !clock; i++) fade = Math.min(fade, 1 - Ui.clamp(topFocus[i].value));
        if (fade <= 0.01f) return;
        int textColor = text.getColor();
        text.setColor(Ui.alpha(textColor, fade));
        // One read of the list, so n always matches the coins drawn below.
        int[] coins = HomeData.active();
        int n = coins.length;
        if (n == 0) { text.setColor(textColor); return; }
        // Measured once per set of prices: this row is redrawn on every frame of the cards' focus animation, and
        // measuring text there cost ~3 ms a frame on a Mi Box 3.
        Quotes q = quotes;
        if (q == null || !q.matches(coins, data.prices, text.getTextSize())) {
            q = quotes = new Quotes(coins, data.prices, text);
        }
        // On the tab row, at the right.
        float base = clock ? L_QUOTES_BASE * k : (L_TABBAR_TOP + L_TABBAR_H / 2) * k + q.halfCap;
        float cap = q.cap, gap = (clock ? 8 : 6) * k, spacing = (clock ? 32 : 24) * k;
        float total = spacing * (n - 1);
        for (int i = 0; i < n; i++) total += q.glyphs[i] + gap + q.widths[i];
        float x = clock ? MARGIN * k : right - total;
        int coinColor = clock ? text.getColor() : Ui.alpha(tone(0xB38E8E93, 0x8CFFFFFF), fade);
        for (int i = 0; i < n; i++) {
            Glyphs.coin(getContext(), c, HomeData.COINS[coins[i]], x + q.glyphs[i] / 2, base - cap / 2, cap, coinColor);
            x += q.glyphs[i] + gap;
            c.drawText(q.values[i], x, base, text);
            x += q.widths[i] + spacing;
        }
        text.setColor(textColor);
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        if (stale.isEmpty()) return;
        if (clock) c.drawText(stale, x - spacing + 24 * k + staleQuote.measureText(stale), base, staleQuote);
        else c.drawText(stale, right - total - 24 * k, base, staleQuote);
    }

    private void drawTiles(Canvas c) {
        // The focused one last: it grows over its neighbors without being covered by them.
        int top = -1;
        float most = 0.02f;
        for (int i = 0; i < shelf.size(); i++) {
            if (tileFocus[i].value > most) {
                most = tileFocus[i].value;
                top = i;
            }
        }
        if (liftIndex >= 0 && liftIndex < shelf.size() && moveLift.value > 0.02f) top = liftIndex;
        int last = Math.min(shelf.size(), shelfFirst + shelfVisible);
        // Scrolling, tiles slide in and out through the glass's sides (only those that show any part are drawn). Only
        // then: on a shelf that fits, the clip would cut the end tiles' shadows in a straight line.
        boolean scrolls = shelf.size() > shelfVisible;
        if (scrolls) {
            c.save();
            c.clipRect(platterRect.left, 0, platterRect.right, h);
        }
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < shelf.size(); i++) {
                if ((pass == 0) == (i == top)) continue;
                float slid = i < tileSlide.length ? tileSlide[i].value : 0;
                if (tileRects[i].right + slid <= platterRect.left || tileRects[i].left + slid >= platterRect.right) continue;
                // Moving apps: the tile slides from where it was and the lifted one wiggles (only then: no extra work
                // otherwise).
                float dx = i < tileSlide.length ? tileSlide[i].value : 0;
                float angle = i == liftIndex ? wiggle * moveLift.value : 0;
                if (dx == 0 && angle == 0) {
                    drawShelfTile(c, i);
                    continue;
                }
                c.save();
                c.translate(dx, 0);
                c.rotate(angle, tileRects[i].centerX(), tileRects[i].centerY());
                drawShelfTile(c, i);
                c.restore();
            }
        }
        if (scrolls) c.restore();
        // More apps than fit: a small arrow inside the glass's edge on each side that has more, where no tile reaches
        // even focused (inside the screen's safe area).
        float cy = platterRect.centerY(), size = 28 * k, inset = 14 * k;
        int ink = tone(0x99000000, 0xCCFFFFFF);
        if (last < shelf.size()) Symbols.draw(getContext(), c, "chevron_right", platterRect.right - inset, cy, size, ink);
        if (shelfFirst > 0) {
            c.save();
            c.scale(-1, 1, platterRect.left + inset, cy);
            Symbols.draw(getContext(), c, "chevron_right", platterRect.left + inset, cy, size, ink);
            c.restore();
        }
    }

    /**
     * App icon like the ones in the tvOS 27 Dock: the brand color edge to edge (a soft gradient) with the logo.
     * Focused: it grows 20% from the center, with a deep shadow (it "rises" toward the viewer) and a subtle glow at the
     * top. No border and no reflection, as Apple does it.
     */
    private void drawShelfTile(Canvas c, int i) {
        HomeApps.Item it = shelf.get(i);
        // Lifted to be moved: a little bigger than focused, with a deeper shadow.
        float l = i == liftIndex ? Math.max(0, moveLift.value) : 0;
        float f = tileFocus[i].value, fc = Ui.clamp(f), s = 1 + FOCUS_GROW * f + LIFT_GROW * l;
        RectF base = tileRects[i];
        float bw = base.width(), bh = base.height(), r0 = TILE_RADIUS * k;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        // Shadow at the resting icon size, enlarged by the transform (so the blur stays cached): short at rest, deep
        // and lower when focused. On the Top Shelf only the focused (or lifted) icon has one, as on tvOS: eight
        // blurred quads bigger than the icons were a good part of each frame's fill.
        if (!TOP_SHELF || f > 0.02f || l > 0.02f) {
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            tmp.offset(0, (4 + 14 * f + 6 * l) * k);
            float rest = TOP_SHELF ? 0 : tone(0.14f, 0.3f);
            shadowDeep.setColor(Ui.alpha(0xFF000000, rest + tone(0.18f, 0.3f) * fc + 0.14f * Ui.clamp(l)));
            shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
            c.restore();
        }
        int[] style = TILE_STYLE.get(it.key);
        if (it.logo != null && style != null) {
            if (tileShaders.length != shelf.size() || tileShaderH != bh) {
                tileShaders = new LinearGradient[shelf.size()];
                tileShaderH = bh;
                tileGlow = new LinearGradient(0, -bh / 2, 0, 0, 0x14FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
            }
            if (tileShaders[i] == null) {
                tileShaders[i] = new LinearGradient(0, -bh / 2, 0, bh / 2, style[0], style[1], Shader.TileMode.CLAMP);
            }
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            tileFill.setShader(tileShaders[i]);
            tileFill.setAlpha(255);
            c.drawRoundRect(tmp, r0, r0, tileFill);
            if (fc > 0.01f) {
                tileFill.setShader(tileGlow);
                tileFill.setAlpha(Math.round(255 * fc));
                c.drawRoundRect(tmp, r0, r0, tileFill);
                tileFill.setAlpha(255);
            }
            tileFill.setShader(null);
            c.restore();
            drawLogo(c, it, cell, s, style[2]);
        } else if (it.logo != null) {
            tileFill.setShader(null);
            tileFill.setColor(0xFF161618);
            c.drawRoundRect(cell, r, r, tileFill);
            drawLogo(c, it, cell, s, 0);
        } else {
            if (it.tile != null) roundImage(c, it.tile, it.tileShader, cell, r);
        }
        if (TOP_SHELF && sCardSub != null && it.label != null) {
            // Your Apps: the app's name under its tile, brighter when focused.
            sCardSub.setTextAlign(Paint.Align.CENTER);
            sCardSub.setAlpha(Math.round(0xA6 + 0x59 * fc));
            fitPaint.set(sCardSub);
            c.drawText(TextUtils.ellipsize(it.label, fitPaint, bw + 24 * k, TextUtils.TruncateAt.END).toString(),
                    cx, cell.bottom + (30 + 6 * fc) * k, sCardSub);
            sCardSub.setTextAlign(Paint.Align.LEFT);
        }
    }

    /**
     * `image` stretched over `cell`, with corners of radius `r`: the shader of an anti-aliased rounded rectangle. A
     * clipPath there isn't anti-aliased on some GPUs (stair-stepped corners on a MediaTek TV), and on Android 8 to 11 it
     * cost a mask on every frame of a focus animation (measured on a Mi Box 3: most of a 49 ms frame). `shader` is the
     * image's own, made with it (HomeApps.Item, Backdrop) and dropped with it; the canvas does the scaling, so drawing
     * changes nothing in it and allocates nothing.
     */
    private void roundImage(Canvas c, Bitmap image, BitmapShader shader, RectF cell, float r) {
        float sx = cell.width() / image.getWidth(), sy = cell.height() / image.getHeight();
        c.save();
        c.translate(cell.left, cell.top);
        c.scale(sx, sy);
        imageFill.setShader(shader);
        c.drawRoundRect(0, 0, image.getWidth(), image.getHeight(), r / sx, r / sy, imageFill);
        imageFill.setShader(null);
        c.restore();
    }

    /**
     * Logo at its "optical" size (all with the same amount of ink, plus each one's own adjustment, never past 80% × 62%
     * of the tile), centered and already filtered down to the focused tile size and to the resting one: focused or at
     * rest it is drawn pixel for pixel, without resampling (on a 720p UI, shrinking at draw time made it look jagged).
     */
    private void drawLogo(Canvas c, HomeApps.Item it, RectF box, float s, int mode) {
        boolean rest = s < 1 + FOCUS_GROW / 2;
        Bitmap logo = crispLogo(it, mode == 2 && it.logoDark != null, rest);
        if (logo == null) return;
        float scale = rest ? s : s / (1 + FOCUS_GROW);
        float dw = logo.getWidth() * scale, dh = logo.getHeight() * scale;
        float left = box.centerX() - dw / 2, top = box.centerY() - dh / 2;
        if (rest ? Math.abs(scale - 1) < 0.002f : scale > 0.998f) {
            left = Math.round(left);
            top = Math.round(top);
        }
        tmp.set(left, top, left + dw, top + dh);
        bitmapPaint.setColorFilter(mode == 1 ? whiteLogo : null);
        c.drawBitmap(logo, null, tmp, bitmapPaint);
        bitmapPaint.setColorFilter(null);
    }

    private Bitmap crispLogo(HomeApps.Item it, boolean dark, boolean rest) {
        Bitmap src = dark ? it.logoDark : it.logo;
        if (tileRects.length == 0) return src;
        String key = it.key + "@" + System.identityHashCode(src) + "@" + Math.round(tileRects[0].width()) + (rest ? "@rest" : "");
        Bitmap cached = crispLogos.get(key);
        if (cached != null) return cached;
        float bw = tileRects[0].width() * (1 + FOCUS_GROW), bh = tileRects[0].height() * (1 + FOCUS_GROW);
        float lw = src.getWidth(), lh = src.getHeight();
        float scale = (float) Math.sqrt(bw * bh * 0.085f / (lw * lh * Math.max(0.08f, it.logoInk))) * it.logoScale;
        scale = Math.min(scale, Math.min(bw * 0.76f / lw, bh * 0.58f / lh));
        if (rest) scale /= 1 + FOCUS_GROW;
        Bitmap out = Ui.downscale(src, Math.round(lw * scale), Math.round(lh * scale));
        crispLogos.put(key, out);
        return out;
    }

    // MARK: - Bottom drawer

    private static List<HomeApps.Item> drawerList(Context c) {
        List<HomeApps.Item> list = HomeApps.allInputs(c);
        list.addAll(HomeApps.drawerShortcuts(c));
        return list;
    }

    private void setDrawerItems(List<HomeApps.Item> list) {
        int inputs = 0;
        while (inputs < list.size() && (list.get(inputs).kind == HomeApps.INPUT || list.get(inputs).kind == HomeApps.LIVE_TV)) inputs++;
        boolean same = list.size() == drawerItems.size();
        for (int i = 0; same && i < list.size(); i++) {
            same = list.get(i).key.equals(drawerItems.get(i).key) && list.get(i).connected == drawerItems.get(i).connected;
        }
        drawerItems = list;
        drawerInputs = inputs;
        if (drawerFocus.length != list.size()) {
            drawerFocus = new Spring[list.size()];
            drawerRects = new RectF[list.size()];
            for (int i = 0; i < drawerFocus.length; i++) {
                drawerFocus[i] = new Spring(0.28f, 0.85f, 0);
                drawerRects[i] = new RectF();
            }
            drawerIndex = Math.min(drawerIndex, Math.max(0, list.size() - 1));
            retarget(true);
        }
        layoutDrawer();
        if (!same) drawerDirty = true;
    }

    private void layoutDrawer() {
        int n = drawerItems.size();
        float left = MARGIN * k, right = CONTENT_R * k;
        float gap = 18 * k, groupGap = 56 * k;
        int groups = drawerInputs > 0 && drawerInputs < n ? 2 : 1;
        float tw = Math.min(230 * k, (right - left - Math.max(0, n - groups) * gap - (groups - 1) * groupGap) / Math.max(1, n));
        float x = left, top = ROW_TOP * k;
        for (int i = 0; i < n; i++) {
            if (i == drawerInputs && i > 0) x += groupGap - gap;
            drawerRects[i].set(x, top, x + tw, top + ROW_H * k);
            snap(drawerRects[i]);
            x += tw + gap;
        }
        float cardsTop = CARDS_TOP * k, split = left + (right - left) * 0.585f;
        boolean wx = Prefs.weather(), qt = Prefs.quotes() && HomeData.active().length > 0;
        // A card that is off gives its room to the other one.
        weatherCard.set(left, cardsTop, qt ? split - 16 * k : right, cardsTop + CARDS_H * k);
        quotesCard.set(wx ? split + 16 * k : left, cardsTop, right, cardsTop + CARDS_H * k);
        snap(weatherCard);
        snap(quotesCard);
        // The symbols at their sizes at rest and focused: drawing them never scales one.
        for (HomeApps.Item it : drawerItems) {
            Symbols.warm(getContext(), it.symbol, 34 * k);
            Symbols.warm(getContext(), it.symbol, 34 * k * 1.07f);
        }
        Symbols.warmWeather(getContext(), 52 * k);
        Symbols.warmWeather(getContext(), 30 * k);
        Symbols.warm(getContext(), "sunrise", 22 * k);
        Symbols.warm(getContext(), "sunset", 22 * k);
        drawerDirty = true;
    }

    private void openDrawer() {
        if (drawerItems.isEmpty()) return;
        drawerOpen = true;
        drawer.target = 1;
        zone = Z_DRAWER;
        drawerIndex = shelf.isEmpty() ? 0 : nearest(drawerRects, tileRects[shelfIndex].centerX());
        for (int i = 0; i < drawerFocus.length; i++) drawerFocus[i].snap(i == drawerIndex ? 1 : 0);
        drawerDirty = true;
    }

    private void closeDrawer() {
        drawerOpen = false;
        drawer.target = 0;
        zone = Z_SHELF;   // back to the apps (the Top Shelf's drawer opens below Your Apps)
        for (Spring f : drawerFocus) f.snap(0);   // the drawer goes away: its focus doesn't need to animate
        if (!shelf.isEmpty() && drawerIndex < drawerRects.length) shelfIndex = nearest(tileRects, drawerRects[drawerIndex].centerX());
    }

    /** Color for the drawer's text and symbols: dark by day, white at night. */
    private int drawerInk(float alpha) {
        return Ui.alpha(tone(L_INK, 0xFFFFFFFF), alpha);
    }

    /** Glass for a drawer card or button: white by day, smoked at night (both during the transition). */
    private void drawerGlass(Canvas c, RectF r, float radius, float lightTint, int shadowColor, float drop) {
        float dk = dark();
        if (dk < 0.999f) {
            tmp.set(r);
            tmp.offset(0, drop);
            shadow.setColor(Ui.alpha(shadowColor, 1 - dk));
            shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
            scene.glass(c, r, radius, 0xFFFFFFFF, lightTint, 0, 0, 0.02f, 1, 1 - dk);
        }
        if (dk > 0.001f) scene.glass(c, r, radius, SMOKE, 0.4f, 0, 0, 0.02f, 1, dk);
    }

    private void drawerCard(Canvas c, RectF r) {
        drawerGlass(c, r, 30 * k, 0.72f, 0x1A000000, 10 * k);
    }

    private void drawDrawer(Canvas c) {
        if (drawerItems.isEmpty()) return;
        rowTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardText.setColor(drawerInk(0.95f));
        cardDim.setColor(tone(L_DIM, 0xADFFFFFF));
        cardDimRight.setColor(tone(L_FAINT, 0x99FFFFFF));
        bigTemp.setColor(drawerInk(1));
        hourPaint.setColor(tone(L_DIM, 0xADFFFFFF));
        hourTemp.setColor(drawerInk(0.95f));
        pricePaint.setColor(drawerInk(0.95f));
        float titleBase = ROW_TITLE * k;
        if (drawerInputs > 0) c.drawText(Ui.tr("Inputs"), drawerRects[0].left + 4 * k, titleBase, rowTitle);
        if (drawerInputs < drawerItems.size()) c.drawText(Ui.tr("Shortcuts"), drawerRects[drawerInputs].left + 4 * k, titleBase, rowTitle);
        int top = -1;
        for (int i = 0; i < drawerItems.size(); i++) if (drawerFocus[i].value > 0.02f && (top < 0 || drawerFocus[i].value > drawerFocus[top].value)) top = i;
        for (int i = 0; i < drawerItems.size(); i++) if (i != top) drawDrawerTile(c, i);
        if (top >= 0) drawDrawerTile(c, top);
        if (Prefs.weather()) drawWeatherCard(c, weatherCard);
        if (Prefs.quotes() && HomeData.active().length > 0) drawQuotesCard(c, quotesCard);
    }

    /** Square drawer button: symbol and name; an input with no device is dimmed. Focused: white, with dark text. */
    private void drawDrawerTile(Canvas c, int i) {
        HomeApps.Item it = drawerItems.get(i);
        float f = Ui.clamp(drawerFocus[i].value), s = 1 + 0.07f * f;
        RectF base = drawerRects[i];
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = 20 * k * s;
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 12 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.45f * f));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (f < 0.99f) drawerGlass(c, cell, r, 0.7f, 0x14000000, 6 * k);
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(WHITE, f));
            c.drawRoundRect(cell, r, r, fill);
        }
        boolean off = it.kind == HomeApps.INPUT && !it.connected;
        int ink = blend(tone(off ? 0xFF8E8E93 : L_INK, off ? 0x6BFFFFFF : 0xF2FFFFFF), INK, f);
        Symbols.draw(getContext(), c, it.symbol, cx, cell.top + cell.height() * 0.4f, 34 * k * s, ink);
        drawerLabel.setColor(ink);
        drawerLabel.setTextSize(23 * k * s);
        float room = cell.width() - 16 * k, wide = drawerLabel.measureText(it.label);
        // A long name ("Home Settings", an input you renamed) shrinks a little before it gets cut.
        if (wide > room) drawerLabel.setTextSize(Math.max(18 * k * s, 23 * k * s * room / wide));
        fitPaint.set(drawerLabel);
        String label = TextUtils.ellipsize(it.label, fitPaint, room, TextUtils.TruncateAt.END).toString();
        c.drawText(label, cx, cell.top + cell.height() * 0.8f, drawerLabel);
        drawerLabel.setTextSize(23 * k);
    }

    /** Weather for your location: now (large), the next 6 hours, and sunrise and sunset. */
    private void drawWeatherCard(Canvas c, RectF r) {
        drawerCard(c, r);
        if (Ui.LEGACY) weatherSnapshot.draw(c, r, drawerCardsKey(), cc -> drawWeather(cc, r));
        else drawWeather(c, r);
    }

    private void drawWeather(Canvas c, RectF r) {
        refreshDrawerText();
        float x = r.left + 34 * k;
        c.drawText(weatherTitle, x, r.top + 54 * k, cardTitle);
        HomeData.Weather wx = textWeather;
        if (Double.isNaN(wx.temp)) {
            c.drawText(Ui.tr("No weather data right now."), x, r.top + 120 * k, cardDim);
            return;
        }
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, 52 * k);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, r.top + 132 * k, 52 * k, drawerInk(0.95f));
        c.drawText(nowTemp, x + sw + 18 * k - Ui.leftBearing(bigTemp, nowTemp), r.top + 170 * k, bigTemp);
        c.drawText(HomeData.condition(wx.code, wx.isDay), x, r.top + 220 * k, cardText);
        c.drawText(highLow, x, r.top + 258 * k, cardDim);
        float split = r.left + 392 * k;
        fill.setShader(null);
        fill.setColor(tone(0x1A000000, 0x24FFFFFF));
        c.drawRoundRect(split - k, r.top + 34 * k, split + k, r.bottom - 34 * k, k, k, fill);
        float hx = split + 30 * k, hw = (r.right - 30 * k - hx) / 6;
        for (int i = 0; i < wx.hours.size() && i < 6; i++) {
            float hc = hx + hw * (i + 0.5f);
            c.drawText(hourText[i], hc, r.top + 96 * k, hourPaint);
            Symbols.draw(getContext(), c, Symbols.weather(wx.hourCode.get(i), wx.hourDay.get(i)), hc, r.top + 142 * k, 30 * k, drawerInk(0.9f));
            c.drawText(hourTempText[i], hc, r.top + 196 * k, hourTemp);
        }
        float sx = hx, base = r.top + 258 * k;
        if (wx.sunrise != 0) sx = sunItem(c, "sunrise", sunriseText, sx, base) + 36 * k;
        if (wx.sunset != 0) sunItem(c, "sunset", sunsetText, sx, base);
    }

    private void refreshDrawerText() {
        HomeData.Weather wx = data.weather;
        boolean settings = Prefs.version != textPrefs || !Ui.language.equals(textLanguage);
        textPrefs = Prefs.version;
        textLanguage = Ui.language;
        if (wx != textWeather || settings) {
            textWeather = wx;
            weatherTitle = Prefs.city().isEmpty() ? Ui.tr("Weather") : Prefs.city();
            nowTemp = HomeData.degrees(wx.temp);
            highLow = HomeData.highLow(wx, "   ");
            for (int i = 0; i < wx.hours.size() && i < 6; i++) {
                hourText[i] = HomeData.hour(wx.hours.get(i));
                hourTempText[i] = HomeData.degrees(wx.hourTemp.get(i));
            }
            sunriseText = Ui.trf("Sunrise {time}", "time", Ui.time(wx.sunrise));
            sunsetText = Ui.trf("Sunset {time}", "time", Ui.time(wx.sunset));
        }
        long minute = System.currentTimeMillis() / 60_000;
        if (data.pricesAt != textPricesAt || minute != textMinute || settings) {
            textPricesAt = data.pricesAt;
            textMinute = minute;
            String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
            staleText = stale.isEmpty() ? Ui.tr("24h change") : Ui.trf("quotes|{ago}", "ago", stale);
            for (int i = 0; i < HomeData.COINS.length; i++) {
                priceText[i] = HomeData.brl(data.prices[i]);
                changeText[i] = HomeData.change(data.changes[i]);
            }
        }
    }

    private float sunItem(Canvas c, String symbol, String label, float x, float base) {
        float size = 22 * k, sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(cardDim), size, tone(L_DIM, 0xB3FFFFFF));
        c.drawText(label, x + sw + 10 * k, base, cardDim);
        return x + sw + 10 * k + cardDim.measureText(label);
    }

    /** Prices in the configured currency with the 24 h change (green/red, like Apple's Stocks app). */
    private void drawQuotesCard(Canvas c, RectF r) {
        drawerCard(c, r);
        if (Ui.LEGACY) quotesSnapshot.draw(c, r, drawerCardsKey(), cc -> drawPrices(cc, r));
        else drawPrices(c, r);
    }

    /**
     * What the drawer cards show depends on: the data, the settings, the language, day or night, and the minute (the
     * sunrise and sunset, "12 min ago").
     */
    private String drawerCardsKey() {
        return data.weatherAt + "|" + data.pricesAt + "|" + Prefs.version + "|" + Ui.language + "|"
                + Float.floatToIntBits(dark()) + "|" + System.currentTimeMillis() / 60_000;
    }

    /**
     * Android 8 to 11: a drawer card's content (its text and symbols, not its glass) kept as a bitmap. The drawer is
     * redrawn on every frame of its focus animation, and laying that text out again took ~15 ms a frame on a Mi Box 3.
     */
    private static final class Snapshot {
        private Bitmap bitmap;
        private String key = "";

        void draw(Canvas c, RectF r, String key, Consumer<Canvas> content) {
            int left = Math.round(r.left), top = Math.round(r.top);
            int w = Math.max(1, Math.round(r.right) - left), h = Math.max(1, Math.round(r.bottom) - top);
            if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                this.key = "";
            }
            if (!key.equals(this.key)) {
                bitmap.eraseColor(0);
                Canvas cc = new Canvas(bitmap);
                cc.translate(-left, -top);
                content.accept(cc);
                this.key = key;
            }
            c.drawBitmap(bitmap, left, top, null);
        }
    }

    private final Snapshot weatherSnapshot = new Snapshot(), quotesSnapshot = new Snapshot();

    private void drawPrices(Canvas c, RectF r) {
        float x = r.left + 34 * k, right = r.right - 34 * k;
        refreshDrawerText();
        c.drawText(Ui.tr("Crypto"), x, r.top + 54 * k, cardTitle);
        c.drawText(staleText, right, r.top + 54 * k, cardDimRight);
        float cap = Ui.cap(cardText);
        int[] coins = HomeData.active();
        for (int j = 0; j < coins.length; j++) {
            int i = coins[j];
            float base = r.top + (110 + 52 * j) * k, mid = base - cap / 2;
            float gw = Glyphs.width(getContext(), HomeData.COINS[i], cap);
            Glyphs.coin(getContext(), c, HomeData.COINS[i], x + 14 * k, mid, cap, tone(L_DIM, 0xCCFFFFFF));
            c.drawText(HomeData.coinName(i), x + Math.max(gw, 28 * k) / 2 + 30 * k, base, cardText);
            c.drawText(priceText[i], right - 128 * k, base, pricePaint);
            double change = data.changes[i];
            if (Double.isNaN(change)) continue;
            int color = change >= 0 ? tone(0xFF248A3D, UP_COLOR) : tone(0xFFD70015, DOWN_COLOR);
            tmp.set(right - 108 * k, mid - 17 * k, right, mid + 17 * k);
            fill.setShader(null);
            fill.setColor(Ui.alpha(color, 0.2f));
            c.drawRoundRect(tmp, 17 * k, 17 * k, fill);
            changePaint.setColor(color);
            c.drawText(changeText[i], tmp.centerX(), mid + Ui.halfCap(changePaint), changePaint);
        }
    }

    // MARK: - Focus

    /** Sets the springs' targets from the current focus (snap: no animation). */
    private void retarget(boolean snap) {
        revealShelf(snap);
        // Only the group whose focus changed is redrawn (moving along the shelf doesn't redraw the hero or capsule).
        boolean tilesChanged = aim(tileFocus, !sheetOpen && zone == Z_SHELF ? shelfIndex : -1, snap);
        boolean statusChanged = aim(statusFocus, !sheetOpen && zone == Z_STATUS ? statusIndex : -1, snap);
        boolean drawerChanged = aim(drawerFocus, !sheetOpen && zone == Z_DRAWER ? drawerIndex : -1, snap);
        boolean heroChanged = heroFocus.target != (!sheetOpen && zone == Z_HERO ? 1 : 0);
        heroFocus.target = !sheetOpen && zone == Z_HERO ? 1 : 0;
        if (snap) heroFocus.snap(heroFocus.target);
        boolean topChanged = aim(topFocus, !sheetOpen && zone == Z_TOP ? topIndex : -1, snap);
        heroChanged |= aim(cardFocus, !sheetOpen && zone == Z_CARDS ? cardIndex : -1, snap) | topChanged;
        if (TOP_SHELF) {
            heroChanged |= aim(actionFocus, !sheetOpen && !infoOpen && zone == Z_ACTIONS ? actionIndex : -1, snap);
            heroChanged |= aim(tabFocus, !sheetOpen && zone == Z_TABS && !weatherFocus && !profileFocus ? tabIndex : -1, snap);
            float profileTarget = !sheetOpen && !weatherOpen && zone == Z_TABS && profileFocus ? 1 : 0;
            heroChanged |= profilePill.target != profileTarget;
            profilePill.target = profileTarget;
            if (snap) profilePill.snap(profileTarget);
            float pillTarget = !sheetOpen && !weatherOpen && zone == Z_TABS && weatherFocus ? 1 : 0;
            heroChanged |= weatherPill.target != pillTarget;
            weatherPill.target = pillTarget;
            if (snap) weatherPill.snap(pillTarget);
            info.target = infoOpen || weatherOpen || searchOpen ? 1 : 0;
            if (snap) info.snap(info.target);
            heroChanged |= retargetStack(snap);
        }
        statusChanged |= topChanged;
        tilesDirty |= snap || tilesChanged;
        statusDirty |= snap || statusChanged;
        heroDirty |= snap || heroChanged;
        drawerDirty |= snap || drawerChanged;
        spoken.focusMoved();
    }

    /** Aims a group's springs (only `focused` at 1); returns true if any target changed. */
    private static boolean aim(Spring[] springs, int focused, boolean snap) {
        boolean changed = false;
        for (int i = 0; i < springs.length; i++) {
            float target = i == focused ? 1 : 0;
            changed |= springs[i].target != target;
            springs[i].target = target;
            if (snap) springs[i].snap(target);
        }
        return changed;
    }

    // MARK: - "All Apps" sheet

    private void setOthers(List<HomeApps.Item> list) {
        others = list;
        gridFocus = new Spring[list.size()];
        for (int i = 0; i < gridFocus.length; i++) gridFocus[i] = new Spring(0.3f, 0.82f, 0);
        gridLabels = null;
        gridIndex = Math.min(gridIndex, Math.max(0, list.size() - 1));
        retargetGrid(true);
        gridDirty = true;
    }

    private void openSheet() {
        sheetOpen = true;
        gridIndex = 0;
        scroll.snap(0);
        retargetGrid(true);
        retarget(false);
        sheet.target = 1;
        gridDirty = true;
    }

    private void closeSheet() {
        sheetOpen = false;
        sheet.target = 0;
        retarget(false);
        if (shelfEdited) {     // the grid was kept as it was while open: apps that went to the shelf leave it now
            shelfEdited = false;
            reload();
        }
    }

    private void retargetGrid(boolean snap) {
        for (int i = 0; i < gridFocus.length; i++) {
            gridFocus[i].target = sheetOpen && i == gridIndex ? 1 : 0;
            if (snap) gridFocus[i].snap(gridFocus[i].target);
        }
        int rows = Math.max(1, (others.size() + COLUMNS - 1) / COLUMNS);
        int visible = Math.min(rows, VISIBLE_ROWS);
        int r = gridIndex / COLUMNS;
        float target = scroll.target;
        if (r < target) target = r;
        else if (r > target + visible - 1) target = r - visible + 1;
        scroll.target = Math.max(0, Math.min(rows - visible, target));
        if (snap) scroll.snap(scroll.target);
        spoken.focusMoved();
    }

    private void drawSheet(Canvas c) {
        c.drawColor(0xA8000000);
        float shift = scroll.value * GRID_ROW * k;
        c.drawText(Ui.tr("All Apps"), w / 2f, 124 * k - shift, sheetTitle);
        int n = others.size();
        if (gridLabels == null || gridLabels.length != n) {
            gridLabels = new String[n];
            for (int i = 0; i < n; i++) {
                gridLabels[i] = TextUtils.ellipsize(others.get(i).label, gridLabel, (GRID_W - 10) * k, TextUtils.TruncateAt.END).toString();
            }
        }
        float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
        float left = (1920 - gridWidth) / 2 * k;
        float gridTop = GRID_TOP * k;
        for (int i = 0; i < n; i++) {
            int r = i / COLUMNS, col = i % COLUMNS;
            float y = gridTop + r * GRID_ROW * k - shift;
            if (y > h || y + GRID_ROW * k < 0) continue;
            float x = left + col * (GRID_W + GRID_GAP) * k;
            gridCell.set(x, y, x + GRID_W * k, y + GRID_H * k);
            float f = gridFocus[i].value;
            drawCard(c, others.get(i), gridCell, GRID_RADIUS * k, f, 0.08f);
            if (sheetOpen && i == gridIndex) focusedCell.set(cell);
            gridLabel.setColor(blend(0x99FFFFFF, 0xFFFFFFFF, Ui.clamp(f)));
            c.drawText(gridLabels[i], gridCell.centerX(), gridCell.bottom + (40 + 6 * f) * k, gridLabel);
        }
    }

    /** 16:9 sheet card. Focused (the shelf's subtle focus): it grows, with a shadow, a soft sheen and a thin border. */
    private void drawCard(Canvas c, HomeApps.Item it, RectF base, float radius, float f, float grow) {
        float s = 1 + grow * f;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = radius * s;
        float fc = Ui.clamp(f);
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 16 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.55f * fc));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (it.tile != null) roundImage(c, it.tile, it.tileShader, cell, r);
        if (f > 0.01f) {
            float bh = base.height();
            if (cardSheen == null || cardSheenH != bh) {
                cardSheen = new LinearGradient(0, -bh / 2, 0, 0, 0x1AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                cardSheenH = bh;
            }
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-base.width() / 2, -bh / 2, base.width() / 2, bh / 2);
            sheen.setShader(cardSheen);
            sheen.setAlpha(Math.round(255 * fc));
            c.drawRoundRect(tmp, radius, radius, sheen);
            sheen.setAlpha(255);
            c.restore();
        }
        if (fc < 0.99f) {
            veil.setColor(Ui.alpha(0xFF000000, 0.08f * (1 - fc)));
            c.drawRoundRect(cell, r, r, veil);
        }
        border.setColor(0x14FFFFFF);
        c.drawRoundRect(cell, r, r, border);
        if (f > 0.01f) {
            rim.setShader(null);
            rim.setColor(Ui.alpha(0xFFFFFFFF, 0.4f * fc));
            rim.setStrokeWidth(1.5f * k);
            c.drawRoundRect(cell, r, r, rim);
            rim.setStrokeWidth(2.5f * k);
        }
    }

    // MARK: - Toasts

    void showToast(String text) {
        toasts.add(text);
        announceForAccessibility(text);
        wake();
    }

    /** Advances the toast queue; returns true while a toast fades in or out (animation). */
    private boolean tickToast(long up) {
        if (toast != null && up - toastAt > TOAST_MS) {
            toast = null;
            toastLayer.invalidate();
        }
        if (toast == null && toastPaint != null) {
            String next = toasts.poll();
            if (next != null) {
                toast = layout(next);
                toastAt = up;
            }
        }
        if (toast == null) return false;
        long age = up - toastAt;
        boolean fading = age < 350 || age > TOAST_MS - 450;
        if (fading) toastLayer.invalidate();
        return fading;
    }

    private void drawToast(Canvas c) {
        if (toast == null) return;
        float age = (SystemClock.uptimeMillis() - toastAt) / 1000f;
        float a = Math.min(Ui.clamp(age / 0.3f), Ui.clamp((TOAST_MS / 1000f - age) / 0.4f));
        if (a <= 0) return;
        float padX = 28 * k, padY = 18 * k, icon = 26 * k, iconW = Symbols.width(getContext(), "bell", icon);
        float tw = toast.getWidth() + iconW + 16 * k, th = toast.getHeight();
        float toastTop = 64 * k - (1 - a) * 16 * k;
        tmp.set((w - tw) / 2f - padX, toastTop, (w + tw) / 2f + padX, toastTop + th + 2 * padY);
        int save = c.saveLayerAlpha(tmp.left - 4, tmp.top - 4, tmp.right + 4, tmp.bottom + 4, Math.round(255 * a));
        scene.glass(c, tmp, Math.min(tmp.height() / 2, 32 * k), SMOKE, 0.55f, 0, 0, 0.02f, 1);
        Symbols.draw(getContext(), c, "bell", tmp.left + padX + iconW / 2, tmp.centerY(), icon, 0xEBFFFFFF);
        c.translate(tmp.left + padX + iconW + 16 * k, tmp.top + padY);
        toast.draw(c);
        c.restoreToCount(save);
    }

    private StaticLayout layout(String text) {
        int max = Math.round(720 * k);
        int width = (int) Math.min(max, Math.ceil(Layout.getDesiredWidth(text, toastPaint)));
        return StaticLayout.Builder.obtain(text, 0, text.length(), toastPaint, Math.max(1, width))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
    }

    /** New notices from the main process (mode switches, the check after the TV boots): those from the last 24 h. */
    private void checkNotices() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            JSONArray list = HomeMode.state(c).optJSONArray("notices");
            if (list == null) return;
            long seen = ui.getLong("seenNotice", 0), newest = seen;
            long cutoff = System.currentTimeMillis() - 24 * 3600_000L;
            final List<String> fresh = new ArrayList<>();
            for (int i = list.length() - 1; i >= 0; i--) {   // the list comes newest first
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                long at = o.optLong("at");
                if (at <= seen || at < cutoff) continue;
                fresh.add(o.optString("text"));
                newest = Math.max(newest, at);
            }
            if (newest > seen) ui.edit().putLong("seenNotice", newest).apply();
            if (!fresh.isEmpty()) handler.post(() -> { for (String t : fresh) showToast(t); });
        });
    }

    // MARK: - Lists (in the background, so returning to the home screen isn't delayed)

    private void reload() {
        final Context c = getContext().getApplicationContext();
        // The shelf and All Apps (100 to 500 ms of package queries on a TV) only when they can have changed.
        long up = SystemClock.uptimeMillis();
        final boolean apps = appsStale || Prefs.version != appsVersion || Ui.generation != appsGeneration
                || up - appsAt > APPS_MAX_AGE_MS;
        if (apps) {
            appsStale = false;
            appsVersion = Prefs.version;
            appsGeneration = Ui.generation;
            appsAt = up;
        }
        LOADER.execute(() -> {
            final List<HomeApps.Item> s = apps ? HomeApps.shelf(c, true) : null;
            final List<HomeApps.Item> st = statusList(c);
            final List<HomeApps.Item> d = drawerList(c);
            final List<HomeApps.Item> o = apps ? HomeApps.others(c) : null;
            final List<HomeApps.Item> top = apps ? HomeApps.topButtons() : null;   // they change with Prefs too
            handler.post(() -> {
                if (top != null) setLightButtons(top);
                if (s != null) setShelf(s);
                setStatusItems(st);
                setDrawerItems(d);
                if (o != null && !sheetOpen) setOthers(o);
                else if (o != null) appsStale = true;   // All Apps is open: its list comes next time
            });
        });
    }

    /** Apps installed, removed or updated (also while another app is open): the next reload reads them again. */
    private final LauncherApps.Callback packages = new LauncherApps.Callback() {
        @Override public void onPackageAdded(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackageRemoved(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackageChanged(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackagesAvailable(String[] pkgs, UserHandle user, boolean replacing) { appsChanged(); }
        @Override public void onPackagesUnavailable(String[] pkgs, UserHandle user, boolean replacing) { appsChanged(); }
    };

    private void appsChanged() {
        appsStale = true;
        if (running) reloadSoon();
    }

    // MARK: - Keys: only the arrows, OK and Back (system keys pass through to the system)

    boolean onKey(KeyEvent e) {
        wake();
        untouched = false;
        if (TOP_SHELF && running) {
            // The carousel moves on by itself only while the remote rests: every press starts its wait over.
            handler.removeCallbacks(rotateHero);
            handler.postDelayed(rotateHero, HERO_ROTATE_MS);
        }
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (reordering) return reorderKey(e, down);
        int code = e.getKeyCode();
        if (down && code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER
                && code != KeyEvent.KEYCODE_NUMPAD_ENTER) {
            holdArmed = false;      // another key while OK is held: no move mode
            handler.removeCallbacks(holdStart);
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) move(-1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) move(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) move(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Opens on release, and only if the press happened here: the release of an OK pressed inside another
                // app opens nothing.
                // Held (~0.5 s) on a shelf app: move mode; on an app in All Apps: on or off the shelf. A timer, since
                // not every remote repeats a held key, or the long-press repeat, whichever comes first; the release
                // after a hold opens nothing.
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        okDown = true;
                        okHeld = false;
                        handler.removeCallbacks(holdStart);
                        holdArmed = sheetOpen ? canToggleShelf(gridIndex) : canReorder() || canForgetWatched();
                        if (holdArmed) handler.postDelayed(holdStart, HOLD_MS);
                    } else if (okDown && holdArmed && e.isLongPress()) {
                        hold();
                    }
                } else {
                    holdArmed = false;
                    handler.removeCallbacks(holdStart);
                    if (okDown && !okHeld && !e.isCanceled() && spoken.keyClick()) activate();
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) back();
                    backDown = false;
                }
                return true;
            case KeyEvent.KEYCODE_SEARCH:
            case KeyEvent.KEYCODE_ASSIST:
                // A remote's search or mic key that reaches the app (RemoteKeyService takes it first in EndGrid iQTV+ mode).
                if (!down && e.getRepeatCount() == 0) searchFromRemote();
                return true;
            default:
                return false;
        }
    }

    private void back() {
        if (searchOpen) {
            if (searchTyping) stopTyping(false);   // the keyboard was already closed by Back
            else closeSearch();
        } else if (weatherOpen) {
            closeWeather();
        } else if (infoOpen) {
            closeInfo();
        } else if (sheetOpen) {
            closeSheet();
        } else if (searchResults != null && (zone == Z_CARDS || zone == Z_ACTIONS || zone == Z_TOP)) {
            endSearch();    // Back leaves the search results
        } else if (zone == Z_DRAWER || zone == WIDGETS) {
            closeDrawer();
            retarget(false);
        } else if (TOP_SHELF && (zone == Z_CARDS || zone == Z_SHELF)) {
            toTop();     // tvOS: Back from the rows returns to the top, on the featured title
            retarget(false);
        } else if (TOP_SHELF) {
            return;      // at the top already: Back has nowhere to go on a home screen
        } else if (zone != Z_SHELF) {
            zone = Z_SHELF;
            retarget(false);
        } else {
            return;
        }
        click.move();
    }

    private void move(int dx, int dy) {
        if (searchOpen) {
            moveSearch(dx, dy);
            return;
        }
        if (infoOpen || weatherOpen) return;   // More Info and Weather: only OK and Back
        if (sheetOpen) {
            moveGrid(dx, dy);
            return;
        }
        if (TOP_SHELF ? moveTopShelf(dx, dy) : moveLight(dx, dy)) return;
        switch (zone) {
            case Z_SHELF:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(shelf.size() - 1, shelfIndex + dx));
                    if (next == shelfIndex) return;
                    shelfIndex = next;
                } else if (dy < 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else if (!statusItems.isEmpty()) {
                        zone = Z_STATUS;
                        statusIndex = shelf.isEmpty() ? 0 : nearest(statusRects, tileRects[shelfIndex].centerX());
                    } else {
                        return;
                    }
                } else {
                    if (drawerItems.isEmpty()) return;
                    openDrawer();
                }
                break;
            case Z_HERO:
                if (dx != 0) {
                    if (!browse(dx)) return;
                } else if (dy < 0) {
                    if (statusItems.isEmpty()) return;
                    zone = Z_STATUS;
                    statusIndex = 0;
                } else {
                    zone = Z_SHELF;
                }
                break;
            case Z_STATUS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(statusItems.size() - 1, statusIndex + dx));
                    if (next == statusIndex) return;
                    statusIndex = next;
                } else if (dy > 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else {
                        zone = Z_SHELF;
                        if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, statusRects[statusIndex].centerX());
                    }
                } else {
                    return;
                }
                break;
            case WIDGETS: {
                List<Integer> on = widgets();
                int at = on.indexOf(widgetIndex);
                if (dy < 0 || at < 0) {
                    zone = Z_DRAWER;
                } else if (dx != 0 && at + dx >= 0 && at + dx < on.size()) {
                    widgetIndex = on.get(at + dx);
                } else {
                    return;
                }
                break;
            }
            default:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(drawerItems.size() - 1, drawerIndex + dx));
                    if (next == drawerIndex) return;
                    drawerIndex = next;
                } else if (dy < 0) {
                    closeDrawer();
                } else if (talkBack() && !widgets().isEmpty()) {
                    // Only with TalkBack: the widgets are there to be read, and this is how it gets to them.
                    zone = WIDGETS;
                    widgetIndex = widgets().get(0);
                } else {
                    return;
                }
                break;
        }
        retarget(false);
        click.move();
    }

    private static int nearest(RectF[] rects, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < rects.length; i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    private void moveGrid(int dx, int dy) {
        int n = others.size();
        if (n == 0) return;
        int r = gridIndex / COLUMNS, col = gridIndex % COLUMNS;
        int rows = (n + COLUMNS - 1) / COLUMNS;
        int next = gridIndex;
        if (dx != 0) {
            int candidate = gridIndex + dx;
            if (candidate >= 0 && candidate < n && candidate / COLUMNS == r) next = candidate;
        } else {
            int targetRow = r + dy;
            if (targetRow >= 0 && targetRow < rows) next = Math.min(n - 1, targetRow * COLUMNS + col);
        }
        if (next == gridIndex) return;
        gridIndex = next;
        retargetGrid(false);
        click.move();
        gridDirty = true;
    }

    private void activate() {
        wake();
        if (searchOpen) {
            activateSearch();
            return;
        }
        if (weatherOpen) {
            closeWeather();
            return;
        }
        if (reordering) {   // TalkBack's click while moving an app: drop it
            dropReorder();
            return;
        }
        HomeApps.Item it;
        RectF r;
        if (!sheetOpen && zone == WIDGETS) return;   // a widget is only read
        if (sheetOpen) {
            if (others.isEmpty()) return;
            it = others.get(gridIndex);
            r = focusedCell;
        } else if (zone == Z_HERO) {
            Recs.Pick p = currentPick();
            if (p == null || p.pkg == null) return;
            it = HomeApps.app(p.pkg, Recs.serviceName(p), p.link);
            r = heroButton;
        } else if (zone == Z_STATUS) {
            if (statusItems.isEmpty()) return;
            it = statusItems.get(statusIndex);
            r = statusRects[statusIndex];
        } else if (zone == Z_DRAWER) {
            if (drawerItems.isEmpty()) return;
            it = drawerItems.get(drawerIndex);
            r = drawerRects[drawerIndex];
        } else if (zone == Z_TOP) {
            if (topIndex < 3) {
                selectTab(topIndex);
                return;
            }
            it = lightButtons.get(topIndex - 3);
            r = topRects[topIndex];
        } else if (zone == Z_CARDS) {
            Recs.Pick p = cardIndex < cards.size() ? cards.get(cardIndex) : null;
            if (p == null) return;
            if (p.pkg == null) {
                showToast(Ui.trf("{title} isn't on any of your apps.", "title", p.title));
                return;
            }
            it = HomeApps.app(p.pkg, Recs.serviceName(p), p.link);
            r = TOP_SHELF ? thumbRect(stackIndex, cardIndex, focusedCell) : cardRects[cardIndex];
        } else if (zone == Z_ACTIONS || infoOpen) {
            Recs.Pick p = shelfPick;
            if (p == null) return;
            List<Integer> acts = actions();
            int act = infoOpen ? ACT_PLAY : actionIndex < acts.size() ? acts.get(actionIndex) : -1;
            if (infoOpen) closeInfo();
            if (act == ACT_HIDE) {
                notForMe(p);
                return;
            }
            if (act == ACT_INFO) {
                openInfo();
                return;
            }
            if (p.pkg == null) return;
            it = HomeApps.app(p.pkg, Recs.serviceName(p), p.link);
            r = actionRects[0];
        } else if (zone == Z_TABS) {
            activateTab();
            return;
        } else {
            if (shelf.isEmpty()) return;
            it = shelf.get(shelfIndex);
            r = tileRects[shelfIndex];
        }
        click.open();
        if (it.kind == HomeApps.SEARCH) {
            openSearch();
            return;
        }
        if (it.kind == HomeApps.ALL_APPS) {
            openSheet();
            return;
        }
        if (it.kind == HomeApps.INPUTS) {
            openDrawer();
            for (int i = 0; i < drawerItems.size(); i++) {
                if (drawerItems.get(i).kind == HomeApps.INPUT) {
                    drawerIndex = i;
                    break;
                }
            }
            retarget(false);
            return;
        }
        boolean opened = HomeApps.open(activity, it, this, Math.round(r.left), Math.round(r.top),
                Math.round(r.width()), Math.round(r.height()));
        if (!opened) showToast(Ui.trf("Couldn't open {app}.", "app", it.label));
    }

    // MARK: - Holding OK: All Apps puts the app on the shelf or takes it off; on the shelf, the app is lifted to move

    private void hold() {
        if (sheetOpen) toggleShelf(); else if (canForgetWatched()) forgetWatched(); else startReorder();
    }

    /** On a card of Continue Watching or Recently Watched: holding OK takes it off the home screen. */
    private boolean canForgetWatched() {
        if (!TOP_SHELF || sheetOpen || infoOpen || zone != Z_CARDS) return false;
        Row row = focusedRow();
        return row != null && (row.id.equals("continue") || row.id.equals("recent")) && row.index < row.items.size();
    }

    /** Takes the focused title off Continue Watching (and Recently Watched) until it's played again; a toast says so. */
    private void forgetWatched() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (!canForgetWatched()) return;
        okHeld = true;     // the release must not open it
        Row row = focusedRow();
        Recs.Pick p = row.items.get(row.index);
        click.open();
        recs.forgetWatched(p);
        showToast(Ui.trf("Removed {title} from Continue Watching.", "title", p.title));
        updateRows();
        if (focusedRow() == null) toTop(); else focusRow(true);
        retarget(false);
    }

    /** TalkBack's long click on an app in All Apps (once per press, if the remote's hold also arrives). */
    private void a11yHold() {
        if (SystemClock.uptimeMillis() - toggledAt < 1000) return;
        if (okDown) okHeld = true;     // the remote's release must not open the app
        toggleShelf();
    }

    private boolean canToggleShelf(int i) {
        if (!sheetOpen || i >= others.size()) return false;
        HomeApps.Item it = others.get(i);
        return it.kind == HomeApps.APP && it.pkg != null;
    }

    /** Puts the focused app at the end of the shelf, or takes it off if it's there; a toast says which. */
    private void toggleShelf() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (!canToggleShelf(gridIndex)) return;
        okHeld = true;
        toggledAt = SystemClock.uptimeMillis();
        final HomeApps.Item it = others.get(gridIndex);
        final Context c = getContext().getApplicationContext();
        click.open();
        LOADER.execute(() -> {
            // On the shelf means shown there: installed and within the limit, as in Settings > Shelf.
            List<String> list = new ArrayList<>(HomeApps.shelfPackages(c));
            PackageManager pm = c.getPackageManager();
            int max = HomeApps.maxShelf(c), shown = 0;
            boolean on = false;
            for (String p : list) {
                if (shown >= max) break;
                if (HomeApps.launchIntent(pm, p) == null) continue;
                shown++;
                on |= p.equals(it.pkg);
            }
            final String text;
            list.remove(it.pkg);
            if (on) {
                text = Ui.trf("Removed from the shelf: {app}", "app", it.label);
            } else {
                if (shown >= max) {
                    handler.post(() -> showToast(Ui.tr("The shelf is full. Make room in EndGrid iQTV+ › Shelf.")));
                    return;
                }
                list.add(it.pkg);
                text = Ui.trf("Added to the shelf: {app}", "app", it.label);
            }
            Prefs.setShelf(list);   // here, so the next hold reads the new list
            handler.post(() -> {
                shelfEdited = true;
                showToast(text);
                reload();
            });
        });
    }

    // MARK: - Moving apps on the shelf

    private boolean canReorder() {
        if (sheetOpen || zone != Z_SHELF || shelfIndex >= shelf.size() || shelf.size() < 2) return false;
        HomeApps.Item it = shelf.get(shelfIndex);
        return it.kind == HomeApps.APP && it.pkg != null;
    }

    private void startReorder() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (reordering || !canReorder()) return;
        wake();
        reordering = reorderAnim = okHeld = true;
        reorderOrigin = new ArrayList<>(shelf);
        liftIndex = shelfIndex;
        reorderAt = SystemClock.uptimeMillis();
        moveLift.target = 1;
        click.open();
        showToast(Ui.tr("← → to move · OK to drop · Back to cancel"));
        retarget(false);    // TalkBack reads "Moving <app>, position i of n"
    }

    /** Keys while moving an app: nothing else on the home screen gets them (system keys still go to the system). */
    private boolean reorderKey(KeyEvent e, boolean down) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) shiftReorder(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // The press that lifted the app is still held: its repeats and release do nothing; a new press drops it.
                if (down && e.getRepeatCount() == 0) {
                    okDown = okHeld = false;
                    dropReorder();
                } else if (!down) {
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) {
                        cancelReorder(false);
                        click.move();
                    }
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    /** Swaps the lifted app with its neighbor; both slide from where they are to their new slots. */
    private void shiftReorder(int dx) {
        int i = shelfIndex, j = i + dx;
        if (j < 0 || j >= shelf.size()) return;
        HomeApps.Item it = shelf.get(i);
        shelf.set(i, shelf.get(j));
        shelf.set(j, it);
        Spring f = tileFocus[i];
        tileFocus[i] = tileFocus[j];
        tileFocus[j] = f;
        Spring s = tileSlide[i];
        tileSlide[i] = tileSlide[j];
        tileSlide[j] = s;
        float d = tileRects[i].left - tileRects[j].left;
        tileSlide[j].value += d;
        tileSlide[i].value -= d;
        if (tileShaders.length == shelf.size()) {
            LinearGradient g = tileShaders[i];
            tileShaders[i] = tileShaders[j];
            tileShaders[j] = g;
        }
        shelfIndex = liftIndex = j;
        reorderAnim = true;
        wake();
        retarget(false);
        click.move();
    }

    /** OK: drops the app where it is and saves the shelf's new order. */
    private void dropReorder() {
        if (!reordering) return;
        reordering = false;
        moveLift.target = 0;
        boolean changed = false;
        for (int i = 0; i < shelf.size() && !changed; i++) changed = shelf.get(i) != reorderOrigin.get(i);
        reorderOrigin = null;
        click.open();
        retarget(false);
        if (!changed) return;
        final List<String> order = new ArrayList<>();
        for (HomeApps.Item it : shelf) order.add(it.pkg);
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            // The saved list may hold more than the shelf shows (apps not installed, past the limit): the shown ones
            // take their new order in the same slots, the others stay where they were.
            List<String> all = HomeApps.shelfPackages(c), out = new ArrayList<>(all);
            int next = 0;
            for (int s = 0; s < out.size() && next < order.size(); s++) {
                if (order.contains(out.get(s))) out.set(s, order.get(next++));
            }
            if (next < order.size()) {
                out = new ArrayList<>(order);
                for (String p : all) if (!order.contains(p)) out.add(p);
            }
            final List<String> save = out;
            handler.post(() -> Prefs.setShelf(save));
        });
    }

    /** Back, or leaving the home screen: the original order comes back (sliding, unless `snap`). */
    private void cancelReorder(boolean snap) {
        if (!reordering && !reorderAnim) return;
        if (reordering) {
            reordering = false;
            moveLift.target = 0;
            int n = shelf.size();
            if (reorderOrigin != null && reorderOrigin.size() == n) {
                HomeApps.Item lifted = shelf.get(shelfIndex);
                Spring[] focus = new Spring[n], slide = new Spring[n];
                LinearGradient[] shaders = tileShaders.length == n ? new LinearGradient[n] : tileShaders;
                for (int a = 0; a < n; a++) {
                    int b = shelf.indexOf(reorderOrigin.get(a));
                    focus[a] = tileFocus[b];
                    slide[a] = tileSlide[b];
                    slide[a].value += tileRects[b].left - tileRects[a].left;
                    if (shaders != tileShaders) shaders[a] = tileShaders[b];
                }
                shelf = reorderOrigin;
                tileFocus = focus;
                tileSlide = slide;
                tileShaders = shaders;
                shelfIndex = liftIndex = shelf.indexOf(lifted);
            }
            reorderOrigin = null;
            retarget(snap);
        }
        if (snap) {
            moveLift.snap(0);
            for (Spring s : tileSlide) s.snap(0);
            wiggle = 0;
            liftIndex = -1;
            reorderAnim = false;
            tilesDirty = true;
        } else {
            wake();
        }
    }

    /** One frame of the move: the slides, the lift and the wiggle. Returns true while any of them moves. */
    private boolean stepReorder(float dt, long up) {
        boolean moving = step(tileSlide, dt) | moveLift.step(dt);
        if (reordering || moveLift.value > 0.001f) {
            wiggle = WIGGLE_DEG * (float) Math.sin((up - reorderAt) * (2 * Math.PI / WIGGLE_MS));
            moving = true;
        } else {
            wiggle = 0;
        }
        reorderAnim = moving;
        return moving;
    }

    // MARK: - TalkBack: every reachable item as a virtual node (Spoken). Id = group * 1000 + index + 1.

    /** The drawer's weather (0) and quotes (1) cards: read by TalkBack, never focused by the remote. */
    private static final int GRID = 9, WIDGETS = 8;

    private static int a11yId(int group, int index) {
        return group * 1000 + index + 1;
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return spoken;
    }

    private void a11yItems(List<Integer> out) {
        if (sheetOpen) {
            for (int i = 0; i < others.size(); i++) out.add(a11yId(GRID, i));
            return;
        }
        for (int i = picksOn() ? 0 : 3; i < 3 + lightButtons.size(); i++) out.add(a11yId(Z_TOP, i));
        if (TOP_SHELF) for (int i = 0; i < tabRects.length; i++) out.add(a11yId(Z_TABS, i));
        if (TOP_SHELF) for (int i = 0; i < actionCount(); i++) out.add(a11yId(Z_ACTIONS, i));
        for (int i = 0; i < (TOP_SHELF ? cards.size() : Math.min(cardRects.length, cards.size())); i++) out.add(a11yId(Z_CARDS, i));
        for (int i = shelfFirst; i < Math.min(shelf.size(), shelfFirst + shelfVisible); i++) out.add(a11yId(Z_SHELF, i));
        for (int i = 0; i < drawerItems.size(); i++) out.add(a11yId(Z_DRAWER, i));
        if (drawerOpen && Prefs.weather()) out.add(a11yId(WIDGETS, 0));
        if (drawerOpen && Prefs.quotes() && HomeData.active().length > 0) out.add(a11yId(WIDGETS, 1));
    }

    private String a11yText(int id) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        switch (group) {
            case GRID:
                if (i >= others.size()) return "";
                return label(others, i) + ", " + Ui.trf("{i} of {n}", "i", i + 1, "n", others.size());
            case Z_TOP:
                if (i >= 3) return label(lightButtons, i - 3);
                return Ui.trf("{name}, tab {i} of {n}", "name", tabs()[i], "i", i + 1, "n", 3);
            case Z_CARDS: return i < cards.size() ? pickText(cards.get(i)) : "";
            case Z_ACTIONS: return shelfPick == null || i >= actionCount() ? "" : actionText(i) + ", " + shelfPick.title;
            case Z_TABS: return i < tabRects.length ? Ui.trf("{name}, tab {i} of {n}", "name", shelfTabs()[i], "i", i + 1, "n", tabRects.length) : "";
            case Z_SHELF:
                if (reordering && i == shelfIndex) {
                    return Ui.trf("Moving {app}, position {n} of {total}",
                            "app", label(shelf, i), "n", i + 1, "total", shelf.size());
                }
                return label(shelf, i);
            case Z_DRAWER: return label(drawerItems, i);
            case WIDGETS: return i == 0 ? weatherText() : quotesText();
            default: return "";
        }
    }

    /**
     * Where the item is now. Items off screen (the closed drawer, the sheet's hidden rows, the top while the drawer is
     * open) get a thin strip at that edge, so TalkBack's arrows can still reach them; going there brings them in.
     */
    private boolean a11yBounds(int id, Rect out) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        float dy;
        switch (group) {
            case GRID: {
                if (!sheetOpen || i >= others.size()) return false;
                float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
                float x = ((1920 - gridWidth) / 2 + (i % COLUMNS) * (GRID_W + GRID_GAP)) * k;
                float y = (GRID_TOP + (i / COLUMNS - scroll.target) * GRID_ROW) * k;
                a11yRect.set(x, y, x + GRID_W * k, y + GRID_H * k);
                dy = 0;
                break;
            }
            case Z_TOP:
                if (i >= 3 + lightButtons.size()) return false;
                a11yRect.set(topRects[i]);
                dy = (i < 3 ? heroLayer : status).getTranslationY();
                break;
            case Z_CARDS:
                if (i >= Math.min(cardRects.length, cards.size())) return false;
                if (TOP_SHELF) {
                    if (stackIndex < 1) return false;
                    thumbRect(stackIndex, i, a11yRect);
                } else {
                    a11yRect.set(cardRects[i]);
                }
                dy = heroLayer.getTranslationY();
                break;
            case Z_ACTIONS:
                if (i >= actionCount()) return false;
                a11yRect.set(actionRects[i]);
                dy = heroLayer.getTranslationY();
                break;
            case Z_TABS:
                if (i >= tabRects.length) return false;
                a11yRect.set(tabRects[i]);
                dy = heroLayer.getTranslationY();
                break;
            case Z_SHELF:
                if (i >= tileRects.length) return false;
                a11yRect.set(tileRects[i]);
                dy = tiles.getTranslationY();
                break;
            case Z_DRAWER:
                if (i >= drawerRects.length) return false;
                a11yRect.set(drawerRects[i]);
                dy = drawerOpen ? 0 : h;
                break;
            case WIDGETS:
                if (!drawerOpen) return false;
                a11yRect.set(i == 0 ? weatherCard : quotesCard);
                dy = 0;
                break;
            default:
                return false;
        }
        a11yRect.offset(0, dy);
        float edge = 24 * k;
        if (a11yRect.top >= h - edge) a11yRect.set(a11yRect.left, h - edge, a11yRect.right, h);
        else if (a11yRect.bottom <= edge) a11yRect.set(a11yRect.left, 0, a11yRect.right, edge);
        a11yRect.round(out);
        return out.width() > 0 && out.height() > 0;
    }

    private int a11yFocused() {
        if (sheetOpen) return others.isEmpty() ? -1 : a11yId(GRID, gridIndex);
        switch (zone) {
            case Z_TOP: return a11yId(Z_TOP, topIndex);
            case Z_CARDS: return cardIndex < cards.size() ? a11yId(Z_CARDS, cardIndex) : -1;
            case Z_ACTIONS: return actionIndex < actionCount() ? a11yId(Z_ACTIONS, actionIndex) : -1;
            case Z_TABS: return a11yId(Z_TABS, tabIndex);
            case Z_SHELF: return shelf.isEmpty() ? -1 : a11yId(Z_SHELF, shelfIndex);
            case Z_DRAWER: return drawerItems.isEmpty() ? -1 : a11yId(Z_DRAWER, drawerIndex);
            case WIDGETS: return a11yId(WIDGETS, widgetIndex);
            default: return -1;
        }
    }

    /** The drawer's widgets that are on (0 weather, 1 quotes), with the drawer open. */
    private List<Integer> widgets() {
        List<Integer> out = new ArrayList<>();
        if (!drawerOpen) return out;
        if (Prefs.weather()) out.add(0);
        if (Prefs.quotes() && HomeData.active().length > 0) out.add(1);
        return out;
    }

    /** A spoken-feedback service (TalkBack) is on. */
    private boolean talkBack() {
        AccessibilityManager am = (AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        return am != null && am.isEnabled()
                && !am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isEmpty();
    }

    /** TalkBack moved to an item: the remote's focus goes there (opening or closing the drawer on the way). */
    private void a11yFocus(int id) {
        wake();
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        if (group == GRID) {
            if (!sheetOpen || i >= others.size()) return;
            gridIndex = i;
            retargetGrid(false);
            gridDirty = true;
            click.move();
            return;
        }
        if (sheetOpen || reordering) return;
        switch (group) {
            case Z_DRAWER:
                if (i >= drawerItems.size()) return;
                if (!drawerOpen) openDrawer();
                drawerIndex = i;
                break;
            case WIDGETS:
                if (!widgets().contains(i)) return;
                widgetIndex = i;
                break;
            case Z_TOP:
                if (i >= 3 + lightButtons.size() || (i < 3 && !picksOn())) return;
                if (drawerOpen) closeDrawer();
                topIndex = i;
                if (i < 3) selectTab(i);
                break;
            case Z_CARDS:
                if (i >= cards.size()) return;
                if (drawerOpen) closeDrawer();
                cardIndex = i;
                if (TOP_SHELF && focusedRow() != null) {
                    focusedRow().index = i;
                    focusRow(true);
                }
                break;
            case Z_ACTIONS:
                if (i >= actionCount()) return;
                if (drawerOpen) closeDrawer();
                actionIndex = i;
                break;
            case Z_TABS:
                if (i >= tabRects.length) return;
                if (drawerOpen) closeDrawer();
                tabIndex = i;
                selectShelfTab(i);
                break;
            case Z_SHELF:
                if (i >= shelf.size()) return;
                if (drawerOpen) closeDrawer();
                shelfIndex = i;
                break;
            default:
                return;
        }
        zone = group;
        retarget(false);
        click.move();
    }

    private static String label(List<HomeApps.Item> list, int i) {
        return i >= 0 && i < list.size() && list.get(i).label != null ? list.get(i).label : "";
    }

    /** A recommendation: title, details and the app that plays it. */
    private static String pickText(Recs.Pick p) {
        if (p == null) return "";
        StringBuilder s = new StringBuilder(p.title == null ? "" : p.title);
        if (p.meta != null && !p.meta.isEmpty()) s.append(", ").append(p.meta);
        String app = Recs.serviceName(p);
        if (app != null && !app.isEmpty()) s.append(". App: ").append(app);
        return s.toString();
    }

    /** The weather card as TalkBack reads it: what it shows, in the same words and units. */
    private String weatherText() {
        StringBuilder s = new StringBuilder(Prefs.city().isEmpty() ? Ui.tr("Weather") : Prefs.city());
        HomeData.Weather wx = data.weather;
        if (Double.isNaN(wx.temp)) return s.append(". ").append(Ui.tr("No weather data right now.")).toString();
        s.append(". ").append(HomeData.degrees(wx.temp)).append(", ").append(HomeData.condition(wx.code, wx.isDay)).append(". ")
                .append(Ui.trf("High {max}, low {min}.", "max", HomeData.degrees(wx.max), "min", HomeData.degrees(wx.min)));
        int hours = Math.min(6, wx.hours.size());
        if (hours > 0) {
            StringBuilder list = new StringBuilder();
            for (int i = 0; i < hours; i++) {
                if (i > 0) list.append(", ");
                list.append(HomeData.hour(wx.hours.get(i))).append(' ').append(HomeData.degrees(wx.hourTemp.get(i)));
            }
            s.append(' ').append(Ui.trf("Next hours: {list}.", "list", list));
        }
        if (wx.sunrise != 0) s.append(' ').append(Ui.trf("talkback|Sunrise {time}", "time", Ui.time(wx.sunrise))).append('.');
        if (wx.sunset != 0) s.append(' ').append(Ui.trf("Sunset {time}", "time", Ui.time(wx.sunset))).append('.');
        return s.toString();
    }

    /** The quotes card as TalkBack reads it: each coin with its price and 24 h change. */
    private String quotesText() {
        StringBuilder s = new StringBuilder(Ui.tr("Crypto"));
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        s.append(", ").append(stale.isEmpty() ? Ui.tr("24h change") : Ui.trf("quotes|{ago}", "ago", stale)).append('.');
        for (int i : HomeData.active()) {
            s.append(' ').append(HomeData.coinName(i)).append(' ').append(HomeData.brl(data.prices[i]));
            if (!Double.isNaN(data.changes[i])) s.append(", ").append(HomeData.change(data.changes[i]));
            s.append('.');
        }
        return s.toString();
    }

    // MARK: - Light design: title, tabs, cards and icon-only buttons

    private void layoutLight() {
        if (lTab == null) return;
        // Text tabs; the focus pill extends 26 px past the text (the first tab's text starts at the margin).
        float pad = 26 * k, tabTop = L_TABBAR_TOP * k, tabBottom = (L_TABBAR_TOP + L_TABBAR_H) * k;
        float x = MARGIN * k - pad;
        for (int i = 0; i < 3; i++) {
            float tw = Math.max(lTabOn.measureText(tabs()[i]), lTab.measureText(tabs()[i]));
            topRects[i].set(x, tabTop, x + tw + 2 * pad, tabBottom);
            snap(topRects[i]);
            x = topRects[i].right + 4 * k;
        }
        float b = L_BUTTON * k, right = CONTENT_R * k, cy = L_BUTTON_CY * k;
        for (int i = lightButtons.size() - 1; i >= 0; i--) {
            topRects[3 + i].set(right - b, cy - b / 2, right, cy + b / 2);
            right -= b + L_BUTTON_GAP * k;
        }
        float top = L_CARDS_TOP * k, ch = L_CARD_H * k, g = L_CARD_GAP * k, left = MARGIN * k, tall = L_TALL_W * k;
        float wide = (CONTENT_R - MARGIN) * k - 2 * tall - 2 * g;
        cardRects[0].set(left, top, left + wide, top + ch);
        cardRects[1].set(cardRects[0].right + g, top, cardRects[0].right + g + tall, top + ch);
        cardRects[2].set(cardRects[1].right + g, top, cardRects[1].right + g + tall, top + ch);
        for (RectF r : cardRects) snap(r);
        for (HomeApps.Item it : lightButtons) {
            Symbols.warm(getContext(), it.symbol, 27 * k);
            Symbols.warm(getContext(), it.symbol, 27 * k * 1.1f);
        }
        Symbols.warmWeather(getContext(), 40 * k);
        if (TOP_SHELF) layoutTopShelf();
        heroDirty = statusDirty = true;
    }

    /** The three cards of the current tab (Explore, Movies, Shows) and the background taken from the first one. */
    private void updateCards() {
        if (TOP_SHELF) {
            updateRows();
            return;
        }
        List<Recs.Pick> list = recs.now(TAB_KIND[tab]);
        if (!samePicks(list, cards)) {
            cards = list;
            cardIndex = Math.min(cardIndex, Math.max(0, list.size() - 1));
            if (zone == Z_CARDS && list.isEmpty()) {
                zone = Z_SHELF;
                retarget(false);
            }
        }
        for (Recs.Pick p : recs.wanted()) recs.backdrop(p);   // all three tabs preloaded: switching tabs is instant
        // Picks bring their own background; without them (no TMDB key), your photo if there is one.
        boolean own = !picksOn() && Photos.on(getContext());
        Backdrop b = own ? Photos.backdrop(getContext(), ownReady) : cards.isEmpty() ? null : recs.backdrop(cards.get(0));
        if (b != null || (cards.isEmpty() && !own)) showBackdrop(b);
        heroDirty = true;
        spoken.focusMoved();    // new picks under the focused card
    }

    /** Your photo's background is ready (or new photos came in): draw it. */
    private final Runnable ownReady = () -> {
        if (running) updatePicks();
    };

    private void selectTab(int index) {
        if (index == tab) return;
        tab = index;
        updateCards();
        heroDirty = true;
    }

    /**
     * Background: the first card's photo, heavily blurred and almost white; without it, a light gradient. At night, the
     * same photo almost black (or a dark gradient), drawn on top during the transition.
     */
    private void drawLightSky(Canvas c) {
        long up = SystemClock.uptimeMillis();
        float e = bgFadeAt == 0 ? 1 : fadeEase(bgFadeAt, up);
        boolean fading = bgFadeAt != 0;
        // Drawer open: the background lightens (by day) or darkens (at night), as a filter on the drawing itself.
        android.graphics.ColorFilter v = veil(tone(0.32f, 0.42f) * Ui.clamp(drawer.value), blend(0xFFFFFFFF, 0xFF000000, night));
        if (bgTo == null || (fading && bgFrom == null)) {
            if (lightGradient == null) lightGradient = Ui.gradient(h, 0xFFF5F5F7, 0xFFE2E2E7);
            if (nightGradient == null) nightGradient = Ui.gradient(h, 0xFF1C1C1E, 0xFF0B0B0D);
            fill.setColorFilter(v);
            fill.setColor(0xFF000000);
            if (night < 0.999f) {
                fill.setShader(lightGradient);
                c.drawRect(screen, fill);
            }
            if (night > 0.001f) {
                fill.setShader(nightGradient);
                fill.setAlpha(Math.round(255 * night));
                c.drawRect(screen, fill);
                fill.setAlpha(255);
            }
            fill.setShader(null);
            fill.setColorFilter(null);
        }
        imagePaint.setColorFilter(v);
        if (fading && bgFrom != null) drawLightImage(c, bgFrom, bgTo == null ? 1 - e : 1);
        if (bgTo != null) drawLightImage(c, bgTo, fading ? e : 1);
        imagePaint.setColorFilter(null);
    }

    private void drawLightImage(Canvas c, Backdrop b, float alpha) {
        if (night < 0.999f || b.nightImage == null) drawImage(c, b.lightImage, alpha);
        if (night > 0.001f) drawImage(c, b.nightImage, alpha * night);
    }

    private void drawLightTop(Canvas c) {
        applyTone();
        if (!picksOn()) {
            drawClock(c);
            if (HomeData.coinCount() > 0) drawQuotes(c);
            return;
        }
        String hello = Ui.tr("For You");
        c.drawText(hello, MARGIN * k - Ui.leftBearing(lTitle, hello), L_TITLE_BASE * k, lTitle);
        // Text tabs (the selected one in bold, in the main text color); with focus on the tabs, the selected one gets
        // the white pill with a shadow and grows a little (the tvOS focus).
        float focus = Ui.clamp(topFocus[tab].value);
        for (int i = 0; i < 3; i++) {
            RectF r = topRects[i];
            if (i == tab && focus > 0.01f) {
                float s = 1 + 0.05f * focus;
                cell.set(r.centerX() - r.width() * s / 2, r.centerY() - r.height() * s / 2,
                        r.centerX() + r.width() * s / 2, r.centerY() + r.height() * s / 2);
                float rr = cell.height() / 2;
                if (focus > 0.01f) {
                    tmp.set(cell);
                    tmp.offset(0, 8 * k * focus);
                    shadow.setColor(Ui.alpha(0xFF000000, tone(0.22f, 0.4f) * focus));
                    shadows.draw(c, tmp, rr, SHADOW_BLUR * k, shadow);
                }
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, focus));
                c.drawRoundRect(cell, rr, rr, fill);
            }
            Paint p = i == tab ? lTabOn : lTab;
            if (i == tab) lTabOn.setColor(blend(tone(L_INK, WHITE), L_INK, focus));   // always dark on the white pill
            c.drawText(tabs()[i], r.centerX() - p.measureText(tabs()[i]) / 2, r.centerY() + Ui.halfCap(p), p);
        }
        int top = -1;
        for (int i = 0; i < 3; i++) if (cardFocus[i].value > 0.02f && (top < 0 || cardFocus[i].value > cardFocus[top].value)) top = i;
        for (int i = 0; i < 3; i++) if (i != top) drawLightCard(c, i);
        if (top >= 0) drawLightCard(c, top);
        if (HomeData.coinCount() > 0) drawQuotes(c);
    }

    private boolean picksOn() {
        return Recs.hasKey(getContext());
    }

    private void drawClock(Canvas c) {
        float x = MARGIN * k;
        c.drawText(dateText, x - Ui.leftBearing(lDate, dateText), L_DATE_BASE * k, lDate);
        c.drawText(clockText, x - Ui.leftBearing(lClock, clockText), L_CLOCK_BASE * k, lClock);
        HomeData.Weather wx = data.weather;
        if (!Prefs.weather() || Double.isNaN(wx.temp)) return;
        if (data.weatherAt != weatherLineAt || Prefs.version != weatherLinePrefs || !Ui.language.equals(weatherLineLanguage)) {
            weatherLineAt = data.weatherAt;
            weatherLinePrefs = Prefs.version;
            weatherLineLanguage = Ui.language;
            weatherLine = HomeData.degrees(wx.temp) + "  " + HomeData.condition(wx.code, wx.isDay) + "   "
                    + HomeData.highLow(wx, "  ");
        }
        float size = 40 * k, base = L_WEATHER_BASE * k;
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(lWeather), size, lWeather.getColor());
        c.drawText(weatherLine, x + sw + 16 * k, base, lWeather);
    }

    /**
     * Recommendation card: the title's photo (already at the focused card size, so it stays sharp), rounded corners and
     * a soft shadow; when focused, it grows 5%, rises and lights up its border. Below it, the title (and on the large
     * card, genre · runtime · service).
     */
    private void drawLightCard(Canvas c, int i) {
        RectF base = cardRects[i];
        Recs.Pick p = i < cards.size() ? cards.get(i) : null;
        float v = cardFocus[i].value, f = Ui.clamp(v), s = 1 + 0.05f * v;
        float bw = base.width(), bh = base.height(), r0 = 18 * k;
        float cx = base.centerX(), cy = base.centerY() - 6 * k * v;
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (10 + 14 * f) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.32f) + tone(0.2f, 0.28f) * f));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        Backdrop b = p == null ? null : recs.backdrop(p);
        Bitmap img = b == null ? null : (i == 0 ? b.wide : b.tall);
        if (img != null) {
            roundImage(c, img, i == 0 ? b.wideShader : b.tallShader, cell, r);
        } else {
            fill.setShader(null);
            fill.setColor(tone(0xFFDADADF, 0xFF2C2C2E));
            c.drawRoundRect(cell, r, r, fill);
        }
        if (f > 0.01f) {
            if (lightSheens.length != cardRects.length) {
                lightSheens = new LinearGradient[cardRects.length];
                lightRims = new LinearGradient[cardRects.length];
                lightShaderW = new float[cardRects.length];
            }
            if (lightSheens[i] == null || lightShaderW[i] != bw) {
                lightSheens[i] = new LinearGradient(-bw / 2, -bh / 2, 0, bh / 2, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                lightRims[i] = new LinearGradient(0, -bh / 2, 0, bh / 2, 0xE6FFFFFF, 0x59FFFFFF, Shader.TileMode.CLAMP);
                lightShaderW[i] = bw;
            }
            int alpha = Math.round(255 * f);
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            sheen.setShader(lightSheens[i]);
            sheen.setAlpha(alpha);
            c.drawRoundRect(tmp, r0, r0, sheen);
            sheen.setAlpha(255);
            rim.setShader(lightRims[i]);
            rim.setAlpha(alpha);
            rim.setStrokeWidth(2.5f * k / s);
            c.drawRoundRect(tmp, r0, r0, rim);
            rim.setStrokeWidth(2.5f * k);
            rim.setAlpha(255);
            c.restore();
        }
        if (p == null) return;
        fitPaint.set(lCardTitle);
        c.drawText(TextUtils.ellipsize(p.title, fitPaint, bw, TextUtils.TruncateAt.END).toString(), base.left, base.bottom + 46 * k, lCardTitle);
        if (i == 0 && p.meta != null && !p.meta.isEmpty()) {
            fitPaint.set(lCardMeta);
            c.drawText(TextUtils.ellipsize(p.meta, fitPaint, bw, TextUtils.TruncateAt.END).toString(), base.left, base.bottom + 80 * k, lCardMeta);
        }
    }

    /**
     * The round buttons (Live TV, HDMI Inputs and Settings by default): icon-only glass circles (light by day, smoked at
     * night); the focused one turns white with a dark icon (the tvOS focus) and its name appears below it.
     */
    private void drawLightButtons(Canvas c) {
        applyTone();
        for (int i = 0; i < lightButtons.size(); i++) {
            HomeApps.Item it = lightButtons.get(i);
            RectF r = topRects[3 + i];
            float f = Ui.clamp(topFocus[3 + i].value), s = 1 + 0.1f * f, rad = r.width() / 2 * s;
            cell.set(r.centerX() - rad, r.centerY() - rad, r.centerX() + rad, r.centerY() + rad);
            tmp.set(cell);
            tmp.offset(0, (6 + 8 * f) * k);
            shadow.setColor(Ui.alpha(0xFF000000, tone(0.1f, 0.3f) + tone(0.14f, 0.2f) * f));
            shadows.draw(c, tmp, rad, SHADOW_BLUR * k, shadow);
            scene.liquid(c, cell, rad, tone(0xFFFFFFFF, SMOKE), tone(0.55f, 0.5f), tone(0.02f, 0.01f), 12 * k, 8 * k,
                    tone(0.6f, 0.45f), 1 - f);
            if (f > 0.01f) {
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, f));
                c.drawCircle(cell.centerX(), cell.centerY(), rad, fill);
            }
            Symbols.draw(getContext(), c, it.symbol, cell.centerX(), cell.centerY(), 27 * k * s,
                    blend(tone(L_INK, 0xEBFFFFFF), L_INK, f));
            if (f > 0.01f) {
                String label = it.label;
                float half = lTip.measureText(label) / 2;
                float x = Math.min(r.centerX(), CONTENT_R * k - half);
                lTip.setAlpha(Math.round(255 * f));
                c.drawText(label, x, r.bottom + (40 + 4 * f) * k, lTip);
            }
        }
        if (sleepText.isEmpty()) return;
        float right = (lightButtons.isEmpty() ? CONTENT_R * k : topRects[3].left) - 28 * k, cy = L_BUTTON_CY * k;
        float tw = lQuote.measureText(sleepText), size = lQuote.getTextSize();
        c.drawText(sleepText, right - tw, cy + Ui.halfCap(lQuote), lQuote);
        Symbols.draw(getContext(), c, "bedtime", right - tw - size * 0.9f, cy, size, lQuote.getColor());
    }

    // MARK: - Top Shelf
    //
    // The screen, top to bottom (1920 × 1080 layout): the top bar (EndGrid iQTV+, the glass navigation, your initial and
    // the time); the hero (label, the title's logo art or name, year • language • genre • length, two lines of
    // description, Watch Now / More Info / Not for me, the carousel's dots); Your Apps (the dock); the rows. The
    // focused title's photo fills the screen behind it all (drawShelfSky). Going down into the rows, the focused row
    // stays where the first one sits and the hero fades out above it, as on tvOS.

    private static final float H_BAR_CY = 92, H_NAV_X = 250, H_NAV_H = 72, H_NAV_ITEM_H = 58;   // the bar just after "iQTV+"
    private static final float H_LABEL_BASE = 296, H_TITLE_BOTTOM = 452, H_LOGO_W = 760, H_LOGO_H = 150, H_TITLE_W = 1000;
    private static final float H_META_BASE = 504, H_OVERVIEW_BASE = 548, H_BUTTONS_TOP = 608, H_BUTTON_H = 66, H_DOTS_CY = 714;
    private static final float H_ROWS_TOP = 762, H_CARD_DY = 46, H_CARD_W = 320, H_CARD_H = 180, H_POSTER_W = 190,
            H_POSTER_H = 285, H_CARD_GAP = 30, H_TEXT_H = 76, H_ROW_GAP = 40, H_ROW_FADE_TOP = 150, H_ROW_FADE = 140;
    private static final long HERO_ROTATE_MS = 9000;
    private static final int HERO_FEATURED = 8;

    /** The navigation's items: three filters of the rows, then Apps (All Apps) and Search (Settings: your initial). */
    private static String[] shelfTabs() {
        return new String[] {Ui.tr("tab|Home"), Ui.tr("Movies"), Ui.tr("TV Shows"), Ui.tr("Apps"), Ui.tr("Search")};
    }

    private static final String[] TAB_SYMBOLS = {"home", "play", "tv", "apps", "search"};

    private void layoutTopShelf() {
        if (sTab != null) {
            // The items inside the glass bar: icon and name, sized to the name.
            float x = H_NAV_X * k + (H_NAV_H - H_NAV_ITEM_H) / 2 * k, cy = H_BAR_CY * k, ih = H_NAV_ITEM_H * k;
            String[] names = shelfTabs();
            for (int i = 0; i < names.length; i++) {
                float tw = Math.max(sTabOn.measureText(names[i]), sTab.measureText(names[i]));
                tabRects[i].set(x, cy - ih / 2, x + 26 * k + 26 * k + 10 * k + tw + 26 * k, cy + ih / 2);
                snap(tabRects[i]);
                x = tabRects[i].right + 4 * k;
            }
        }
        layoutActions();
        for (String s : TAB_SYMBOLS) Symbols.warm(getContext(), s, 24 * k);
        for (String s : new String[] {"play", "not_interested", "apps"}) Symbols.warm(getContext(), s, 26 * k);
    }

    // MARK: Hero actions

    private static final int ACT_PLAY = 0, ACT_INFO = 1, ACT_HIDE = 2;

    /** The title's buttons: open it (needs its app on the TV), More Info, Not for me (not for what you watched). */
    private List<Integer> actions() {
        List<Integer> out = new ArrayList<>();
        if (shelfPick == null) return out;
        if (shelfPick.pkg != null) out.add(ACT_PLAY);
        out.add(ACT_INFO);
        if (!"watched".equals(shelfPick.kind) && searchResults == null) out.add(ACT_HIDE);
        return out;
    }

    private int actionCount() {
        return actions().size();
    }

    /** Sized to their words. */
    private void layoutActions() {
        if (sAction == null) return;
        float x = MARGIN * k, top = H_BUTTONS_TOP * k, hgt = H_BUTTON_H * k, pad = 32 * k, icon = 26 * k + 14 * k;
        for (int i = 0; i < actionRects.length; i++) {
            float tw = i < actionCount() ? sAction.measureText(actionText(i)) : 0;
            actionRects[i].set(x, top, x + pad + icon + tw + pad, top + hgt);
            snap(actionRects[i]);
            x = actionRects[i].right + 22 * k;
        }
    }

    /**
     * What the button says: "Watch Now" only where the title's own page opens (a Watchmode link), otherwise "Open
     * Netflix": the app opens at its home, and the button says so (Resume when the app reported you started it).
     */
    private String actionText(int i) {
        List<Integer> acts = actions();
        int a = i < acts.size() ? acts.get(i) : -1;
        if (a == ACT_INFO) return Ui.tr("More Info");
        if (a == ACT_HIDE) return Ui.tr("Not for me");
        if (shelfPick == null) return "";
        if (shelfPick.progress > 0) return Ui.tr("Resume");
        if (shelfPick.link != null) return Ui.tr("Watch Now");
        String app = Recs.serviceName(shelfPick);
        return app == null || app.isEmpty() ? Ui.tr("Open App") : Ui.trf("Open {app}", "app", app);
    }

    private String actionSymbol(int i) {
        List<Integer> acts = actions();
        int a = i < acts.size() ? acts.get(i) : -1;
        return a == ACT_INFO ? "apps" : a == ACT_HIDE ? "not_interested" : "play";
    }

    // MARK: The stack: Your Apps, then the rows (Continue Watching first)

    /** Popular and Latest show posters (2:3), as the tvOS rows of a kind do; the others landscape art (16:9). */
    private static boolean posterRow(Row row) {
        return row.id.equals("popular") || row.id.equals("latest");
    }

    private float cardW(Row row) { return (posterRow(row) ? H_POSTER_W : H_CARD_W) * k; }
    private float cardH(Row row) { return (posterRow(row) ? H_POSTER_H : H_CARD_H) * k; }

    /** How tall a row is with its label, its cards and their two lines of text. */
    private float rowPitch(Row row) {
        return (H_CARD_DY + H_TEXT_H + H_ROW_GAP) * k + cardH(row);
    }

    /** How many rows come before Your Apps: none, so the apps are always on Home's first screen, one press down. */
    private int dockAfter() {
        return 0;
    }

    /** How tall Your Apps is with its label, its tiles and their names. */
    private float dockPitch() {
        return shelf.isEmpty() ? 0 : (H_CARD_DY + 60 + H_ROW_GAP) * k + tileRects[0].height();
    }

    /** Top (label line) of row `i` (1-based) before scrolling; the rows after Your Apps start below it. */
    private float stackTop(int i) {
        float y = H_ROWS_TOP * k;
        for (int r = 1; r < i && r <= rows.size(); r++) y += rowPitch(rows.get(r - 1));
        return i > dockAfter() ? y + dockPitch() : y;
    }

    private float dockTop() {
        float y = H_ROWS_TOP * k;
        for (int r = 1; r <= dockAfter(); r++) y += rowPitch(rows.get(r - 1));
        return y;
    }

    /**
     * Where the stack scrolls to: the focused row (or Your Apps) up to where the first row rests, or higher when it's
     * a tall row (posters) that wouldn't fit with its titles under it.
     */
    private float stackTarget() {
        if (infoOpen) return stack.target;
        if (zone == Z_CARDS && stackIndex >= 1) {
            float fits = h - rowPitch(rows.get(stackIndex - 1)) + H_ROW_GAP * k - 24 * k;
            return stackTop(stackIndex) - Math.min(H_ROWS_TOP * k, fits);
        }
        if (zone == Z_SHELF || zone == Z_DRAWER) return dockTop() - H_ROWS_TOP * k;
        return 0;
    }

    /** How visible something whose top is at `y` is: it fades out as it scrolls up under the top bar. */
    private float stackAlpha(float y) {
        return Ui.clamp((y - H_ROW_FADE_TOP * k) / (H_ROW_FADE * k));
    }

    /** How visible the hero's text and buttons are: they fade as the rows scroll up over them. */
    private float heroAlpha() {
        return 1 - Ui.clamp(stack.value / (220 * k));
    }

    /** Your Apps (the dock layer) where the stack has it now: {translationY from its resting place, alpha}. */
    private void dockMotion(float[] out) {
        float top = dockTop() - stack.value;
        out[0] = top + H_CARD_DY * k - S_DOCK_TOP * k;
        out[1] = stackAlpha(top);
    }

    private Row focusedRow() {
        return stackIndex >= 1 && stackIndex <= rows.size() ? rows.get(stackIndex - 1) : null;
    }

    /** Card `i` of row `r` (1-based) where it is now, into `out`. */
    private RectF thumbRect(int r, int i, RectF out) {
        Row row = rows.get(r - 1);
        float cw = cardW(row);
        float x = MARGIN * k + i * (cw + H_CARD_GAP * k) - row.scroll.value;
        float y = stackTop(r) + H_CARD_DY * k - stack.value;
        out.set(x, y, x + cw, y + cardH(row));
        return out;
    }

    /** Scrolls a row so its focused card shows, keeping one card of context at the left (as tvOS rows do). */
    private void revealRow(Row row, boolean snap) {
        float step = cardW(row) + H_CARD_GAP * k;
        int fits = Math.max(1, (int) ((CONTENT_R - MARGIN) * k / step));
        int first = row.first;
        if (row.index < first + 1) first = Math.max(0, row.index - 1);
        if (row.index > first + fits - 2) first = Math.max(0, row.index - fits + 2);
        first = Math.max(0, Math.min(first, Math.max(0, row.items.size() - fits)));
        row.first = first;
        row.scroll.target = first * step;
        if (snap) row.scroll.snap(row.scroll.target);
    }

    /** The springs and scrolls for the focus (from retarget). */
    private boolean retargetStack(boolean snap) {
        boolean changed = false;
        for (int r = 0; r < rows.size(); r++) {
            Row row = rows.get(r);
            changed |= aim(row.focus, !sheetOpen && !infoOpen && zone == Z_CARDS && stackIndex == r + 1 ? row.index : -1, snap);
            revealRow(row, snap);
        }
        float target = stackTarget();
        changed |= stack.target != target;
        stack.target = target;
        if (snap) stack.snap(target);
        motionDirty = true;     // Your Apps moves with the stack (updateMotion)
        return changed;
    }

    private boolean stepStack(float dt) {
        boolean moving = stack.step(dt);
        if (moving) motionDirty = true;
        for (Row row : rows) moving |= step(row.focus, dt) | row.scroll.step(dt);
        return moving;
    }

    /** A row as the navigation's filter shows it, or null: Movies and TV Shows keep only those (an empty row goes). */
    private Recs.Section forTab(Recs.Section s) {
        if (shelfTab != TAB_MOVIES && shelfTab != TAB_SHOWS) return s;
        String kind = shelfTab == TAB_MOVIES ? "movie" : "tv";
        List<Recs.Pick> items = new ArrayList<>();
        for (Recs.Pick p : s.items) if (kind.equals(p.kind)) items.add(p);
        return items.isEmpty() ? null : new Recs.Section(s.id, s.title, items);
    }

    /** Home, Movies or TV Shows was chosen: its rows, from the top. */
    private void selectShelfTab(int t) {
        if (t == shelfTab || t > TAB_SHOWS) return;
        shelfTab = t;
        rows.clear();       // other rows: none keeps its place
        stackIndex = 0;
        heroIndex = 0;
        stack.snap(0);
        updateRows();
    }

    /** The rows from Recs (and a search's results first); each keeps its focus on the same title when they change. */
    private void updateRows() {
        List<Recs.Section> sections = new ArrayList<>();
        if (searchResults != null) sections.add(new Recs.Section("search", searchResults.isEmpty()
                ? Ui.trf("Nothing found for “{query}”", "query", searchQuery) : searchByGemini ? Ui.trf("Gemini picks for “{query}”", "query", searchQuery)
                : Ui.trf("Results for “{query}”", "query", searchQuery), searchResults));
        for (Recs.Section s : recs.sections()) {
            Recs.Section shown = forTab(s);
            if (shown != null) sections.add(shown);
        }
        String focusedRow = focusedRow() == null ? null : focusedRow().id;
        Map<String, Row> old = new HashMap<>();
        for (Row r : rows) old.put(r.id, r);
        List<Row> next = new ArrayList<>();
        for (Recs.Section s : sections) {
            if (s.items.isEmpty() && !s.id.equals("search")) continue;
            Row r = old.get(s.id);
            if (r == null || !r.title.equals(s.title)) {
                r = new Row(s.id, s.title, s.items);
            } else if (!samePicks(r.items, s.items)) {
                String at = r.index < r.items.size() ? Recs.key(r.items.get(r.index)) : null;
                r.setItems(s.items);
                for (int i = 0; at != null && i < s.items.size(); i++) if (Recs.key(s.items.get(i)).equals(at)) r.index = i;
            } else {
                r.items = s.items;   // the same titles: their details may be newer
            }
            next.add(r);
        }
        rows.clear();
        rows.addAll(next);
        // The hero's titles: from every row, on or off in Settings (Movies and TV Shows keep only theirs).
        heroSources.clear();
        for (Recs.Section s : recs.highlights()) {
            Recs.Section shown = forTab(s);
            if (shown != null && !shown.items.isEmpty()) heroSources.add(shown);
        }
        featuredNow = makeFeatured();
        if (heroIndex >= featuredNow.size()) heroIndex = 0;
        if (focusedRow != null) {
            int was = stackIndex;
            stackIndex = 0;
            for (int i = 0; i < rows.size(); i++) if (rows.get(i).id.equals(focusedRow)) stackIndex = i + 1;
            if (stackIndex == 0 && zone == Z_CARDS) stackIndex = Math.max(1, Math.min(was, rows.size()));
        }
        stackIndex = Math.min(stackIndex, rows.size());
        if (zone == Z_CARDS && stackIndex == 0) zone = rows.isEmpty() ? Z_SHELF : Z_CARDS;
        if (zone == Z_CARDS && stackIndex == 0) stackIndex = 1;
        syncCards();
        // The title on screen: the focused card's in the rows; at the top, the featured title the carousel is on.
        Row row = zone == Z_CARDS ? focusedRow() : null;
        Recs.Pick p = row != null && !row.items.isEmpty() ? row.items.get(row.index) : featuredPick();
        setShelfPick(p, false);
        // Started before the titles had loaded (no buttons yet: the focus went to the navigation): once the first title
        // is there, and until a key is pressed, the focus goes to its main button, as Home does.
        if (untouched && zone == Z_TABS && actionCount() > 0) {
            zone = Z_ACTIONS;
            actionIndex = 0;
        }
        updateShelfImage();
        preloadRows();
        retarget(false);
        heroDirty = true;
        spoken.focusMoved();
    }

    // MARK: The featured carousel (the hero while the focus is at the top)

    private int heroIndex;
    /** No key pressed since the home screen started or Home was pressed (updateRows may still place the focus). */
    private boolean untouched = true;
    /** Every row for the chosen tab, including the ones turned off in Settings (updateRows): the carousel's source. */
    private final List<Recs.Section> heroSources = new ArrayList<>();

    /** The carousel's titles for the chosen tab (makeFeatured, from updateRows). */
    private List<Recs.Pick> featuredNow = new ArrayList<>();

    private List<Recs.Pick> featured() {
        return featuredNow;
    }

    /** Streaming services first in the carousel unless Settings > Streaming Apps gives an order of your own. */
    private static final String[] HERO_FIRST = {"prime", "zee5"};

    /**
     * The titles the carousel goes through, from every row (on or off), never what you already watched: the services
     * take turns (your order in Settings > Streaming Apps, else Prime Video and ZEE5 first, then the rest), so one
     * app's list doesn't fill it; on Home a movie and a show alternate (Movies and TV Shows have only theirs). The most
     * personal rows come first within each service: yours, your languages', then the worldwide lists.
     */
    private List<Recs.Pick> makeFeatured() {
        // Candidates, by service, in the rows' order of preference.
        Map<String, List<Recs.Pick>> byService = new java.util.LinkedHashMap<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<Recs.Section> ordered = new ArrayList<>();
        for (String id : new String[] {"featured", "popular", "latest", "web", "shows", "trending", "new"}) {
            for (Recs.Section s : heroSources) if (s.id.equals(id)) ordered.add(s);
        }
        for (Recs.Section s : heroSources) {
            if (!ordered.contains(s) && !s.id.equals("continue") && !s.id.equals("recent") && !s.id.equals("search")) ordered.add(s);
        }
        for (Recs.Section s : ordered) {
            for (Recs.Pick p : s.items) {
                if (p.backdrop == null || !seen.add(Recs.key(p))) continue;
                String svc = p.app == null ? "" : p.app;
                List<Recs.Pick> list = byService.get(svc);
                if (list == null) byService.put(svc, list = new ArrayList<>());
                list.add(p);
            }
        }
        List<String> services = new ArrayList<>();
        List<String> order = Prefs.serviceOrder();
        for (String svc : order.isEmpty() ? java.util.Arrays.asList(HERO_FIRST) : order) if (byService.containsKey(svc)) services.add(svc);
        for (String svc : byService.keySet()) if (!services.contains(svc)) services.add(svc);
        // Round after round, one title per service; on Home, the other kind than the last one when the service has it.
        List<Recs.Pick> out = new ArrayList<>();
        boolean mix = shelfTab == TAB_HOME;
        String last = null;
        boolean any = true;
        while (out.size() < HERO_FEATURED && any) {
            any = false;
            for (String svc : services) {
                if (out.size() >= HERO_FEATURED) break;
                List<Recs.Pick> list = byService.get(svc);
                if (list.isEmpty()) continue;
                int at = 0;
                if (mix && last != null) {
                    for (int i = 0; i < list.size(); i++) if (!last.equals(list.get(i).kind)) { at = i; break; }
                }
                Recs.Pick p = list.remove(at);
                out.add(p);
                last = p.kind;
                any = true;
            }
        }
        return out;
    }

    private Recs.Pick featuredPick() {
        List<Recs.Pick> f = featured();
        if (f.isEmpty()) return rows.isEmpty() || rows.get(0).items.isEmpty() ? null : rows.get(0).items.get(0);
        return f.get(Math.min(heroIndex, f.size() - 1));
    }

    /** Every few seconds, while the focus is at the top and nothing is open, the next featured title cross-fades in. */
    private final Runnable rotateHero = new Runnable() {
        @Override public void run() {
            if (!running) return;
            handler.postDelayed(this, HERO_ROTATE_MS);
            List<Recs.Pick> f = featured();
            boolean atTop = zone == Z_ACTIONS || zone == Z_TABS;
            if (!TOP_SHELF || !atTop || infoOpen || weatherOpen || sheetOpen || drawerOpen || f.size() < 2 || reordering) return;
            heroIndex = (heroIndex + 1) % f.size();
            setShelfPick(f.get(heroIndex), true);
            updateShelfImage();
            for (int i = 1; i <= 2; i++) recs.full(f.get((heroIndex + i) % f.size()));   // the next ones ready
            wake();
        }
    };

    /**
     * ←/→ past the hero's buttons: the previous or next featured title, at once. The carousel's timer starts over, so
     * it doesn't move on right after a press.
     */
    private boolean stepHero(int dir) {
        List<Recs.Pick> f = featured();
        if (f.size() < 2) return true;
        heroIndex = ((heroIndex + dir) % f.size() + f.size()) % f.size();
        setShelfPick(f.get(heroIndex), true);
        updateShelfImage();
        recs.full(f.get((heroIndex + dir + f.size()) % f.size()));   // the one after, ready
        handler.removeCallbacks(rotateHero);
        handler.postDelayed(rotateHero, HERO_ROTATE_MS);
        wake();
        return true;
    }

    /** Back to the top of the screen: the hero shows the featured title again. */
    private void showFeatured() {
        setShelfPick(featuredPick(), true);
        handler.removeCallbacks(settleShelf);
        handler.postDelayed(settleShelf, SHELF_SETTLE_MS);
    }

    private boolean inRows(Recs.Pick p) {
        String k = Recs.key(p);
        for (Row r : rows) for (Recs.Pick q : r.items) if (Recs.key(q).equals(k)) return true;
        return false;
    }

    /** `cards` and `cardIndex` follow the focused row (activate and TalkBack read them). */
    private void syncCards() {
        Row row = focusedRow();
        cards = row == null ? new ArrayList<>() : row.items;
        cardIndex = row == null ? 0 : row.index;
    }

    /** Pictures for the rows on screen and the next two, ahead of the focus (decoded off the UI thread). */
    private void preloadRows() {
        int from = Math.max(1, stackIndex), to = Math.min(rows.size(), Math.max(stackIndex, 1) + 2);
        for (int r = from; r <= to; r++) {
            Row row = rows.get(r - 1);
            for (int i = row.first; i < Math.min(row.items.size(), row.first + 8); i++) {
                Recs.Pick p = row.items.get(i);
                if (posterRow(row)) recs.art(p.poster, Math.round(H_POSTER_W * k), Math.round(H_POSTER_H * k), true);
                else recs.backdrop(p);
            }
        }
    }

    // MARK: The title on screen

    /** The title the screen shows; its text fades in when it changes. */
    private void setShelfPick(Recs.Pick p, boolean animate) {
        String key = p == null ? "" : Recs.key(p) + "|" + p.title + "|" + p.meta + "|" + p.pkg + "|" + (p.progress > 0)
                + "|" + p.overview.length() + "|" + (p.link != null) + "|" + p.logo;
        shelfPick = p;
        if (key.equals(shelfTitleKey)) return;
        boolean other = !key.split("\\|", 2)[0].equals(shelfTitleKey.split("\\|", 2)[0]);
        shelfTitleKey = key;
        shelfTitle = null;
        shelfOverview = null;
        if (sTitle != null && p != null && p.title != null) {
            // One line, as tvOS sets a title without logo art: a long one (a live stream's) ends in "…", never
            // climbing over the label above it.
            shelfTitle = StaticLayout.Builder.obtain(p.title, 0, p.title.length(), sTitle, Math.round(H_TITLE_W * k))
                    .setMaxLines(1).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).build();
            // Two lines of TMDB's description, when it has one (nothing is made up when it hasn't).
            if (!p.overview.isEmpty()) {
                shelfOverview = StaticLayout.Builder.obtain(p.overview, 0, p.overview.length(), sOverview, Math.round(880 * k))
                        .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).build();
            }
        }
        if (p != null) recs.art(p.logo, Math.round(H_LOGO_W * k), Math.round(H_LOGO_H * k), false);
        layoutActions();
        if (actionIndex >= actionCount()) actionIndex = Math.max(0, actionCount() - 1);
        if (zone == Z_ACTIONS && actionCount() == 0) zone = Z_TABS;
        if (animate && other) shelfTextAt = SystemClock.uptimeMillis();
        heroDirty = true;
    }

    private boolean tickShelfText(long up) {
        if (shelfTextAt == 0) return false;
        if (up - shelfTextAt >= SHELF_TEXT_MS) shelfTextAt = 0;
        return true;
    }

    /** The focused title's photo and its glass (built in the background from the saved image if needed). */
    private void updateShelfImage() {
        Recs.Pick p = shelfPick;
        // A title without a picture (something you watched that TMDB couldn't match) keeps the photo behind it.
        if (p != null && p.backdrop == null) return;
        Bitmap b = p == null ? null : recs.full(p);
        if (p == null || b != null) setShelfImage(b);
        Backdrop g = p == null ? null : recs.glass(p);
        if (p == null || g != null) showBackdrop(g);
    }

    private void setShelfImage(Bitmap b) {
        if (b == shelfImage) return;
        long up = SystemClock.uptimeMillis();
        // In the middle of a fade, start from whichever is more visible.
        if (shelfFadeAt == 0 || fadeEase(shelfFadeAt, up, SHELF_FADE_MS) >= 0.5f) shelfImageFrom = shelfImage;
        shelfImage = b;
        shelfFadeAt = up;
        sky.invalidate();
        wake();
    }

    private boolean tickShelfFade(long up) {
        if (shelfFadeAt == 0) return false;
        if (fadeEase(shelfFadeAt, up, SHELF_FADE_MS) >= 1) {
            shelfFadeAt = 0;
            shelfImageFrom = null;
        }
        return true;
    }

    /**
     * The background: the focused title's photo (opaque, its scrims inside), cross-fading to the next. Only while no
     * photo is ready, a flat near-black (one clear; a dithered full-screen gradient is a shader on every pixel). With
     * the drawer open, darker (a filter on the same draw: no extra pass).
     */
    private void drawShelfSky(Canvas c) {
        long up = SystemClock.uptimeMillis();
        float e = shelfFadeAt == 0 ? 1 : fadeEase(shelfFadeAt, up, SHELF_FADE_MS);
        android.graphics.ColorFilter v = veil(0.45f * Ui.clamp(drawer.value), 0xFF000000);
        boolean covered = shelfImage != null && (e >= 1 || shelfImageFrom != null);
        if (!covered) c.drawColor(blend(0xFF141416, 0xFF000000, 0.45f * Ui.clamp(drawer.value)));
        imagePaint.setColorFilter(v);
        if (shelfImageFrom != null && e < 1) drawImage(c, shelfImageFrom, 1);
        if (shelfImage != null) drawImage(c, shelfImage, e);
        imagePaint.setColorFilter(null);
    }

    // MARK: Drawing

    /** Everything but the photo and Your Apps: the top bar, the hero, the rows, Your Apps' label, More Info. */
    private void drawTopShelf(Canvas c) {
        applyTone();
        if (!picksOn()) {
            drawClock(c);
            return;
        }
        drawTopBar(c);
        long up = SystemClock.uptimeMillis();
        float a = (shelfTextAt == 0 ? 1 : smoothstep(0, 1, (up - shelfTextAt) / (float) SHELF_TEXT_MS)) * heroAlpha();
        if (shelfPick != null && a > 0.01f) drawHero(c, shelfPick, a, (1 - a) * 14 * k);
        if (rows.isEmpty() && Recs.anyRowOn()) {   // with the rows turned off, nothing to explain
            sRowLabel.setAlpha(0xCC);
            c.drawText(Ui.tr("Nothing here yet: the picks load from TMDB in the background."), MARGIN * k, stackTop(1) + 30 * k, sRowLabel);
        }
        // The rows that show: the label, the cards (the focused one last: it grows over its neighbors).
        for (int r = 1; r <= rows.size(); r++) {
            Row row = rows.get(r - 1);
            float top = stackTop(r) - stack.value;
            if (top > h || top + rowPitch(row) < H_ROW_FADE_TOP * k) continue;
            float alpha = stackAlpha(top);
            if (alpha <= 0.01f) continue;
            sRowLabel.setAlpha(Math.round(0xF2 * alpha));
            c.drawText(row.title, MARGIN * k, top + 30 * k, sRowLabel);
            int most = -1;
            for (int i = 0; i < row.items.size(); i++) if (row.focus[i].value > 0.02f && (most < 0 || row.focus[i].value > row.focus[most].value)) most = i;
            for (int i = 0; i < row.items.size(); i++) if (i != most) drawCard(c, r, i, alpha);
            if (most >= 0) drawCard(c, r, most, alpha);
        }
        // Your Apps: its label here; the apps themselves are the dock layer, moved to sit under it (updateMotion).
        float dock = dockTop() - stack.value;
        if (!shelf.isEmpty() && dock < h && !dockGlass()) {   // in its glass tray the dock needs no label
            sRowLabel.setAlpha(Math.round(0xF2 * stackAlpha(dock)));
            c.drawText(Ui.tr("Your Apps"), MARGIN * k, dock + 30 * k, sRowLabel);
        }
        if (info.value > 0.01f) {
            if (overlaySearch) searchPanel().draw(c, w, h, k, Ui.clamp(info.value), searchFocus, searchTyping ? searchTyped.toString() : searchText,
                    searchTyping, searchState, searchMessage, (SystemClock.uptimeMillis() - searchOpenedAt) / 1000f);
            else if (overlayWeather) weatherPanel().drawPanel(c, data.weather, data.weatherAt, w, h, k, Ui.clamp(info.value));
            else drawInfo(c, Ui.clamp(info.value));
        }
    }

    /**
     * The top bar: iQTV+ at the left; the navigation in a translucent glass bar, the chosen filter white, the
     * focused item white and raised; your initial and the time at the right.
     */
    private void drawTopBar(Canvas c) {
        float cy = H_BAR_CY * k;
        // The wordmark: "iQ" in the brand's cyan to violet, "TV+" in white.
        float x = MARGIN * k;
        if (brandShader == null || brandShaderX != x) {
            brandShaderX = x;
            brandShader = new LinearGradient(x, 0, x + sBrand.measureText("iQ"), 0, Brand.CYAN, Brand.VIOLET, Shader.TileMode.CLAMP);
        }
        sBrandAccent.setShader(brandShader);
        c.drawText("iQ", x, cy + Ui.halfCap(sBrandAccent), sBrandAccent);
        c.drawText("TV+", x + sBrandAccent.measureText("iQ"), cy + Ui.halfCap(sBrand), sBrand);
        // The glass bar around the items.
        String[] names = shelfTabs();
        float pad = (H_NAV_H - H_NAV_ITEM_H) / 2 * k;
        tmp.set(H_NAV_X * k, cy - H_NAV_H / 2 * k, tabRects[names.length - 1].right + pad, cy + H_NAV_H / 2 * k);
        float rr = tmp.height() / 2;
        fill.setShader(null);
        fill.setColor(0x2EFFFFFF);
        c.drawRoundRect(tmp, rr, rr, fill);
        rim.setShader(null);
        rim.setColor(0x33FFFFFF);
        rim.setStrokeWidth(1.5f * k);
        c.drawRoundRect(tmp, rr, rr, rim);
        rim.setStrokeWidth(2.5f * k);
        boolean navFocused = zone == Z_TABS && !infoOpen;
        for (int i = 0; i < names.length; i++) {
            RectF r = tabRects[i];
            float f = Ui.clamp(tabFocus[i].value), s = 1 + Tokens.FOCUS_GROW_TAB * f;
            boolean chosen = i == shelfTab;
            // The chosen filter: a white pill (dimmed while you move along the bar); the focused item: white, raised.
            float white = Math.max(f, chosen ? (navFocused ? 0.22f : 1) : 0);
            cell.set(r.centerX() - r.width() * s / 2, r.centerY() - r.height() * s / 2, r.centerX() + r.width() * s / 2, r.centerY() + r.height() * s / 2);
            float ir = cell.height() / 2;
            if (f > 0.02f) {
                tmp.set(cell);
                tmp.offset(0, 8 * k * f);
                shadow.setColor(Ui.alpha(0xFF000000, 0.45f * f));
                shadows.draw(c, tmp, ir, SHADOW_BLUR * k, shadow);
            }
            if (white > 0.01f) {
                fill.setColor(Ui.alpha(0xFFFFFFFF, white));
                c.drawRoundRect(cell, ir, ir, fill);
            }
            float ink = chosen && !navFocused ? 1 : f;
            Paint p = chosen ? sTabOn : sTab;
            p.setColor(blend(chosen ? Tokens.TEXT : Tokens.TEXT_SECONDARY, Tokens.INK, ink));
            float ix = cell.left + 26 * k * s;
            Symbols.draw(getContext(), c, TAB_SYMBOLS[i], ix + 13 * k * s, cell.centerY(), 24 * k * s, p.getColor());
            c.drawText(names[i], ix + 36 * k * s, cell.centerY() + Ui.halfCap(p), p);
        }
        // At the right: the time, and your initial (Settings > Personalize > Your name).
        float right = CONTENT_R * k;
        sClock.setTextAlign(Paint.Align.RIGHT);
        c.drawText(clockText, right, cy + Ui.halfCap(sClock), sClock);
        String name = Prefs.viewerName();
        // The weather pill, left of you: OK on it opens the Weather panel.
        float pillRight = right - sClock.measureText(clockText) - 28 * k - 56 * k - 22 * k;
        weatherPanel().drawPill(c, data.weather, pillRight, cy, k, Ui.clamp(weatherPill.value), 1);
        // You: your initial (Settings > Personalize > Your name), or the settings symbol without a name. Focused, it
        // grows in a white ring and says what OK does; OK opens EndGrid iQTV+'s settings.
        float f = Ui.clamp(profilePill.value), d = 56 * k * (1 + 0.14f * f);
        float cx = right - sClock.measureText(clockText) - 28 * k - 28 * k;
        if (f > 0.02f) {
            tmp.set(cx - d / 2, cy - d / 2 + 8 * k * f, cx + d / 2, cy + d / 2 + 8 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.45f * f));
            shadows.draw(c, tmp, d / 2, SHADOW_BLUR * k, shadow);
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFFFFFFFF, f));
            c.drawCircle(cx, cy, d / 2 + 6 * k, fill);
        }
        if (avatarShader == null || avatarX != cx || avatarD != d) {
            avatarX = cx;
            avatarD = d;
            avatarShader = new LinearGradient(cx - d / 2, cy - d / 2, cx + d / 2, cy + d / 2, Brand.CYAN, Brand.VIOLET, Shader.TileMode.CLAMP);
        }
        fill.setColor(0xFFFFFFFF);   // opaque: the tabs above leave `fill` translucent while the bar has the focus
        fill.setShader(avatarShader);
        c.drawCircle(cx, cy, d / 2, fill);
        fill.setShader(null);
        if (!name.isEmpty()) {
            String initial = name.substring(0, 1).toUpperCase(Ui.locale());
            sAvatar.setTextAlign(Paint.Align.CENTER);
            c.drawText(initial, cx, cy + Ui.halfCap(sAvatar), sAvatar);
        } else {
            Symbols.draw(getContext(), c, "settings", cx, cy, 28 * k, 0xFFFFFFFF);
        }
        if (f > 0.02f) {
            String caption = name.isEmpty() ? Ui.tr("light|Settings") : Ui.trf("{name} · Settings", "name", name);
            sProfile.setAlpha(Math.round(255 * f));
            sProfile.setTextAlign(Paint.Align.RIGHT);
            c.drawText(caption, right, cy + 64 * k, sProfile);
        }
    }

    private float avatarD;

    /**
     * The hero: a label, the title's logo art (TMDB's, when it has one) or its name, year • language • genre •
     * length • service, TMDB's description, the buttons, and the carousel's dots while it's on the featured titles.
     */
    private void drawHero(Canvas c, Recs.Pick p, float a, float rise) {
        int alpha = Math.round(255 * a);
        String label = p.progress > 0 ? Ui.tr("Continue Watching") : "watched".equals(p.kind) ? Ui.tr("You watched")
                : p.pkg != null ? Ui.tr("Now Streaming") : "";
        if (!label.isEmpty()) {
            sHeroLabel.setAlpha(Math.round(0xE6 * a));
            c.drawText(label, MARGIN * k, H_LABEL_BASE * k + rise, sHeroLabel);
        }
        Bitmap logo = recs.art(p.logo, Math.round(H_LOGO_W * k), Math.round(H_LOGO_H * k), false);
        if (logo != null) {
            bitmapPaint.setAlpha(alpha);
            tmp.set(MARGIN * k, H_TITLE_BOTTOM * k - logo.getHeight() + rise, MARGIN * k + logo.getWidth(), H_TITLE_BOTTOM * k + rise);
            c.drawBitmap(logo, null, tmp, bitmapPaint);
            bitmapPaint.setAlpha(255);
        } else if (shelfTitle != null) {
            sTitle.setAlpha(alpha);
            c.save();
            c.translate(MARGIN * k, H_TITLE_BOTTOM * k - shelfTitle.getHeight() + rise);
            shelfTitle.draw(c);
            c.restore();
        }
        String meta = heroMeta(p);
        if (!meta.isEmpty()) {
            sMeta.setAlpha(Math.round(0xE6 * a));
            fitPaint.set(sMeta);
            c.drawText(TextUtils.ellipsize(meta, fitPaint, H_TITLE_W * k, TextUtils.TruncateAt.END).toString(), MARGIN * k, H_META_BASE * k + rise, sMeta);
        }
        if (shelfOverview != null) {
            sOverview.setAlpha(Math.round(0xC7 * a));
            c.save();
            c.translate(MARGIN * k, H_OVERVIEW_BASE * k - Ui.cap(sOverview) + rise);
            shelfOverview.draw(c);
            c.restore();
        }
        for (int i = 0; i < actionCount(); i++) drawAction(c, i, a);
        // The carousel's dots (only on a featured title, at the top).
        List<Recs.Pick> f = featured();
        int at = -1;
        for (int i = 0; i < f.size(); i++) if (Recs.key(f.get(i)).equals(Recs.key(p))) at = i;
        if (f.size() > 1 && at >= 0 && zone != Z_CARDS) {
            float d = 10 * k, gap = 12 * k, wide = 28 * k;
            float total = (f.size() - 1) * (d + gap) + wide, x = w / 2f - total / 2, cy = H_DOTS_CY * k;
            fill.setShader(null);
            for (int i = 0; i < f.size(); i++) {
                float dw = i == at ? wide : d;
                fill.setColor(Ui.alpha(0xFFFFFFFF, (i == at ? 0.95f : 0.4f) * a));
                tmp.set(x, cy - d / 2, x + dw, cy + d / 2);
                c.drawRoundRect(tmp, d / 2, d / 2, fill);
                x += dw + gap;
            }
        }
    }

    /** "2024 • Tamil • Action • 2h 54m • Prime Video": year, the language it was made in, then TMDB's details. */
    private String heroMeta(Recs.Pick p) {
        List<String> parts = new ArrayList<>();
        if (!p.year.isEmpty()) parts.add(p.year);
        if (!p.lang.isEmpty()) parts.add(SettingsPages.languageName(p.lang));
        String meta = p.meta == null ? "" : p.meta;
        for (String part : meta.split(" · ")) if (!part.trim().isEmpty() && !parts.contains(part.trim())) parts.add(part.trim());
        if (p.pkg == null && p.detailed) parts.add(Ui.tr("Not on your apps"));
        return String.join("  •  ", parts);
    }

    /** More Info: the title's details over the dimmed screen (Back closes it, OK opens the title). */
    private void drawInfo(Canvas c, float a) {
        Recs.Pick p = shelfPick;
        if (p == null) return;
        fill.setShader(null);
        fill.setColor(Ui.alpha(Tokens.SCRIM, a));
        c.drawRect(screen, fill);
        float left = 240 * k, top = 180 * k, right = w - 240 * k, bottom = h - 180 * k;
        tmp.set(left, top + (1 - a) * 30 * k, right, bottom + (1 - a) * 30 * k);
        fill.setColor(Ui.alpha(Tokens.PANEL, a));
        c.drawRoundRect(tmp, Tokens.RADIUS_PANEL * k, Tokens.RADIUS_PANEL * k, fill);
        float x = tmp.left + 64 * k, width = tmp.width() - 128 * k;
        sInfoTitle.setAlpha(Math.round(255 * a));
        String title = TextUtils.ellipsize(p.title, sInfoTitle, width, TextUtils.TruncateAt.END).toString();
        c.drawText(title, x, tmp.top + 100 * k, sInfoTitle);
        sMeta.setAlpha(Math.round(0xD9 * a));
        c.drawText(TextUtils.ellipsize(heroMeta(p), new TextPaint(sMeta), width, TextUtils.TruncateAt.END).toString(), x, tmp.top + 150 * k, sMeta);
        String key = Recs.key(p) + "|" + width;
        if (infoText == null || !key.equals(infoFor)) {
            infoFor = key;
            String body = p.overview.isEmpty() ? Ui.tr("TMDB has no description of this title.") : p.overview;
            infoText = StaticLayout.Builder.obtain(body, 0, body.length(), sInfoBody, Math.round(width))
                    .setMaxLines(9).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0, 1.12f).build();
        }
        sInfoBody.setAlpha(Math.round(0xD9 * a));
        c.save();
        c.translate(x, tmp.top + 200 * k);
        infoText.draw(c);
        c.restore();
        String app = Recs.serviceName(p);
        String where = p.pkg == null ? Ui.trf("TMDB lists no streaming for it on your apps in {region}.", "region", Recs.region())
                : "watched".equals(p.kind) ? Ui.trf("You watched it in {app}.", "app", app)
                : Ui.trf("Streams on {app} in {region}, according to TMDB.", "app", app, "region", Recs.region());
        sOverview.setAlpha(Math.round(0xB3 * a));
        c.drawText(where, x, tmp.bottom - 96 * k, sOverview);
        sOverview.setAlpha(Math.round(0x99 * a));
        c.drawText(p.pkg != null ? Ui.tr("OK opens it · Back closes") : Ui.tr("Back closes"), x, tmp.bottom - 52 * k, sOverview);
    }

    /**
     * A hero button. The first (open the title) is white with dark text, as tvOS's main action; the others are glass
     * and turn white when focused. Focused: it grows a little, rises and casts a shadow.
     */
    private void drawAction(Canvas c, int i, float alpha) {
        RectF r = actionRects[i];
        float f = Ui.clamp(actionFocus[i].value), s = 1 + Tokens.FOCUS_GROW_BUTTON * actionFocus[i].value;
        boolean primary = actions().get(i) == ACT_PLAY;
        cell.set(r.centerX() - r.width() * s / 2, r.centerY() - r.height() * s / 2, r.centerX() + r.width() * s / 2, r.centerY() + r.height() * s / 2);
        cell.offset(0, -3 * k * f);
        float rr = 16 * k * s;
        if (f > 0.02f) {
            tmp.set(cell);
            tmp.offset(0, 12 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.5f * f * alpha));
            shadows.draw(c, tmp, rr, SHADOW_BLUR * k, shadow);
        }
        float white = primary ? 1 : f;
        fill.setShader(null);
        fill.setColor(Ui.alpha(blend(0x40FFFFFF, 0xFFFFFFFF, white), alpha));
        c.drawRoundRect(cell, rr, rr, fill);
        if (primary && f > 0.02f) {
            // The white button can't turn whiter: a light ring around it says it has the focus.
            rim.setShader(null);
            rim.setColor(Ui.alpha(0xFFFFFFFF, 0.7f * f * alpha));
            rim.setStrokeWidth(3 * k);
            tmp.set(cell.left - 6 * k, cell.top - 6 * k, cell.right + 6 * k, cell.bottom + 6 * k);
            c.drawRoundRect(tmp, rr + 6 * k, rr + 6 * k, rim);
            rim.setStrokeWidth(2.5f * k);
        }
        int ink = Ui.alpha(blend(0xFFFFFFFF, INK, white), alpha);
        float icon = 26 * k * s, x = cell.left + 32 * k * s;
        String symbol = actionSymbol(i);
        Symbols.draw(getContext(), c, symbol, x + icon / 2, cell.centerY(), 26 * k * s * (symbol.equals("play") ? 0.9f : 0.8f), ink);
        sAction.setColor(ink);
        sAction.setTextSize(28 * k * s);
        c.drawText(actionText(i), x + icon + 14 * k * s, cell.centerY() + Ui.halfCap(sAction), sAction);
        sAction.setTextSize(28 * k);
    }

    /** The app tile on the dock for a package (its own TV banner art), or null. */
    private HomeApps.Item appTile(String pkg) {
        if (pkg == null) return null;
        for (HomeApps.Item it : shelf) if (pkg.equals(it.pkg)) return it;
        return null;
    }

    /**
     * A card in a row: its picture (a poster in Popular and Latest, landscape art elsewhere) with rounded corners; under
     * it the title and the service. Focused, it grows 8%, rises, casts a shadow with a faint glow and lights its edge.
     * A started title shows how far you got (the app's own position). Without TMDB artwork (an episode an app
     * reported), the app's own banner art with the name over it; with neither, the name on a dark card.
     */
    private void drawCard(Canvas c, int r, int i, float alpha) {
        RectF base = thumbRect(r, i, gridCell);
        if (base.right < -40 * k || base.left > w + 40 * k) return;
        Row row = rows.get(r - 1);
        Recs.Pick p = row.items.get(i);
        float v = row.focus[i].value, f = Ui.clamp(v), s = 1 + 0.08f * v;
        float bw = base.width(), bh = base.height(), r0 = Tokens.RADIUS_CARD * k;
        float cx = base.centerX(), cy = base.centerY() - 8 * k * v;
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float rad = r0 * s;
        if (f > 0.02f) {
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            tmp.offset(0, 18 * k * f);
            shadowDeep.setColor(Ui.alpha(0xFF000000, Tokens.SHADOW_ALPHA * f * alpha));
            shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
            tmp.offset(0, -18 * k * f);
            shadowDeep.setColor(Ui.alpha(0xFFFFFFFF, Tokens.GLOW_ALPHA * f * alpha));   // the glow, readable from the sofa
            shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
            c.restore();
        }
        int a8 = Math.round(255 * alpha);
        boolean drawn = false;
        if (posterRow(row)) {
            Bitmap poster = recs.art(p.poster, Math.round(H_POSTER_W * k), Math.round(H_POSTER_H * k), true);
            if (poster != null) {
                imageFill.setAlpha(a8);
                tmp.set(cell);
                c.save();
                c.clipRect(cell);
                bitmapPaint.setAlpha(a8);
                // A rounded poster: drawn through its own shader would allocate per frame; the round corner mask
                // comes from drawing it as a round-rect with a shader made once per bitmap.
                roundImage(c, poster, posterShader(poster), cell, rad);
                bitmapPaint.setAlpha(255);
                c.restore();
                imageFill.setAlpha(255);
                drawn = true;
            }
        } else if (p.backdrop != null) {
            Backdrop b = recs.backdrop(p);
            if (b != null && b.thumb != null) {
                imageFill.setAlpha(a8);
                roundImage(c, b.thumb, b.thumbShader, cell, rad);
                imageFill.setAlpha(255);
                drawn = true;
            }
        } else if (p.thumb != null) {
            // What you watched, with the picture its app showed while it played.
            Bitmap t = recs.art(p.thumb, Math.round(H_CARD_W * k), Math.round(H_CARD_H * k), true);
            if (t != null) {
                imageFill.setAlpha(a8);
                roundImage(c, t, posterShader(t), cell, rad);
                imageFill.setAlpha(255);
                drawn = true;
            }
        }
        HomeApps.Item app = drawn ? null : appTile(p.pkg);
        boolean appArt = !drawn && app != null && app.tile != null && !posterRow(row);
        if (appArt) {
            // An episode an app reported, with no TMDB match: the app's own artwork (its name is under the card).
            imageFill.setAlpha(a8);
            roundImage(c, app.tile, app.tileShader, cell, rad);
            imageFill.setAlpha(255);
        } else if (!drawn) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(Tokens.PLACEHOLDER, alpha));
            c.drawRoundRect(cell, rad, rad, fill);
        }
        if (!drawn && !appArt && p.title != null) {
            fitPaint.set(sCardTitle);
            sCardTitle.setAlpha(Math.round(0xF2 * alpha));
            c.drawText(TextUtils.ellipsize(p.title, fitPaint, cell.width() - 32 * k, TextUtils.TruncateAt.END).toString(),
                    cell.left + 16 * k, cell.centerY() + Ui.halfCap(sCardTitle), sCardTitle);
        }
        if (p.progress > 0) {
            // Only the position the app itself reported (Continue Watching), never an estimate.
            float pad = 14 * k * s, barH = 6 * k * s;
            tmp.set(cell.left + pad, cell.bottom - pad - barH, cell.right - pad - 56 * k, cell.bottom - pad);
            fill.setShader(null);
            fill.setColor(Ui.alpha(0x59FFFFFF, alpha));
            c.drawRoundRect(tmp, barH / 2, barH / 2, fill);
            float bar = tmp.width();
            tmp.right = tmp.left + bar * Math.max(0.04f, p.progress);
            fill.setColor(Ui.alpha(Brand.CYAN, alpha));
            c.drawRoundRect(tmp, barH / 2, barH / 2, fill);
            sCardSub.setAlpha(Math.round(0xF2 * alpha));
            sCardSub.setTextAlign(Paint.Align.RIGHT);
            c.drawText(Math.round(p.progress * 100) + "%", cell.right - pad, cell.bottom - pad + 2 * k, sCardSub);
            sCardSub.setTextAlign(Paint.Align.LEFT);
        }
        if (f > 0.01f) {
            rim.setShader(null);
            rim.setColor(Ui.alpha(0xFFFFFFFF, 0.92f * f * alpha));
            rim.setStrokeWidth(3.5f * k);
            c.drawRoundRect(cell, rad, rad, rim);
            rim.setStrokeWidth(2.5f * k);
        }
        // Under the card: the title (brighter when focused) and the service it's on.
        float tx = base.left, ty = base.bottom + (30 + 10 * f) * k;
        fitPaint.set(sCardTitle);
        sCardTitle.setAlpha(Math.round((0xB3 + 0x4C * f) * alpha));
        c.drawText(TextUtils.ellipsize(p.title == null ? "" : p.title, fitPaint, bw + 16 * k, TextUtils.TruncateAt.END).toString(), tx, ty, sCardTitle);
        String service = p.pkg == null ? Ui.tr("Not on your apps") : Recs.serviceName(p);
        if (service != null && !service.isEmpty()) {
            sCardSub.setAlpha(Math.round((0x80 + 0x40 * f) * alpha));
            c.drawText(service, tx, ty + 30 * k, sCardSub);
        }
    }

    private final Map<Bitmap, BitmapShader> posterShaders = new java.util.WeakHashMap<>();

    /** The poster's shader for its rounded corners (made once per bitmap, not per frame). */
    private BitmapShader posterShader(Bitmap b) {
        BitmapShader s = posterShaders.get(b);
        if (s == null) posterShaders.put(b, s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        return s;
    }

    // MARK: Remote navigation

    /** Remote arrows: the navigation ⇄ the hero's buttons ⇄ the rows, one by one ⇄ Your Apps (⇄ the drawer). */
    private boolean moveTopShelf(int dx, int dy) {
        switch (zone) {
            case Z_SHELF:
                if (dx != 0) return false;               // along Your Apps: as before
                if (dy > 0) {
                    if (dockAfter() >= rows.size()) return false;   // nothing below: the drawer, as before
                    zone = Z_CARDS;
                    stackIndex = dockAfter() + 1;
                    focusRow(true);
                } else if (dockAfter() > 0) {
                    zone = Z_CARDS;
                    stackIndex = dockAfter();
                    focusRow(true);
                } else {
                    toTop();
                }
                break;
            case Z_TABS:
                if (dx != 0) {
                    // Past the last tab: the weather pill (when there's weather), then you (your initial: Settings).
                    boolean weather = WeatherPanel.has(data.weather);
                    if (profileFocus) {
                        if (dx > 0) return true;
                        profileFocus = false;
                        weatherFocus = weather;
                        break;
                    }
                    if (weatherFocus) {
                        weatherFocus = false;
                        profileFocus = dx > 0;
                        break;
                    }
                    if (dx > 0 && tabIndex == tabRects.length - 1) {
                        if (weather) weatherFocus = true; else profileFocus = true;
                        break;
                    }
                    int next = Math.max(0, Math.min(tabRects.length - 1, tabIndex + dx));
                    if (next == tabIndex) return true;
                    tabIndex = next;
                    if (tabIndex <= TAB_SHOWS) selectShelfTab(tabIndex);   // as on tvOS: focusing a filter shows it
                } else if (dy > 0) {
                    weatherFocus = false;
                    profileFocus = false;
                    if (actionCount() > 0) {
                        zone = Z_ACTIONS;
                        actionIndex = 0;
                    } else {
                        belowHero();
                    }
                } else {
                    return true;
                }
                break;
            case Z_ACTIONS:
                if (dx != 0) {
                    int next = actionIndex + dx;
                    // Past the first or last button: the previous or next featured title.
                    if (next < 0 || next >= actionCount()) return stepHero(dx);
                    actionIndex = next;
                } else if (dy < 0) {
                    zone = Z_TABS;
                    tabIndex = shelfTab;
                    weatherFocus = false;
                    profileFocus = false;
                } else {
                    belowHero();
                }
                break;
            case Z_CARDS: {
                Row row = focusedRow();
                if (row == null) return false;
                if (dx != 0) {
                    int next = Math.max(0, Math.min(row.items.size() - 1, row.index + dx));
                    if (next == row.index) return true;
                    row.index = next;
                    focusRow(true);
                } else if (dy < 0) {
                    if (stackIndex == dockAfter() + 1 && !shelf.isEmpty()) {
                        zone = Z_SHELF;  // Your Apps, above this row
                    } else if (stackIndex <= 1) {
                        toTop();
                    } else {
                        stackIndex--;
                        focusRow(true);
                    }
                } else if (stackIndex == dockAfter() && !shelf.isEmpty()) {
                    zone = Z_SHELF;      // Your Apps
                } else if (stackIndex < rows.size()) {
                    stackIndex++;
                    focusRow(true);
                } else if (!drawerItems.isEmpty()) {
                    openDrawer();        // after the last row: the inputs and the weather, as from Your Apps
                } else {
                    return true;
                }
                break;
            }
            default:
                return false;
        }
        retarget(false);
        click.move();
        return true;
    }

    /** Down from the hero: the first row, or Your Apps when it comes first. */
    private void belowHero() {
        if (dockAfter() == 0 && !shelf.isEmpty()) {
            zone = Z_SHELF;
        } else if (!rows.isEmpty()) {
            zone = Z_CARDS;
            stackIndex = 1;
            focusRow(false);
        }
    }

    /** Up from the first row: the hero's buttons (or the navigation), and the featured title again. */
    private void toTop() {
        if (actionCount() > 0 || featuredPick() != null) {
            showFeatured();
            zone = actionCount() > 0 ? Z_ACTIONS : Z_TABS;
            actionIndex = 0;
        } else {
            zone = Z_TABS;
        }
        tabIndex = shelfTab;
    }

    /** More Info for the title on screen; Back (or OK, which opens the title) closes it. */
    private void openInfo() {
        if (shelfPick == null) return;
        infoOpen = true;
        overlayWeather = false;
        overlaySearch = false;
        infoText = null;
        retarget(false);
        wake();
    }

    private void closeInfo() {
        infoOpen = false;
        retarget(false);
        wake();
    }

    private WeatherPanel weatherPanel() {
        if (weatherPanel == null) weatherPanel = new WeatherPanel(getContext());
        return weatherPanel;
    }

    /** OK on the weather pill: the Weather panel over the screen; Back or OK closes it. */
    private void openWeather() {
        weatherOpen = true;
        overlayWeather = true;
        overlaySearch = false;
        data.refreshIfStale();
        retarget(false);
        wake();
    }

    private void closeWeather() {
        weatherOpen = false;
        retarget(false);
        wake();
    }

    /** OK in the navigation: Apps opens All Apps, Search the search, Settings EndGrid iQTV+'s settings. */
    private void activateTab() {
        if (weatherFocus) {
            openWeather();
            return;
        }
        if (profileFocus) {   // you: EndGrid iQTV+'s settings
            activity.startActivity(new android.content.Intent(activity, SettingsActivity.class));
            return;
        }
        switch (tabIndex) {
            case TAB_APPS: openSheet(); break;
            case TAB_SEARCH: openSearch(); break;
            default: selectShelfTab(tabIndex); break;
        }
    }

    /** The focus is on the row's card: it becomes the title on screen, and its photo follows once the focus rests. */
    private void focusRow(boolean animate) {
        Row row = focusedRow();
        syncCards();
        if (row == null || row.items.isEmpty()) return;
        setShelfPick(row.items.get(row.index), animate);
        handler.removeCallbacks(settleShelf);
        handler.postDelayed(settleShelf, SHELF_SETTLE_MS);   // holding an arrow doesn't decode a photo per title
        preloadRows();
    }

    /** "Not for me": the title leaves the rows, and similar ones count less. */
    private void notForMe(Recs.Pick p) {
        recs.hide(p);
        if (searchResults != null) searchResults.remove(p);
        showToast(Ui.trf("Got it. Fewer picks like {title}.", "title", p.title));
        updateRows();
        if (zone != Z_CARDS) {
            showFeatured();
            zone = actionCount() > 0 ? Z_ACTIONS : Z_TABS;
        }
        retarget(false);
    }

    // MARK: - Search: typed (the TV's keyboard, which has its own mic) or spoken (Android's speech recognizer)

    static final int VOICE_REQUEST = 41;

    /** Search: a box to type in (a title, an actor or a director, with a year to narrow it), or Voice. */
    // MARK: Search (the Search screen: SearchPanel draws it)

    /** Search in the navigation: the Search screen, on Speak. */
    private void openSearch() {
        if (!TOP_SHELF) {
            startVoiceSearch();
            return;
        }
        searchOpen = true;
        overlaySearch = true;
        overlayWeather = false;
        searchFocus = SearchPanel.SPEAK;
        searchText = "";
        searchMessage = "";
        searchState = SearchPanel.IDLE;
        searchOpenedAt = SystemClock.uptimeMillis();
        retarget(false);
        wake();
    }

    /** The remote's Assistant or search key: the Search screen, already listening (Gemini answers what's said). */
    void searchFromRemote() {
        if (!TOP_SHELF) {
            startVoiceSearch();
            return;
        }
        if (sheetOpen || weatherOpen) reset(false);
        if (!searchOpen) openSearch();
        if (searchTyping) stopTyping(false);
        stopListening();
        searchFocus = SearchPanel.SPEAK;
        startListening();
        wake();
    }

    private void closeSearch() {
        stopListening();
        if (searchTyping) stopTyping(false);
        searchOpen = false;
        retarget(false);
        wake();
    }

    private SearchPanel searchPanel() {
        if (searchPanel == null) searchPanel = new SearchPanel(getContext());
        return searchPanel;
    }

    /** The caret, the listening pulse or the spinner: drawn on every frame while the Search screen shows one. */
    private boolean searchAnimating() {
        return searchOpen && (searchTyping || searchState == SearchPanel.LISTENING || searchState == SearchPanel.ASKING);
    }

    /** Speak and Type on the first row, the suggestions on the second. */
    private void moveSearch(int dx, int dy) {
        if (searchTyping) stopTyping(false);   // the keyboard was closed: the arrows are ours again
        if (searchState == SearchPanel.ASKING) return;
        int chips = SearchPanel.chips().length;
        boolean onChips = searchFocus >= SearchPanel.FIRST_CHIP;
        if (dy > 0 && !onChips) searchFocus = SearchPanel.FIRST_CHIP;
        else if (dy < 0 && onChips) searchFocus = SearchPanel.SPEAK;
        else if (dx != 0) {
            int lo = onChips ? SearchPanel.FIRST_CHIP : SearchPanel.SPEAK, hi = onChips ? SearchPanel.FIRST_CHIP + chips - 1 : SearchPanel.TYPE;
            searchFocus = Math.max(lo, Math.min(hi, searchFocus + dx));
        }
        heroDirty = true;
        wake();
    }

    private void activateSearch() {
        if (searchTyping) stopTyping(false);
        if (searchState == SearchPanel.ASKING) return;
        if (searchFocus == SearchPanel.SPEAK) {
            if (searchState == SearchPanel.LISTENING) stopListening(); else startListening();
        } else if (searchFocus == SearchPanel.TYPE) {
            stopListening();
            startTyping();
        } else {
            runSearch(SearchPanel.chips()[searchFocus - SearchPanel.FIRST_CHIP]);
        }
        heroDirty = true;
        wake();
    }

    private void searchFailed(String message) {
        searchState = SearchPanel.ERROR;
        searchMessage = message;
        heroDirty = true;
        wake();
    }

    /** The question: with a Gemini key, Gemini picks the titles; without one, TMDB's search by name. */
    private void runSearch(String question) {
        String q = question == null ? "" : question.trim();
        if (q.isEmpty()) return;
        searchText = q;
        searchState = SearchPanel.ASKING;
        searchMessage = "";
        heroDirty = true;
        wake();
        if (!Gemini.on()) {
            recs.search(q, (results, error) -> {
                if (!running || !searchOpen) return;
                if (!error.isEmpty() && results.isEmpty()) {
                    searchFailed(Ui.trf("Search didn't work: {error}", "error", error));
                    return;
                }
                closeSearch();
                showSearchResults(q, results, false);
            });
            return;
        }
        recs.askGemini(q, (results, answer, error) -> {
            if (!running || !searchOpen) return;
            if (results.isEmpty()) {
                searchFailed(error.isEmpty() ? Ui.tr("Gemini found nothing for that. Try other words.") : error);
                return;
            }
            closeSearch();
            showSearchResults(q, results, true);
            if (!answer.isEmpty()) showToast(answer);
        });
    }

    // Typing: the TV's own keyboard writes into searchTyped through this view's input connection.

    private void startTyping() {
        searchTyping = true;
        searchTyped.clear();
        searchTyped.append(searchText);
        requestFocus();
        android.view.inputmethod.InputMethodManager imm = activity.getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (imm == null) return;
        imm.restartInput(this);
        imm.showSoftInput(this, 0);
    }

    /** The keyboard is done: `search` runs what was typed. */
    private void stopTyping(boolean search) {
        if (!searchTyping) return;
        searchTyping = false;
        searchText = searchTyped.toString().trim();
        android.view.inputmethod.InputMethodManager imm = activity.getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (imm != null) {
            imm.hideSoftInputFromWindow(getWindowToken(), 0);
            imm.restartInput(this);
        }
        if (search) runSearch(searchText);
        heroDirty = true;
        wake();
    }

    @Override public boolean onCheckIsTextEditor() {
        return searchTyping;
    }

    @Override public android.view.inputmethod.InputConnection onCreateInputConnection(android.view.inputmethod.EditorInfo out) {
        if (!searchTyping) return null;
        out.inputType = android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT;
        // The keyboard at the bottom, not over the whole screen: the question shows in the Search screen as it's typed.
        out.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN;
        out.initialSelStart = out.initialSelEnd = searchTyped.length();
        return new android.view.inputmethod.BaseInputConnection(this, true) {
            @Override public android.text.Editable getEditable() { return searchTyped; }
            @Override public boolean performEditorAction(int action) {
                stopTyping(true);
                return true;
            }
            @Override public boolean endBatchEdit() {
                heroDirty = true;
                wake();
                return super.endBatchEdit();
            }
            @Override public boolean commitText(CharSequence text, int newCursorPosition) {
                boolean r = super.commitText(text, newCursorPosition);
                heroDirty = true;
                wake();
                return r;
            }
            @Override public boolean deleteSurroundingText(int before, int after) {
                boolean r = super.deleteSurroundingText(before, after);
                heroDirty = true;
                wake();
                return r;
            }
            @Override public boolean sendKeyEvent(KeyEvent e) {
                // Some keyboards send Enter and Delete as keys.
                if (e.getAction() == KeyEvent.ACTION_DOWN && (e.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        || e.getKeyCode() == KeyEvent.KEYCODE_SEARCH)) {
                    stopTyping(true);
                    return true;
                }
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getKeyCode() == KeyEvent.KEYCODE_DEL && searchTyped.length() > 0) {
                    searchTyped.delete(searchTyped.length() - 1, searchTyped.length());
                    heroDirty = true;
                    wake();
                    return true;
                }
                return super.sendKeyEvent(e);
            }
        };
    }

    // Listening: the TV's speech recognizer, inside EndGrid iQTV+ (not the Assistant's screen); the words show as
    // they're heard, and the question runs when the person stops speaking.

    static final int MIC_REQUEST = 42;

    /**
     * The TV's speech recognizer: the one set in the system, else the first installed (the Google app's). Android can
     * clear the setting when EndGrid iQTV+ becomes the TV's assistant; the recognizer itself stays.
     */
    private android.content.ComponentName recognitionService() {
        String set = android.provider.Settings.Secure.getString(getContext().getContentResolver(), "voice_recognition_service");
        android.content.ComponentName c = set == null || set.isEmpty() ? null : android.content.ComponentName.unflattenFromString(set);
        if (c != null) return c;
        for (android.content.pm.ResolveInfo r : getContext().getPackageManager().queryIntentServices(
                new android.content.Intent(android.speech.RecognitionService.SERVICE_INTERFACE), 0)) {
            return new android.content.ComponentName(r.serviceInfo.packageName, r.serviceInfo.name);
        }
        return null;
    }

    private void startListening() {
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(getContext())) {
            searchFailed(Ui.tr("Voice search isn't available on this TV."));
            return;
        }
        if (activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[] {android.Manifest.permission.RECORD_AUDIO}, MIC_REQUEST);
            return;   // micAllowed() continues
        }
        if (recognizer == null) {
            recognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(getContext(), recognitionService());
            recognizer.setRecognitionListener(new android.speech.RecognitionListener() {
                @Override public void onReadyForSpeech(android.os.Bundle b) {}
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float db) {}
                @Override public void onBufferReceived(byte[] b) {}
                @Override public void onEndOfSpeech() {}
                @Override public void onEvent(int type, android.os.Bundle b) {}
                @Override public void onPartialResults(android.os.Bundle b) {
                    String heard = first(b);
                    if (!heard.isEmpty()) {
                        searchText = heard;
                        heroDirty = true;
                        wake();
                    }
                }
                @Override public void onResults(android.os.Bundle b) {
                    String heard = first(b);
                    if (heard.isEmpty()) heard = searchText;
                    if (heard.isEmpty()) {
                        searchFailed(Ui.tr("Didn't catch that. Try again."));
                        return;
                    }
                    runSearch(heard);
                }
                @Override public void onError(int error) {
                    if (searchState != SearchPanel.LISTENING) return;
                    searchFailed(error == android.speech.SpeechRecognizer.ERROR_NO_MATCH || error == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                            ? Ui.tr("Didn't catch that. Try again.")
                            : error == android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ? Ui.tr("The microphone isn't allowed.")
                            : Ui.tr("Voice search isn't available right now."));
                }
            });
        }
        android.content.Intent i = new android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(android.speech.RecognizerIntent.EXTRA_CALLING_PACKAGE, getContext().getPackageName());
        searchText = "";
        searchMessage = "";
        searchState = SearchPanel.LISTENING;
        try {
            recognizer.startListening(i);
        } catch (RuntimeException e) {
            searchFailed(Ui.tr("Voice search isn't available right now."));
        }
    }

    private static String first(android.os.Bundle b) {
        java.util.ArrayList<String> heard = b == null ? null : b.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
        return heard == null || heard.isEmpty() || heard.get(0) == null ? "" : heard.get(0).trim();
    }

    private void stopListening() {
        if (recognizer != null) {
            try {
                recognizer.destroy();
            } catch (RuntimeException ignored) {
            }
            recognizer = null;
        }
        if (searchState == SearchPanel.LISTENING) searchState = SearchPanel.IDLE;
    }

    /** The answer to the microphone permission (HomeActivity hands it over): listening starts if allowed. */
    void micAllowed(boolean allowed) {
        if (!searchOpen) return;
        if (allowed) startListening(); else searchFailed(Ui.tr("The microphone isn't allowed."));
    }

    /** The results become the first row (under Your Apps), with the focus on the first. */
    private void showSearchResults(String query, List<Recs.Pick> results, boolean byGemini) {
        searchQuery = query;
        searchByGemini = byGemini;
        searchResults = new ArrayList<>(results);
        rows.removeIf(r -> r.id.equals("search"));   // a new search starts at its first result
        updateRows();
        zone = Z_CARDS;
        stackIndex = 1;
        focusRow(false);
        retarget(false);
    }

    private void startVoiceSearch() {
        android.content.Intent i = new android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH);
        i.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, Ui.tr("Say a movie or show"));
        i.putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        try {
            activity.startActivityForResult(i, VOICE_REQUEST);
        } catch (android.content.ActivityNotFoundException | SecurityException e) {
            showToast(Ui.tr("Voice search isn't available on this TV."));
        }
    }

    /** What was typed or heard (HomeActivity hands speech over): the matches become the first row. */
    void voiceResult(String text) {
        if (text == null || text.trim().isEmpty()) return;
        searchQuery = text.trim();
        showToast(Ui.trf("Searching for “{query}”…", "query", searchQuery));
        recs.search(searchQuery, (results, error) -> {
            if (!running) return;
            if (!error.isEmpty() && results.isEmpty()) {
                showToast(Ui.trf("Search didn't work: {error}", "error", error));
                return;
            }
            showSearchResults(searchQuery, results, false);
        });
    }

    private void endSearch() {
        searchResults = null;
        searchQuery = "";
        stackIndex = 0;
        updateRows();
        toTop();
        retarget(false);
    }

    private boolean moveLight(int dx, int dy) {
        switch (zone) {
            case Z_SHELF:
                // Up from an empty shelf too: move()'s older zones (the hero, the corner capsule) aren't drawn any more.
                if (dy >= 0) return false;
                if (cards.isEmpty()) {
                    if (!picksOn() && lightButtons.isEmpty()) return true;   // no tabs and no buttons above
                    zone = Z_TOP;
                    topIndex = picksOn() ? tab : 3;
                } else {
                    zone = Z_CARDS;
                    cardIndex = nearest(cardRects, cards.size(), shelf.isEmpty() ? w / 2f : tileRects[shelfIndex].centerX());
                }
                break;
            case Z_CARDS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(cards.size() - 1, cardIndex + dx));
                    if (next == cardIndex) return true;
                    cardIndex = next;
                } else if (dy < 0) {
                    zone = Z_TOP;
                    topIndex = tab;     // as on Apple TV: moving up to the tabs focuses the selected tab
                } else {
                    zone = Z_SHELF;
                    if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, tileRects.length, cardRects[cardIndex].centerX());
                }
                break;
            case Z_TOP:
                if (dx != 0) {
                    int next = Math.max(picksOn() ? 0 : 3, Math.min(2 + lightButtons.size(), topIndex + dx));
                    if (next == topIndex) return true;
                    topIndex = next;
                    if (topIndex < 3) selectTab(topIndex);   // like Apple TV tabs: focusing a tab selects it
                } else if (dy > 0) {
                    if (cards.isEmpty()) {
                        zone = Z_SHELF;
                    } else {
                        zone = Z_CARDS;
                        cardIndex = nearest(cardRects, cards.size(), topRects[topIndex].centerX());
                    }
                } else if (topIndex < 3 && !lightButtons.isEmpty()) {
                    topIndex = 3;   // up from the tabs reaches the corner buttons without walking the row
                } else {
                    return true;
                }
                break;
            default:
                return false;
        }
        retarget(false);
        click.move();
        return true;
    }

    private static int nearest(RectF[] rects, int count, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < Math.min(count, rects.length); i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    // MARK: - Helpers

    /** Rounds a shape's edges to whole pixels (crisp at rest; a scale animation moves them again). */
    private static void snap(RectF r) {
        r.set(Math.round(r.left), Math.round(r.top), Math.round(r.right), Math.round(r.bottom));
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = Ui.clamp((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    private static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
