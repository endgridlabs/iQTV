/*
 * What EndGrid iQTV+ shows on LG and Samsung TVs, as the Android app's Recs does it:
 *  - the TV's apps (TV.apps), Your Apps;
 *  - the rows from TMDB, with the person's own free key: Trending Now, Popular and Latest movies, TV Shows and Web
 *    Series in their content languages, New Releases. A title shows only when TMDB's watch-provider data lists it as
 *    streaming by subscription in their region, on an app this TV has. Nothing is shown on a guess.
 *  - search (TMDB's multi search), More Info's details, "Not for me".
 * Kept in localStorage so the screen opens with its rows; fetched again every 6 hours. Settings changed on the TV
 * (Prefs) win over config.properties (CONFIG, written by the build), as on Android.
 * Plain ES5 for the Chromium of older TVs.
 */
var CONFIG = window.CONFIG || {};

var Prefs = (function () {
  'use strict';
  var KEY = 'iqtv.prefs';
  var v = {};
  try { v = JSON.parse(localStorage.getItem(KEY)) || {}; } catch (e) {}
  function save() { try { localStorage.setItem(KEY, JSON.stringify(v)); } catch (e) {} }
  function pick(name, fallback) { return v[name] === undefined || v[name] === null ? fallback : v[name]; }

  /** The TV's country from its language ("en-IN" → IN), for a build without a region. */
  function tvCountry() {
    var m = /[-_]([A-Za-z]{2})\b/.exec(navigator.language || '');
    return m ? m[1].toUpperCase() : 'US';
  }

  return {
    tmdbKey: function () { return String(pick('tmdbKey', CONFIG.tmdbKey || '')).replace(/^\s+|\s+$/g, ''); },
    region: function () { return String(pick('region', CONFIG.tmdbRegion || '') || tvCountry()).toUpperCase(); },
    /** TMDB's two-letter codes of the languages the rows are in, ["ta", "te"]; empty = any. */
    contentLanguages: function () {
      var s = String(pick('contentLanguage', CONFIG.contentLanguage || '')).toLowerCase();
      var out = [], parts = s.split(/[\s,|]+/);
      for (var i = 0; i < parts.length; i++) if (/^[a-z]{2}$/.test(parts[i]) && out.indexOf(parts[i]) < 0) out.push(parts[i]);
      return out;
    },
    viewerName: function () { return String(pick('viewerName', CONFIG.viewerName || '')).replace(/^\s+|\s+$/g, ''); },
    language: function () { return pick('language', CONFIG.language || 'auto'); },
    hidden: function () { return pick('hidden', []); },
    /** What the person typed on the TV, as it is (for the Settings fields). */
    raw: function (name) { return v[name]; },
    set: function (name, value) { v[name] = value; save(); }
  };
})();

var Data = (function () {
  'use strict';

  function store(key, value) { try { localStorage.setItem('iqtv.' + key, JSON.stringify(value)); } catch (e) {} }
  function stored(key) { try { return JSON.parse(localStorage.getItem('iqtv.' + key)); } catch (e) { return null; } }
  function trim(s) { return (s || '').replace(/^\s+|\s+$/g, ''); }

  /** GET a JSON document; done(error, json). */
  function get(url, headers, done) {
    var x = new XMLHttpRequest();
    var over = false;
    x.open('GET', url, true);
    x.timeout = 12000;
    for (var h in headers) if (headers.hasOwnProperty(h)) x.setRequestHeader(h, headers[h]);
    x.onreadystatechange = function () {
      if (x.readyState !== 4 || over) return;
      over = true;
      if (x.status !== 200) { done(new Error(x.status === 401 ? 'TMDB rejected the key' : 'TMDB returned HTTP ' + x.status)); return; }
      var json;
      try { json = JSON.parse(x.responseText); } catch (e) { done(e); return; }
      done(null, json);
    };
    x.ontimeout = x.onerror = function () { if (!over) { over = true; done(new Error('no connection')); } };
    x.send();
  }

  var listeners = [];
  function changed() { for (var i = 0; i < listeners.length; i++) listeners[i](); }

  // MARK: - The TV's apps

  var installed = [], appsLoaded = false;

  function loadApps() {
    TV.apps(function (list) {
      installed = list || [];
      appsLoaded = true;
      changed();
      refresh();
    });
  }

  /**
   * The streaming services, as Providers.java: [part of TMDB's provider name (lowercase), key, the app's id on LG or
   * Samsung, the app's name on the TV, the service's name]. Short names match as whole words ("max" isn't "Maxdome").
   */
  var SERVICES = [
    ['netflix', 'netflix', /netflix/i, /netflix/i, 'Netflix'],
    ['prime video', 'prime', /^amazon$|amazon|primevideo/i, /prime video|amazon/i, 'Prime Video'],
    ['hotstar', 'hotstar', /hotstar/i, /hotstar/i, 'JioHotstar'],
    ['zee5', 'zee5', /zee5/i, /zee5|zee 5/i, 'ZEE5'],
    ['sony liv', 'sonyliv', /sonyliv/i, /sony ?liv/i, 'Sony LIV'],
    ['sun nxt', 'sunnxt', /sunnxt/i, /sun ?nxt/i, 'Sun NXT'],
    ['aha', 'aha', /ahaflix|\.aha\b/i, /^aha\b/i, 'aha'],
    ['disney', 'disney', /disney/i, /disney/i, 'Disney+'],
    ['apple tv', 'appletv', /appletv|apple\.tv/i, /apple tv/i, 'Apple TV'],
    ['max', 'max', /hbo|wbd|\.max\b/i, /^(hbo )?max$|hbo max/i, 'Max'],
    ['hulu', 'hulu', /hulu/i, /hulu/i, 'Hulu'],
    ['paramount', 'paramount', /paramount|cbs/i, /paramount/i, 'Paramount+'],
    ['peacock', 'peacock', /peacock/i, /peacock/i, 'Peacock'],
    ['globoplay', 'globoplay', /globoplay/i, /globoplay/i, 'Globoplay'],
    ['crunchyroll', 'crunchyroll', /crunchyroll/i, /crunchyroll/i, 'Crunchyroll']
  ];

  function hiddenServices() {
    return (CONFIG.hideServices || '').toLowerCase().split(/[\s,]+/);
  }

  /** The installed app for a service key, or null (and null for services left out in config.properties). */
  function appFor(key) {
    if (!key || hiddenServices().indexOf(key) >= 0) return null;
    for (var s = 0; s < SERVICES.length; s++) {
      if (SERVICES[s][1] !== key) continue;
      for (var i = 0; i < installed.length; i++) {
        if (SERVICES[s][2].test(installed[i].id) || SERVICES[s][3].test(installed[i].title)) return installed[i];
      }
    }
    return null;
  }

  /** "… Amazon Channel" is sold inside another app; Apple TV is only the streaming service (350; 2 is the store). */
  function service(providerName, providerId) {
    var n = (providerName || '').toLowerCase();
    if (n.indexOf(' channel') >= 0) return null;
    for (var i = 0; i < SERVICES.length; i++) {
      var s = SERVICES[i];
      if (s[1] === 'appletv' && providerId !== 350) continue;
      if (s[0].length <= 4 ? (' ' + n + ' ').indexOf(' ' + s[0] + ' ') >= 0 : n.indexOf(s[0]) >= 0) return s;
    }
    return null;
  }

  function serviceName(key) {
    for (var i = 0; i < SERVICES.length; i++) if (SERVICES[i][1] === key) return SERVICES[i][4];
    return '';
  }

  // MARK: - TMDB

  var API = 'https://api.themoviedb.org/3';
  var IMAGES = 'https://image.tmdb.org/t/p/';
  var ANIMATION = 16;   // TMDB's Animation genre, left out with hide_animation=true
  var LIST_EVERY = 6 * 3600000;
  var CACHE_VERSION = 1;

  function key() { return Prefs.tmdbKey(); }

  function tmdb(path, done) {
    var k = key(), bearer = k.indexOf('eyJ') === 0;   // read access token (v4) or API key (v3)
    var url = API + path + (bearer ? '' : (path.indexOf('?') >= 0 ? '&' : '?') + 'api_key=' + encodeURIComponent(k));
    get(url, bearer ? {Authorization: 'Bearer ' + k, Accept: 'application/json'} : {Accept: 'application/json'}, done);
  }

  /** TMDB's language, as on Android: Latin American Spanish unless the TV is in Spain. */
  function lang() {
    if (I18N.lang === 'en') return 'en-US';
    if (I18N.lang === 'es') return /-ES\b/i.test(navigator.language || '') ? 'es-ES' : 'es-MX';
    return I18N.locale();
  }

  function day(offset) {
    var d = new Date(Date.now() + offset * 86400000);
    function two(n) { return (n < 10 ? '0' : '') + n; }
    return d.getFullYear() + '-' + two(d.getMonth() + 1) + '-' + two(d.getDate());
  }

  /** The content languages' names, for the rows ("Popular Tamil Movies"): the first one's, in the TV's language. */
  var LANGUAGES = {ta: 'Tamil', te: 'Telugu', ml: 'Malayalam', kn: 'Kannada', hi: 'Hindi', bn: 'Bengali', mr: 'Marathi',
    pa: 'Punjabi', gu: 'Gujarati', ko: 'Korean', ja: 'Japanese', zh: 'Chinese', en: 'English', es: 'Spanish',
    fr: 'French', de: 'German', it: 'Italian', pt: 'Portuguese', ru: 'Russian', tr: 'Turkish', th: 'Thai'};

  function languageName(code) {
    if (!code) return '';
    try {
      var own = new Intl.DisplayNames([I18N.locale()], {type: 'language'}).of(code);
      if (own && own !== code) return own.charAt(0).toUpperCase() + own.substring(1);
    } catch (e) { /* older Chromium: no Intl.DisplayNames */ }
    return LANGUAGES[code] || code;
  }

  function rowName(id) {
    var l = languageName(Prefs.contentLanguages()[0]);
    switch (id) {
      case 'trending': return tr('Trending Now');
      case 'popular': return l ? trf('Popular {language} Movies', {language: l}) : tr('Popular Movies');
      case 'latest': return l ? trf('Latest {language} Movies', {language: l}) : tr('Latest Movies');
      case 'shows': return l ? trf('{language} TV Shows', {language: l}) : tr('TV Shows');
      case 'web': return l ? trf('{language} Web Series', {language: l}) : tr('Web Series');
      case 'new': return tr('New Releases');
      default: return id;
    }
  }

  var ROWS = ['trending', 'popular', 'latest', 'shows', 'web', 'new'];

  /** A pick from a TMDB list item, or null without a picture. */
  function item(o, kind) {
    var backdrop = o.backdrop_path;
    if (!backdrop) return null;
    var genres = o.genre_ids || [];
    if (CONFIG.hideAnimation && genres.indexOf(ANIMATION) >= 0) return null;
    return {kind: kind, id: o.id, title: o[kind === 'movie' ? 'title' : 'name'] || '', backdrop: backdrop,
      poster: o.poster_path || '', lang: o.original_language || '', logo: '', overview: '', year: '', meta: '',
      app: '', web: false, detailed: false};
  }

  function pickKey(p) { return p.kind + ':' + p.id; }

  var cache = stored('rows') || {};
  if (cache.version !== CACHE_VERSION) cache = {};
  var picks = cache.picks || {}, sections = cache.sections || {}, listAt = cache.listAt || 0, stamp = cache.stamp || '';
  var fetching = false, failedAt = 0, error = '';

  function save() {
    store('rows', {version: CACHE_VERSION, picks: picks, sections: sections, listAt: listAt, stamp: stamp});
  }

  /** What the saved rows were made for: the key, region, languages, the TV's language and its services. */
  function sourceStamp() {
    var k = key(), s = k.length + ':' + k.slice(-6) + ':' + Prefs.region() + ':' + Prefs.contentLanguages().join('|') + ':'
        + lang() + (CONFIG.hideAnimation ? ':no-animation' : '') + ':';
    for (var i = 0; i < SERVICES.length; i++) s += appFor(SERVICES[i][1]) ? '1' : '0';
    return s;
  }

  /** Runs `jobs` (functions taking a done callback) `width` at a time; then all(). */
  function parallel(jobs, width, all) {
    var next = 0, running = 0, finished = 0;
    if (!jobs.length) { all(); return; }
    function start() {
      while (running < width && next < jobs.length) {
        running++;
        jobs[next++](function () {
          running--;
          finished++;
          if (finished === jobs.length) all(); else start();
        });
      }
    }
    start();
  }

  /** Genre, length (or seasons), logo art, description and the service it's on, among the ones the TV has. */
  function details(p, done) {
    var base = I18N.base;
    tmdb('/' + p.kind + '/' + p.id + '?language=' + lang() + '&append_to_response=watch/providers,images'
        + '&include_image_language=null,' + base + ',en' + (p.lang ? ',' + p.lang : ''), function (err, o) {
      if (err || !o) { done(); return; }
      p.title = o[p.kind === 'movie' ? 'title' : 'name'] || p.title;
      p.logo = bestLogo(o.images, p.lang);
      if (o.poster_path) p.poster = o.poster_path;
      p.overview = trim(o.overview);
      var date = o[p.kind === 'movie' ? 'release_date' : 'first_air_date'] || '';
      p.year = date.length >= 4 ? date.substring(0, 4) : '';
      var parts = [];
      if (p.kind === 'tv') parts.push(tr('Series'));
      if (o.genres && o.genres.length) parts.push(genre(o.genres[0].name));
      if (p.kind === 'movie') {
        var minutes = o.runtime || 0;
        if (minutes > 0) parts.push(minutes >= 60 ? trf('{h}h {m}m', {h: Math.floor(minutes / 60), m: minutes % 60}) : trf('{m}m', {m: minutes}));
      } else {
        if (o.number_of_seasons > 0) parts.push(plural('plural|one:{n} season|other:{n} seasons', o.number_of_seasons));
        p.web = streamingOriginal(o.networks);
      }
      var wp = o['watch/providers'], here = wp && wp.results ? wp.results[Prefs.region()] : null;
      var flat = here && here.flatrate ? here.flatrate : [];
      p.app = '';
      for (var i = 0; i < flat.length && !p.app; i++) {
        var s = service(flat[i].provider_name, flat[i].provider_id);
        if (s && appFor(s[1])) p.app = s[1];
      }
      if (p.app) parts.push(serviceName(p.app));
      p.meta = parts.join(' · ');
      p.detailed = true;
      done();
    });
  }

  /** TV genres that TMDB returns in English even in other languages. */
  function genre(name) {
    switch (name) {
      case 'Action & Adventure': return tr('genre|Action & Adventure');
      case 'Sci-Fi & Fantasy': return tr('genre|Sci-Fi & Fantasy');
      case 'War & Politics': return tr('genre|War & Politics');
      default: return name;
    }
  }

  /** TMDB's networks of a series: a streaming service among them makes it a web series (not a TV channel's show). */
  function streamingOriginal(networks) {
    var names = ['netflix', 'prime video', 'amazon', 'hotstar', 'disney', 'zee5', 'sonyliv', 'sony liv', 'aha', 'sun nxt',
      'sunnxt', 'mx player', 'jiocinema', 'voot', 'alt balaji', 'hoichoi', 'apple tv', 'airtel'];
    for (var i = 0; networks && i < networks.length; i++) {
      var n = (networks[i].name || '').toLowerCase();
      for (var j = 0; j < names.length; j++) if (n.indexOf(names[j]) >= 0) return true;
    }
    return false;
  }

  /** The title's logo art: in the TV's language, else the title's own, else English, else any; the best rated. */
  function bestLogo(images, original) {
    var logos = images && images.logos ? images.logos : [];
    var order = [I18N.base, original, 'en', null], best = null;
    for (var o = 0; o < order.length && !best; o++) {
      for (var i = 0; i < logos.length; i++) {
        var l = logos[i];
        if (/\.svg$/i.test(l.file_path || '')) continue;   // old TVs draw TMDB's SVGs badly
        if (order[o] !== null && l.iso_639_1 !== order[o]) continue;
        if (!best || (l.vote_average || 0) > (best.vote_average || 0)) best = l;
      }
    }
    return best ? best.file_path : '';
  }

  /** Up to `pages` pages of a TMDB list into `list` (keys), the picks into `all`; then done(error). */
  function collect(all, list, kind, path, pages, done) {
    var page = 1;
    (function one() {
      tmdb(path + '&page=' + page, function (err, r) {
        if (err) { done(err); return; }
        var results = r.results || [];
        for (var i = 0; i < results.length; i++) {
          var k = results[i].media_type || kind;
          if (k !== 'movie' && k !== 'tv') continue;
          var p = item(results[i], k);
          if (!p) continue;
          var id = pickKey(p);
          if (!all[id]) all[id] = p;
          if (list.indexOf(id) < 0) list.push(id);
        }
        if (++page > pages || page > (r.total_pages || 1)) done(null); else one();
      });
    })();
  }

  /** Fetches every row from TMDB, then each title's details (four at a time). */
  function fetchRows(done) {
    var region = Prefs.region();
    var langs = Prefs.contentLanguages().join('|');
    var providers = [], found = {};
    var lists = ['/watch/providers/movie', '/watch/providers/tv'];
    var left = lists.length, failed = null;
    for (var l = 0; l < lists.length; l++) {
      tmdb(lists[l] + '?language=' + lang() + '&watch_region=' + region, function (err, r) {
        if (err) failed = err;
        var results = r && r.results ? r.results : [];
        for (var i = 0; i < results.length; i++) {
          var s = service(results[i].provider_name, results[i].provider_id);
          if (s && appFor(s[1]) && !found[results[i].provider_id]) {
            found[results[i].provider_id] = true;
            providers.push(results[i].provider_id);
          }
        }
        if (--left === 0) withProviders();
      });
    }

    function withProviders() {
      if (failed) { done(failed); return; }
      if (!providers.length) {
        done(new Error(trf("TMDB lists none of this TV's streaming apps in {region}.", {region: region})));
        return;
      }
      var on = '&language=' + lang() + '&watch_region=' + region + '&with_watch_monetization_types=flatrate&include_adult=false'
          + '&with_watch_providers=' + encodeURIComponent(providers.join('|')) + (CONFIG.hideAnimation ? '&without_genres=' + ANIMATION : '');
      var inLangs = langs ? '&with_original_language=' + encodeURIComponent(langs) : '';
      var votes = langs ? 3 : 50;
      var all = {}, keys = {popular: [], latest: [], series: [], trending: [], 'new': []};
      var steps = [
        function (next) { collect(all, keys.popular, 'movie', '/discover/movie?sort_by=popularity.desc&vote_count.gte=' + votes + on + inLangs, 2, next); },
        function (next) {
          collect(all, keys.latest, 'movie', '/discover/movie?sort_by=primary_release_date.desc&primary_release_date.lte=' + day(0)
              + '&primary_release_date.gte=' + day(-240) + on + inLangs, 1, next);
        },
        function (next) { collect(all, keys.series, 'tv', '/discover/tv?sort_by=popularity.desc' + on + inLangs, 2, next); },
        function (next) {
          // In your languages, what's popular in them stands in for the worldwide trending list.
          if (langs) collect(all, keys.trending, 'movie', '/discover/movie?sort_by=popularity.desc&primary_release_date.gte=' + day(-120) + on + inLangs, 1, next);
          else collect(all, keys.trending, 'all', '/trending/all/week?language=' + lang(), 1, next);
        },
        function (next) {
          collect(all, keys['new'], 'movie', '/discover/movie?sort_by=popularity.desc&vote_count.gte=5&primary_release_date.lte=' + day(0)
              + '&primary_release_date.gte=' + day(-75) + on + inLangs, 1, function (err) {
            if (err) { next(err); return; }
            collect(all, keys['new'], 'tv', '/discover/tv?sort_by=popularity.desc&first_air_date.lte=' + day(0)
                + '&first_air_date.gte=' + day(-75) + on + inLangs, 1, next);
          });
        }
      ];
      var i = 0;
      (function step(err) {
        if (err) { done(err); return; }
        if (i < steps.length) { steps[i++](step); return; }
        // Details: only for titles the saved list doesn't have yet (in this language).
        var jobs = [];
        for (var id in all) {
          if (!all.hasOwnProperty(id)) continue;
          if (picks[id] && picks[id].detailed && picks[id].lang === all[id].lang && stamp === sourceStamp()) { all[id] = picks[id]; continue; }
          jobs.push((function (p) { return function (next) { details(p, next); }; })(all[id]));
        }
        parallel(jobs, 4, function () {
          var shows = [], web = [];
          for (var s = 0; s < keys.series.length; s++) {
            var p = all[keys.series[s]];
            if (p) (p.web ? web : shows).push(keys.series[s]);
          }
          picks = all;
          sections = {trending: keys.trending, popular: keys.popular, latest: keys.latest, shows: shows, web: web, 'new': keys['new']};
          done(null);
        });
      })();
    }
  }

  function refresh(force) {
    if (!appsLoaded || fetching || !key()) return;
    var now = Date.now();
    var stale = stamp !== sourceStamp() || now - listAt > LIST_EVERY || now < listAt;
    if (!force && !stale) return;
    if (!force && failedAt && now - failedAt < 5 * 60000) return;
    fetching = true;
    error = '';
    changed();
    fetchRows(function (err) {
      fetching = false;
      if (err) {
        failedAt = Date.now();
        error = err.message || String(err);
      } else {
        failedAt = 0;
        listAt = Date.now();
        stamp = sourceStamp();
        save();
      }
      changed();
    });
  }

  function isHidden(p) { return Prefs.hidden().indexOf(pickKey(p)) >= 0; }

  /** The rows, each with only what streams on an app this TV has; `kind` keeps only movies or shows. */
  function rows(kind) {
    var out = [];
    if (!key()) return out;
    for (var r = 0; r < ROWS.length; r++) {
      var ids = sections[ROWS[r]] || [], items = [];
      for (var i = 0; i < ids.length && items.length < 20; i++) {
        var p = picks[ids[i]];
        if (!p || !p.detailed || !appFor(p.app) || isHidden(p)) continue;
        if (kind && p.kind !== kind) continue;
        items.push(p);
      }
      if (items.length) out.push({id: ROWS[r], title: rowName(ROWS[r]), items: items, poster: ROWS[r] === 'popular' || ROWS[r] === 'latest'});
    }
    return out;
  }

  // MARK: - Search

  var searchAt = 0;

  /** TMDB's multi search: titles (with their details), and the titles of people found; done(results or null). */
  function search(query, done) {
    var at = ++searchAt;
    tmdb('/search/multi?include_adult=false&language=' + lang() + '&query=' + encodeURIComponent(query), function (err, r) {
      if (at !== searchAt) return;
      if (err) { error = err.message; done(null); return; }
      var found = [], seen = {};
      function add(o, kind) {
        var p = item(o, kind);
        if (!p || seen[pickKey(p)] || found.length >= 12) return;
        seen[pickKey(p)] = true;
        found.push(picks[pickKey(p)] && picks[pickKey(p)].detailed ? picks[pickKey(p)] : p);
      }
      var results = r.results || [];
      for (var i = 0; i < results.length; i++) {
        var o = results[i];
        if (o.media_type === 'movie' || o.media_type === 'tv') add(o, o.media_type);
        else if (o.media_type === 'person') for (var j = 0; o.known_for && j < o.known_for.length; j++) add(o.known_for[j], o.known_for[j].media_type);
      }
      var jobs = [];
      for (var k = 0; k < found.length; k++) {
        if (!found[k].detailed) jobs.push((function (p) { return function (next) { details(p, next); }; })(found[k]));
      }
      parallel(jobs, 4, function () { if (at === searchAt) done(found); });
    });
  }

  // MARK: - The rest

  loadApps();
  setInterval(function () { refresh(false); }, 10 * 60000);

  return {
    onChange: function (f) { listeners.push(f); },
    apps: function () { return installed; },
    appsLoaded: function () { return appsLoaded; },
    rows: rows,
    search: search,
    hasKey: function () { return !!key(); },
    loading: function () { return fetching; },
    error: function () { return error; },
    region: function () { return Prefs.region(); },
    /** The TV's app for a title, or null. */
    appOf: function (p) { return p ? appFor(p.app) : null; },
    serviceName: function (p) { return p ? serviceName(p.app) : ''; },
    languageName: languageName,
    image: function (path, size) { return path ? IMAGES + size + path : ''; },
    key: pickKey,
    /** "Not for me": the title leaves the rows. */
    hide: function (p) {
      var h = Prefs.hidden().slice();
      if (h.indexOf(pickKey(p)) < 0) h.push(pickKey(p));
      if (h.length > 400) h = h.slice(h.length - 400);
      Prefs.set('hidden', h);
      changed();
    },
    /** After Settings changed the key, region or languages: everything again. */
    reload: function () {
      failedAt = 0;
      stamp = '';
      changed();
      refresh(true);
    }
  };
})();
