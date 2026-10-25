package io.freemytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.EditText;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * EndGrid iQTV+'s own settings pages (Settings > Personalize): language and region, streaming apps, recommendations
 * and genres, home rows, screensaver, about, reset. Each page is a list of options; SettingsActivity draws them in its
 * style and sends the remote's keys here: OK runs `ok`, ← → run `step` (a value) or `move` (a place in an order).
 *
 * Everything is stored on the TV (Prefs); a page only offers what this TV and its data sources can do, and says so in
 * the hint where something isn't possible (dubbed versions, an app with no catalog data).
 */
final class SettingsPages {
    private SettingsPages() {}

    /** What the pages need from the settings screen. */
    interface Host {
        Activity activity();
        void open(Page page);
        void refresh();          // a value changed: build the page's rows again
        void openShelf();        // the dock's apps and order (the existing editor)
        void openHomeSettings(); // the existing Home Settings list
        void preview();          // the screensaver, right here
    }

    /** One row: a name, a value; what OK and the arrows do; a hint for when it has the focus. */
    static final class Opt {
        final String label;
        String value = "", hint = "";
        boolean chevron, checked, dim;
        Runnable ok;
        IntConsumer step, move;

        Opt(String label) {
            this.label = label;
        }

        Opt value(String v) { value = v == null ? "" : v; return this; }
        Opt hint(String h) { hint = h; return this; }
        Opt chevron() { chevron = true; return this; }
        Opt checked(boolean on) { checked = on; return this; }
        Opt dim() { dim = true; return this; }
        Opt ok(Runnable r) { ok = r; return this; }
        Opt step(IntConsumer c) { step = c; return this; }
        Opt move(IntConsumer c) { move = c; return this; }
    }

    abstract static class Page {
        int focus, scroll;   // where the focus was when another page opened over this one (Back returns there)

        abstract String title();
        abstract List<Opt> rows();
        /** The hint under the list when the focused row has none. */
        String hint() { return Ui.tr("OK or the arrows change the value."); }
    }

    private static String onOff(boolean on) {
        return on ? Ui.tr("On") : Ui.tr("Off");
    }

    // MARK: - Personalize (the hub)

    static Page hub(Host host) {
        return new Page() {
            @Override String title() { return Ui.tr("Personalize"); }

            @Override List<Opt> rows() {
                Context c = host.activity();
                List<Opt> out = new ArrayList<>();
                String name = Prefs.viewerName();
                out.add(new Opt(Ui.tr("Your name")).value(name.isEmpty() ? Ui.tr("Not set") : name)
                        .hint(Ui.tr("On the home screen: your initial at the top, and “Recommended for <name>”.")).ok(() -> askName(host)));
                out.add(new Opt(Ui.tr("Language & Region")).value(languagesText()).chevron().ok(() -> host.open(language(host))));
                out.add(new Opt(Ui.tr("Streaming Apps")).chevron().ok(() -> host.open(apps(host))));
                out.add(new Opt(Ui.tr("Recommendations")).chevron().ok(() -> host.open(recommendations(host))));
                out.add(new Opt(Ui.tr("Home Rows")).chevron().ok(() -> host.open(homeRows(host))));
                out.add(new Opt(Ui.tr("Screensaver")).chevron().ok(() -> host.open(screensaver(host))));
                out.add(new Opt(Ui.tr("Home Settings")).chevron()
                        .hint(Ui.tr("Menu language, temperature, theme, your photos, sounds, weather and prices."))
                        .ok(host::openHomeSettings));
                out.add(new Opt(Ui.tr("About")).chevron().ok(() -> host.open(about(host))));
                out.add(new Opt(Ui.tr("Reset preferences")).hint(Ui.tr("Back to the defaults: languages, region, apps, rows, genres, hidden titles."))
                        .ok(() -> confirm(host, Ui.tr("Reset all preferences?"), Ui.tr("Reset"), () -> {
                            Prefs.resetPersonal();
                            Recs.get(c).clearHidden();
                            host.refresh();
                        })));
                return out;
            }

            @Override String hint() { return Ui.tr("Everything here stays on this TV."); }
        };
    }

    private static void askName(Host host) {
        EditText field = new EditText(host.activity());
        field.setSingleLine(true);
        field.setText(Prefs.viewerName());
        field.setHint(Ui.tr("Your name"));
        new AlertDialog.Builder(host.activity(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Ui.tr("Your name"))
                .setView(field)
                .setPositiveButton(Ui.tr("Save"), (d, which) -> {
                    Prefs.setViewerName(field.getText().toString());
                    host.refresh();
                })
                .setNegativeButton(Ui.tr("Cancel"), null)
                .show();
    }

    /** The TMDB key typed on the TV (an empty one goes back to the build's, if it has one). Never shown back in full. */
    private static void askTmdbKey(Host host) {
        EditText field = new EditText(host.activity());
        field.setSingleLine(true);
        field.setHint(Prefs.tmdbKeyTyped() ? Ui.tr("A key is set: type a new one, or clear it") : Ui.tr("API Read Access Token or API Key"));
        new AlertDialog.Builder(host.activity(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Ui.tr("TMDB key"))
                .setView(field)
                .setPositiveButton(Ui.tr("Save"), (d, which) -> {
                    Prefs.setTmdbKey(field.getText().toString());
                    host.refresh();
                })
                .setNegativeButton(Ui.tr("Cancel"), null)
                .show();
    }

    private static void confirm(Host host, String question, String yes, Runnable then) {
        AlertDialog dialog = new AlertDialog.Builder(host.activity(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(question)
                .setPositiveButton(yes, (d, which) -> then.run())
                .setNegativeButton(Ui.tr("Cancel"), null)
                .show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();   // a stray OK changes nothing
    }

    // MARK: - Language & Region

    /** The languages most asked for first; then every other language TMDB has (Recs.catalog). */
    static final List<String> COMMON_LANGUAGES = Collections.unmodifiableList(Arrays.asList(
            "en", "ta", "hi", "te", "ml", "kn", "bn", "mr", "pa", "es", "fr", "de", "ja", "ko", "ar", "zh", "pt", "it", "ru", "tr"));
    static final List<String> COMMON_REGIONS = Collections.unmodifiableList(Arrays.asList(
            "IN", "US", "GB", "CA", "AU", "DE", "FR", "ES", "IT", "BR", "MX", "JP", "KR", "AE", "SG"));

    static String languageName(String code) {
        String name = new Locale(code).getDisplayLanguage(Ui.locale());
        if (name.isEmpty() || name.equals(code)) return code;
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static String languagesText() {
        List<String> langs = Prefs.contentLanguages();
        if (langs.isEmpty()) return Ui.tr("Any language");
        StringBuilder sb = new StringBuilder();
        for (String l : langs) sb.append(sb.length() == 0 ? "" : ", ").append(languageName(l));
        return sb.toString();
    }

    private static String regionName(String code) {
        String name = new Locale("", code).getDisplayCountry(Ui.locale());
        return name.isEmpty() ? code : name;
    }

    static Page language(Host host) {
        return new Page() {
            @Override String title() { return Ui.tr("Language & Region"); }

            @Override List<Opt> rows() {
                List<Opt> out = new ArrayList<>();
                out.add(new Opt(Ui.tr("Content languages")).value(languagesText()).chevron()
                        .hint(Ui.tr("The rows show titles made in these languages, the first one first."))
                        .ok(() -> host.open(languages(host))));
                out.add(new Opt(Ui.tr("Country or region")).value(regionName(Prefs.region())).chevron()
                        .hint(Ui.tr("Which streaming catalog counts: what's available differs by country."))
                        .ok(() -> host.open(regions(host))));
                out.add(new Opt(Ui.tr("Dubbed and subtitled")).value(Ui.tr("Not available")).dim()
                        .hint(Ui.tr("TMDB has no data on dubbed or subtitled versions, nor on audio tracks.")));
                out.add(new Opt(Ui.tr("Menu language")).value(Prefs.language().equals("auto") ? Ui.tr("Automatic") : Ui.languageName(Prefs.language()))
                        .hint(Ui.tr("In Home Settings.")).chevron().ok(host::openHomeSettings));
                return out;
            }
        };
    }

    /** Content languages: OK adds or removes one; ← → move a chosen one up or down (its priority). */
    static Page languages(Host host) {
        final List<String[]> all = new ArrayList<>();
        Page page = new Page() {
            @Override String title() { return Ui.tr("Content languages"); }

            @Override List<Opt> rows() {
                List<String> chosen = Prefs.contentLanguages();
                List<Opt> out = new ArrayList<>();
                out.add(new Opt(Ui.tr("Any language")).checked(chosen.isEmpty())
                        .hint(Ui.tr("No language filter: what's popular and trending anywhere."))
                        .ok(() -> {
                            Prefs.setContentLanguages(new ArrayList<>());
                            host.refresh();
                        }));
                for (int i = 0; i < chosen.size(); i++) {
                    String code = chosen.get(i);
                    out.add(new Opt(languageName(code)).checked(true).value(ordinal(i + 1))
                            .hint(Ui.tr("OK removes it; ← → change its place."))
                            .ok(() -> {
                                List<String> next = new ArrayList<>(Prefs.contentLanguages());
                                next.remove(code);
                                Prefs.setContentLanguages(next);
                                host.refresh();
                            })
                            .move(dir -> {
                                Prefs.setContentLanguages(Lists.move(Prefs.contentLanguages(), code, dir));
                                host.refresh();
                            }));
                }
                List<String> others = new ArrayList<>();
                for (String code : COMMON_LANGUAGES) if (!chosen.contains(code)) others.add(code);
                List<String[]> rest = new ArrayList<>();
                for (String[] l : all) if (!chosen.contains(l[0]) && !others.contains(l[0]) && !l[0].equals("xx")) rest.add(l);
                Collections.sort(rest, (a, b) -> languageName(a[0]).compareToIgnoreCase(languageName(b[0])));
                for (String[] l : rest) others.add(l[0]);
                for (String code : others) {
                    out.add(new Opt(languageName(code)).hint(Ui.tr("OK adds it after the ones you chose."))
                            .ok(() -> {
                                List<String> next = new ArrayList<>(Prefs.contentLanguages());
                                next.add(code);
                                Prefs.setContentLanguages(next);
                                host.refresh();
                            }));
                }
                return out;
            }
        };
        Recs.get(host.activity()).catalog("languages", items -> {
            all.clear();
            all.addAll(items);
            host.refresh();
        });
        return page;
    }

    private static String ordinal(int n) {
        return Ui.trf("#{n}", "n", n);
    }

    static Page regions(Host host) {
        final List<String[]> all = new ArrayList<>();
        Page page = new Page() {
            @Override String title() { return Ui.tr("Country or region"); }

            @Override List<Opt> rows() {
                String now = Prefs.region();
                List<String> codes = new ArrayList<>();
                codes.add(now);
                for (String c : COMMON_REGIONS) if (!codes.contains(c)) codes.add(c);
                List<String[]> rest = new ArrayList<>(all);
                Collections.sort(rest, (a, b) -> regionName(a[0]).compareToIgnoreCase(regionName(b[0])));
                for (String[] r : rest) if (!codes.contains(r[0])) codes.add(r[0]);
                List<Opt> out = new ArrayList<>();
                for (String code : codes) {
                    out.add(new Opt(regionName(code)).checked(code.equals(now)).value(code).ok(() -> {
                        Prefs.setRegion(code);
                        host.refresh();
                    }));
                }
                return out;
            }

            @Override String hint() { return Ui.tr("Only countries TMDB has streaming data for."); }
        };
        Recs.get(host.activity()).catalog("regions", items -> {
            all.clear();
            all.addAll(items);
            host.refresh();
        });
        return page;
    }

    // MARK: - Streaming Apps

    /** The services in your order (Prefs.serviceOrder), then the rest as Providers lists them. */
    static List<Providers.Provider> orderedProviders() {
        List<Providers.Provider> out = new ArrayList<>();
        for (String key : Prefs.serviceOrder()) {
            Providers.Provider p = Providers.byKey(key);
            if (p != null) out.add(p);
        }
        for (Providers.Provider p : Providers.ALL) if (!out.contains(p)) out.add(p);
        return out;
    }

    private static String installedPackage(Context c, Providers.Provider p) {
        for (String pkg : p.packages) if (HomeApps.launchIntent(c.getPackageManager(), pkg) != null) return pkg;
        return null;
    }

    static Page apps(Host host) {
        return new Page() {
            @Override String title() { return Ui.tr("Streaming Apps"); }

            @Override List<Opt> rows() {
                Context c = host.activity();
                List<Opt> out = new ArrayList<>();
                out.add(new Opt(Ui.tr("Apps on the dock")).chevron().hint(Ui.tr("Which apps the dock shows, and their order."))
                        .ok(host::openShelf));
                List<String> hidden = Prefs.hiddenServices();
                List<Providers.Provider> order = orderedProviders();
                // Installed first (in your order), then the rest.
                List<Providers.Provider> shown = new ArrayList<>();
                for (Providers.Provider p : order) if (installedPackage(c, p) != null) shown.add(p);
                for (Providers.Provider p : order) if (installedPackage(c, p) == null) shown.add(p);
                for (Providers.Provider p : shown) {
                    String pkg = installedPackage(c, p);
                    boolean catalog = p.can(Providers.Capability.METADATA);
                    Opt o = new Opt(p.name).hint(capabilities(p, pkg != null));
                    if (pkg == null) {
                        o.value(Ui.tr("Not installed")).dim().ok(() -> install(host, p));
                    } else if (!catalog) {
                        o.value(Ui.tr("App only"));
                    } else {
                        boolean on = !hidden.contains(p.key);
                        o.value(on ? Ui.tr("In the picks") : Ui.tr("Left out")).checked(on).ok(() -> {
                            List<String> next = new ArrayList<>(Prefs.hiddenServices());
                            if (next.contains(p.key)) next.remove(p.key); else next.add(p.key);
                            Prefs.setHiddenServices(next);
                            host.refresh();
                        });
                    }
                    if (pkg != null) o.move(dir -> {
                        List<String> keys = new ArrayList<>();
                        for (Providers.Provider q : orderedProviders()) keys.add(q.key);
                        Prefs.setServiceOrder(Lists.move(keys, p.key, dir));
                        host.refresh();
                    });
                    out.add(o);
                }
                return out;
            }

            @Override String hint() { return Ui.tr("OK: in or out of the picks · ← →: your order of preference."); }
        };
    }

    /** What EndGrid can do with this service here, in words. */
    private static String capabilities(Providers.Provider p, boolean installed) {
        if (!installed) return Ui.tr("OK opens the TV's app store page for it, where there is one.");
        List<String> can = new ArrayList<>();
        can.add(Ui.tr("opens"));
        if (p.can(Providers.Capability.METADATA)) can.add(Ui.tr("titles and availability from TMDB"));
        if (p.can(Providers.Capability.DEEP_LINK) && !Config.WATCHMODE_KEY.trim().isEmpty()) can.add(Ui.tr("title pages (Watchmode)"));
        if (p.can(Providers.Capability.NOW_PLAYING)) can.add(Ui.tr("watch history if the app reports it"));
        if (!p.can(Providers.Capability.METADATA)) can.add(Ui.tr("no catalog data on TMDB"));
        return Ui.tr("Here: ") + String.join(" · ", can);
    }

    private static void install(Host host, Providers.Provider p) {
        Activity a = host.activity();
        Intent store = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + p.packages[0]))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (store.resolveActivity(a.getPackageManager()) != null) {
            a.startActivity(store);
        } else {
            android.widget.Toast.makeText(a, Ui.trf("Install {app} from the TV's app store.", "app", p.name), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    // MARK: - Recommendations

    static Page recommendations(Host host) {
        return new Page() {
            @Override String title() { return Ui.tr("Recommendations"); }

            @Override List<Opt> rows() {
                Recs recs = Recs.get(host.activity());
                List<Opt> out = new ArrayList<>();
                List<String> genres = Prefs.genres();
                out.add(new Opt(Ui.tr("TMDB key")).value(Prefs.tmdbKeyTyped() ? Ui.tr("Set") : Prefs.tmdbKey().isEmpty()
                                ? Ui.tr("Not set") : Ui.tr("Built in"))
                        .hint(Ui.tr("Free from themoviedb.org/settings/api: it brings the picks, rows and search. OK to paste it."))
                        .ok(() -> askTmdbKey(host)));
                out.add(new Opt(Ui.tr("Preferred genres")).value(genres.isEmpty() ? Ui.tr("None") : genresText(recs, genres)).chevron()
                        .hint(Ui.tr("They come first in every row, and each gets a row of its own."))
                        .ok(() -> host.open(genrePage(host))));
                out.add(new Opt(Ui.tr("Use what I watch")).value(onOff(Prefs.useHistory()))
                        .hint(Ui.tr("From apps that report what they play to Android. It stays on this TV."))
                        .ok(() -> { Prefs.setUseHistory(!Prefs.useHistory()); host.refresh(); })
                        .step(dir -> { Prefs.setUseHistory(!Prefs.useHistory()); host.refresh(); }));
                out.add(new Opt(Ui.tr("Suggest what I finished")).value(onOff(Prefs.suggestWatched()))
                        .hint(Ui.tr("Off: titles you finished only show in Recently Watched."))
                        .ok(() -> { Prefs.setSuggestWatched(!Prefs.suggestWatched()); host.refresh(); })
                        .step(dir -> { Prefs.setSuggestWatched(!Prefs.suggestWatched()); host.refresh(); }));
                int hidden = recs.hiddenCount();
                out.add(new Opt(Ui.tr("Titles marked “Not for me”")).value(String.valueOf(hidden))
                        .hint(hidden == 0 ? Ui.tr("None hidden.") : Ui.tr("OK shows them again."))
                        .ok(() -> {
                            if (recs.hiddenCount() == 0) return;
                            confirm(host, Ui.tr("Show the hidden titles again?"), Ui.tr("Show"), () -> {
                                recs.clearHidden();
                                host.refresh();
                            });
                        }));
                int removed = recs.forgottenCount();
                out.add(new Opt(Ui.tr("Removed from Continue Watching")).value(String.valueOf(removed))
                        .hint(removed == 0 ? Ui.tr("On the home screen, hold OK on a title to remove it.") : Ui.tr("OK shows them again."))
                        .ok(() -> {
                            if (recs.forgottenCount() == 0) return;
                            confirm(host, Ui.tr("Show the removed titles again?"), Ui.tr("Show"), () -> {
                                recs.clearForgotten();
                                host.refresh();
                            });
                        }));
                return out;
            }
        };
    }

    private static String genresText(Recs recs, List<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            String name = recs.genreName(id);
            if (!name.isEmpty()) sb.append(sb.length() == 0 ? "" : ", ").append(name);
        }
        return sb.length() == 0 ? String.valueOf(ids.size()) : sb.toString();
    }

    static Page genrePage(Host host) {
        final List<String[]> all = new ArrayList<>();
        Page page = new Page() {
            @Override String title() { return Ui.tr("Preferred genres"); }

            @Override List<Opt> rows() {
                List<String> chosen = Prefs.genres();
                List<Opt> out = new ArrayList<>();
                if (all.isEmpty()) out.add(new Opt(Ui.tr("Loading…")).dim());
                List<String[]> sorted = new ArrayList<>(all);
                Collections.sort(sorted, (a, b) -> a[1].compareToIgnoreCase(b[1]));
                for (String[] g : sorted) {
                    boolean on = chosen.contains(g[0]);
                    out.add(new Opt(g[1]).checked(on).ok(() -> {
                        List<String> next = new ArrayList<>(Prefs.genres());
                        if (next.contains(g[0])) next.remove(g[0]); else next.add(g[0]);
                        Prefs.setGenres(next);
                        host.refresh();
                    }));
                }
                return out;
            }

            @Override String hint() { return Ui.tr("OK chooses or drops a genre (up to four get their own rows)."); }
        };
        Recs.get(host.activity()).catalog("genres", items -> {
            all.clear();
            all.addAll(items);
            host.refresh();
        });
        return page;
    }

    // MARK: - Home Rows

    static Page homeRows(Host host) {
        return new Page() {
            @Override String title() { return Ui.tr("Home Rows"); }

            @Override List<Opt> rows() {
                List<Opt> out = new ArrayList<>();
                for (String[] r : Recs.rowChoices()) {
                    boolean on = r[2].equals("1");
                    out.add(new Opt(r[1]).checked(on).value(on ? Ui.tr("On") : Ui.tr("Off"))
                            .ok(() -> {
                                List<String> next = new ArrayList<>();
                                for (String id : Recs.rowOrder()) {
                                    boolean wasOn = Recs.rowOn(id);
                                    next.add(id.equals(r[0]) ? (wasOn ? "-" + id : id) : (wasOn ? id : "-" + id));
                                }
                                Prefs.setRows(next);
                                host.refresh();
                            })
                            .move(dir -> {
                                List<String> order = Lists.move(Recs.rowOrder(), r[0], dir), next = new ArrayList<>();
                                for (String id : order) next.add(Recs.rowOn(id) ? id : "-" + id);
                                Prefs.setRows(next);
                                host.refresh();
                            }));
                }
                return out;
            }

            @Override String hint() { return Ui.tr("OK turns a row on or off · ← → move it. Empty rows never show."); }
        };
    }

    // MARK: - Screensaver

    static Page screensaver(Host host) {
        return new Page() {
            @Override String title() { return Ui.tr("Screensaver"); }

            @Override List<Opt> rows() {
                Context c = host.activity();
                List<Opt> out = new ArrayList<>();
                int photos = Photos.count(c);
                boolean mine = Photos.on(c);
                out.add(new Opt(Ui.tr("Photo source")).value(mine ? Ui.tr("My photos") : Ui.tr("Movie and show art"))
                        .hint(photos == 0 ? Ui.tr("Your photos: a USB drive or the TV's storage, or sent from a computer (README).")
                                : Ui.plural("plural|one:{n} photo of yours on this TV.|other:{n} photos of yours on this TV.", photos))
                        .ok(() -> { if (Photos.count(c) > 0) { Prefs.setPhotos(!Prefs.photos()); host.refresh(); } })
                        .step(dir -> { if (Photos.count(c) > 0) { Prefs.setPhotos(!Prefs.photos()); host.refresh(); } }));
                String album = Prefs.saverAlbum();
                Opt albumRow = new Opt(Ui.tr("Album")).value(mine ? Photos.albumName(Photos.albums(c).contains(album) ? album : "") : "—")
                        .hint(Ui.tr("A folder of your photos (each folder on a USB drive or the TV is one)."));
                if (mine) albumRow.step(dir -> {
                    List<String> albums = new ArrayList<>(Photos.albums(c));
                    albums.add(0, "");
                    int at = Math.max(0, albums.indexOf(Prefs.saverAlbum()));
                    Prefs.setSaverAlbum(albums.get((at + dir + albums.size()) % albums.size()));
                    host.refresh();
                }).ok(() -> albumRow.step.accept(1));
                else albumRow.dim();
                out.add(albumRow);
                out.add(new Opt(Ui.tr("Order")).value(Prefs.saverShuffle() ? Ui.tr("Shuffle") : Ui.tr("In order"))
                        .ok(() -> { Prefs.setSaverShuffle(!Prefs.saverShuffle()); host.refresh(); })
                        .step(dir -> { Prefs.setSaverShuffle(!Prefs.saverShuffle()); host.refresh(); }));
                int s = Prefs.saverSeconds();
                out.add(new Opt(Ui.tr("Change photos every")).value(s < 60 ? Ui.trf("{n} s", "n", s) : Ui.trf("{n} min", "n", s / 60))
                        .step(dir -> {
                            int at = 0, n = Prefs.SAVER_SECONDS.length;
                            for (int i = 0; i < n; i++) if (Prefs.SAVER_SECONDS[i] == Prefs.saverSeconds()) at = i;
                            Prefs.setSaverSeconds(Prefs.SAVER_SECONDS[(at + dir + n) % n]);
                            host.refresh();
                        }));
                String motion = Prefs.saverMotion();
                out.add(new Opt(Ui.tr("Motion")).value(motion.equals("still") ? Ui.tr("None") : motion.equals("zoom") ? Ui.tr("Zoom") : Ui.tr("Pan and zoom"))
                        .hint(Ui.tr("Pan and zoom drifts toward a corner; every change is a crossfade."))
                        .step(dir -> {
                            int n = Prefs.SAVER_MOTIONS.length, at = Math.max(0, Arrays.asList(Prefs.SAVER_MOTIONS).indexOf(Prefs.saverMotion()));
                            Prefs.setSaverMotion(Prefs.SAVER_MOTIONS[(at + dir + n) % n]);
                            host.refresh();
                        }));
                out.add(new Opt(Ui.tr("Start after")).value(saverStartText(c))
                        .hint(Ui.tr("The TV's own setting; the TV still turns off when its sleep time comes."))
                        .step(dir -> stepSaverStart(host, dir)));
                out.add(new Opt(Ui.tr("Preview")).chevron().ok(host::preview));
                for (Opt o : out) if (o.step != null && o.ok == null) { IntConsumer st = o.step; o.ok(() -> st.accept(1)); }
                return out;
            }
        };
    }

    // "Start after": the TV's screen-off timeout (the screensaver starts then), written through ADB.
    static final int[] SAVER_MINUTES = {2, 5, 10, 15, 20, 30};
    private static int saverStartPending = -1;

    private static String saverStartText(Context c) {
        int m = saverStartPending > 0 ? saverStartPending : saverStartMinutes(c);
        return m > 0 ? Ui.trf("{n} min", "n", m) : "—";
    }

    static int saverStartMinutes(Context c) {
        int ms = android.provider.Settings.System.getInt(c.getContentResolver(), android.provider.Settings.System.SCREEN_OFF_TIMEOUT, -1);
        return ms > 0 ? Math.round(ms / 60_000f) : -1;
    }

    /** Only choices shorter than the TV's own sleep timer: past it, the TV would turn off before the screensaver. */
    private static void stepSaverStart(Host host, int dir) {
        Context app = host.activity().getApplicationContext();
        int sleep = android.provider.Settings.Secure.getInt(app.getContentResolver(), "sleep_timeout", -1);
        List<Integer> ok = new ArrayList<>();
        for (int m : SAVER_MINUTES) if (sleep <= 0 || m * 60_000 < sleep) ok.add(m);
        if (ok.isEmpty()) return;
        int now = saverStartPending > 0 ? saverStartPending : saverStartMinutes(app);
        int at = ok.indexOf(now);
        int next = at < 0 ? ok.get(dir > 0 ? 0 : ok.size() - 1) : ok.get((at + dir + ok.size()) % ok.size());
        saverStartPending = next;
        host.refresh();
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(() -> {
            try (Adb adb = Adb.connect(app, 10_000, null)) {
                adb.shell("settings put system screen_off_timeout " + next * 60_000);
            } catch (java.io.IOException e) {
                AppLog.w("screensaver start: " + e.getMessage());
            }
            main.post(() -> {
                saverStartPending = -1;
                host.refresh();
            });
        }).start();
    }

    // MARK: - About

    static Page about(Host host) {
        return new Page() {
            @Override String title() { return Ui.tr("About"); }

            @Override List<Opt> rows() {
                List<Opt> out = new ArrayList<>();
                String version = "";
                try {
                    version = host.activity().getPackageManager().getPackageInfo(host.activity().getPackageName(), 0).versionName;
                } catch (Exception ignored) {
                }
                out.add(new Opt(Brand.NAME).value(version).dim()
                        .hint(Ui.tr("A cinematic home screen for your TV, made by EndGrid Labs.")));
                out.add(new Opt(Ui.tr("Made by")).value("EndGrid Labs").dim()
                        .hint(Ui.tr("Open, privacy-first software for the screens at home: endgrid.in")));
                out.add(new Opt(Ui.tr("Website")).value("endgrid.in").dim()
                        .hint(Ui.tr("News, help and other projects from EndGrid Labs.")));
                out.add(new Opt(Ui.tr("Source code")).value("github.com/endgridlabs/iQTV").dim()
                        .hint(Ui.tr("Contributions: CONTRIBUTING.md in the source.")));
                out.add(new Opt(Ui.tr("License")).value("MIT").dim()
                        .hint(Ui.tr("Open source under the MIT license. See LICENSE.")));
                out.add(new Opt(Ui.tr("Titles and availability")).value("TMDB").dim()
                        .hint(Ui.tr("This product uses the TMDB API but is not endorsed or certified by TMDB.")));
                out.add(new Opt(Ui.tr("Weather")).value("Open-Meteo").dim().hint(Ui.tr("Weather data by Open-Meteo.com (CC BY 4.0).")));
                out.add(new Opt(Ui.tr("Title links")).value(Config.WATCHMODE_KEY.trim().isEmpty() ? Ui.tr("Off") : "Watchmode").dim()
                        .hint(Ui.tr("Optional: a free Watchmode key opens a title's own page in its app.")));
                out.add(new Opt(Ui.tr("Privacy")).value(Ui.tr("On this TV only")).dim()
                        .hint(Ui.tr("No accounts or analytics. Settings, watch history and photos never leave the TV.")));
                out.add(new Opt(Ui.tr("Limits")).value(Ui.tr("See PRIVACY.md")).dim()
                        .hint(Ui.tr("Watch history only from apps that report it; no Apple Photos on Android TV.")));
                return out;
            }

            @Override String hint() { return ""; }
        };
    }
}
