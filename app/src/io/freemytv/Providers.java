package io.freemytv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The streaming services EndGrid iQTV+ knows: one entry per service, with its Android TV apps and what it can do here.
 * Add a service by adding a line to ALL (its TMDB provider name decides when TMDB's availability data names it).
 *
 * Capabilities are optional and honest: every service can be detected and opened when its app is installed;
 * METADATA means TMDB lists its catalog for regions (what the picks and search rely on, so a title is never said to
 * be on a service without it); DEEP_LINK needs a Watchmode key and an app that opens title addresses; NOW_PLAYING only
 * where the app reports what it plays through Android's media sessions (none of this is guaranteed by the app makers,
 * so EndGrid treats it as "if reported").
 */
final class Providers {
    private Providers() {}

    enum Capability { DETECT, LAUNCH, METADATA, DEEP_LINK, NOW_PLAYING }

    static final class Provider {
        final String key, name;
        final String[] tmdbNames;     // part of TMDB's provider name, lowercase ("prime video")
        final String[] packages;      // its Android TV apps, the usual first
        final Set<Capability> caps;

        Provider(String key, String name, String[] tmdbNames, String[] packages, Set<Capability> caps) {
            this.key = key;
            this.name = name;
            this.tmdbNames = tmdbNames;
            this.packages = packages;
            this.caps = caps;
        }

        boolean can(Capability c) { return caps.contains(c); }

        /** TMDB's provider name is this service ("Amazon Prime Video with Ads" is Prime Video; "… Channel" isn't). */
        boolean matches(String tmdbName, int providerId) {
            String n = tmdbName.toLowerCase(Locale.ROOT);
            if (n.contains(" channel")) return false;          // sold inside another app
            if (key.equals("appletv") && providerId != 350) return false;   // 2 is the Apple TV Store (rent or buy)
            for (String t : tmdbNames) {
                // Short names as whole words: "max" isn't "Maxdome", "aha" isn't in another service's name.
                boolean word = t.length() <= 4;
                if (word ? (" " + n + " ").contains(" " + t + " ") : n.contains(t)) return true;
            }
            return false;
        }
    }

    private static Set<Capability> full() {
        return EnumSet.of(Capability.DETECT, Capability.LAUNCH, Capability.METADATA, Capability.DEEP_LINK, Capability.NOW_PLAYING);
    }

    /** Only what any installed app allows: found and opened (no catalog data for it on TMDB). */
    private static Set<Capability> appOnly() {
        return EnumSet.of(Capability.DETECT, Capability.LAUNCH, Capability.NOW_PLAYING);
    }

    private static Provider p(String key, String name, String tmdb, Set<Capability> caps, String... packages) {
        return new Provider(key, name, tmdb.isEmpty() ? new String[0] : tmdb.split("\\|"), packages, caps);
    }

    static final List<Provider> ALL = Collections.unmodifiableList(java.util.Arrays.asList(
        p("netflix", "Netflix", "netflix", full(), "com.netflix.ninja"),
        p("prime", "Prime Video", "prime video", full(), "com.amazon.amazonvideo.livingroom", "com.amazon.firebat", "com.amazon.avod"),
        p("hotstar", "JioHotstar", "hotstar", full(), "in.startv.hotstar"),   // TMDB: "JioHotstar" (earlier "Disney Plus Hotstar")
        p("zee5", "ZEE5", "zee5", full(), "com.graymatrix.did"),
        p("sonyliv", "Sony LIV", "sony liv", full(), "com.sonyliv"),
        p("sunnxt", "Sun NXT", "sun nxt", full(), "com.suntv.sunnxt"),
        p("aha", "aha", "aha", full(), "ahaflix.tv"),
        // TMDB lists no Airtel Xstream Play catalog (checked 2026-10): no title is ever said to be on it.
        p("airtel", "Airtel Xstream Play", "airtel xstream", appOnly(), "tv.airtel.xstream.tvapp"),
        p("disney", "Disney+", "disney", full(), "com.disney.disneyplus"),
        p("appletv", "Apple TV", "apple tv", full(), "com.apple.atve.androidtv.appletv", "com.apple.atve.sony.appletv", "com.apple.atve.amazon.appletv"),
        p("max", "Max", "max", full(), "com.wbd.stream", "com.hbo.hbonow"),
        p("hulu", "Hulu", "hulu", full(), "com.hulu.livingroomplus", "com.hulu.plus"),
        p("paramount", "Paramount+", "paramount", full(), "com.cbs.ott"),
        p("peacock", "Peacock", "peacock", full(), "com.peacocktv.peacockandroid", "com.peacock.peacockfiretv"),
        p("globoplay", "Globoplay", "globoplay", full(), "com.globo.globotv"),
        p("okko", "Okko", "okko", full(), "ru.more.play"),
        p("crunchyroll", "Crunchyroll", "crunchyroll", full(), "com.crunchyroll.crunchyroid"),
        p("oneplay", "Oneplay", "oneplay", full(), "net.cme.voyo.cz.tvapp"),   // Czechia: Voyo and O2 TV became Oneplay
        p("youtube", "YouTube", "", appOnly(), "com.google.android.youtube.tv", "com.amazon.firetv.youtube")
    ));

    static Provider byKey(String key) {
        for (Provider p : ALL) if (p.key.equals(key)) return p;
        return null;
    }

    static Provider byPackage(String pkg) {
        for (Provider p : ALL) for (String q : p.packages) if (q.equals(pkg)) return p;
        return null;
    }

    /** Every TMDB match for a provider name, the services with an installed app first (as Recs.service wants). */
    static List<Provider> matching(String tmdbName, int providerId) {
        List<Provider> out = new ArrayList<>();
        for (Provider p : ALL) if (p.matches(tmdbName, providerId)) out.add(p);
        return out;
    }
}
