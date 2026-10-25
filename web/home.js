/*
 * EndGrid iQTV+ on LG (webOS) and Samsung (Tizen), with the Android app's Top Shelf design (HomeView):
 *  - the top bar: the iQTV+ wordmark, the glass navigation (Home, Movies, TV Shows, Apps, Search, Settings), your
 *    initial and the time;
 *  - the hero: the featured title's photo fills the screen, with its logo art (or name), year • language • genre •
 *    length • service, two lines of description, Open / More Info / Not for me and the carousel's dots; it moves on to
 *    the next featured title every few seconds while the focus is at the top;
 *  - below, the stack: Your Apps, then the rows from TMDB in your languages; the focused row rises to where the first
 *    one rests and the hero fades out above it, as on tvOS;
 *  - All Apps (with the TV's inputs), Search and Settings as sheets over the screen; More Info over the dimmed screen.
 * The remote's arrows, OK and Back move the focus; a pointer (LG's Magic Remote) focuses what it points at.
 * On these TVs it's an app, not the home screen: the TV's Home button still opens the TV's own.
 * Plain ES5 for the Chromium of older TVs.
 */
(function () {
  'use strict';

  function $(id) { return document.getElementById(id); }

  function el(tag, cls, parent, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined) e.textContent = text;
    if (parent) parent.appendChild(e);
    return e;
  }

  function sym(name, parent, size) {
    var s = el('span', 'sym', parent);
    s.style.webkitMaskImage = 'url(symbols/' + name + '.png)';
    s.style.maskImage = 'url(symbols/' + name + '.png)';
    if (size) { s.style.width = size + 'px'; s.style.height = size + 'px'; }
    return s;
  }

  function hasClass(e, c) { return (' ' + e.className + ' ').indexOf(' ' + c + ' ') >= 0; }
  function setClass(e, c, on) {
    if (!e) return;
    if (on && !hasClass(e, c)) e.className = (e.className + ' ' + c).replace(/^\s+/, '');
    else if (!on && hasClass(e, c)) e.className = (' ' + e.className + ' ').replace(' ' + c + ' ', ' ').replace(/^\s+|\s+$/g, '');
  }
  function translate(e, x, y) { e.style.webkitTransform = e.style.transform = 'translate(' + x + 'px,' + y + 'px)'; }
  function clamp(v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

  var stage = $('stage'), body = document.body;

  /** The 1920 × 1080 stage fills the window (on the TV it already is 1920 × 1080). */
  function fit() {
    var s = Math.min(window.innerWidth / 1920, window.innerHeight / 1080);
    stage.style.webkitTransform = stage.style.transform = s === 1 ? '' : 'scale(' + s + ')';
  }
  window.addEventListener('resize', fit);
  fit();
  I18N.set(Prefs.language());

  // The Top Shelf's measurements (HomeView's H_ constants), in the 1920 × 1080 layout.
  var MARGIN = 80, CONTENT_R = 1840, ROWS_TOP = 762, CARD_DY = 46, CARD_W = 320, CARD_H = 180, POSTER_W = 190,
      POSTER_H = 285, CARD_GAP = 30, TEXT_H = 76, ROW_GAP = 40, TILE_W = 224, TILE_H = 126, TILE_GAP = 48,
      ROW_FADE_TOP = 150, ROW_FADE = 140, HERO_ROTATE_MS = 9000, HERO_FEATURED = 5, SETTLE_MS = 500;

  var TAB_HOME = 0, TAB_MOVIES = 1, TAB_SHOWS = 2, TAB_APPS = 3, TAB_SEARCH = 4, TAB_SETTINGS = 5;
  var TAB_SYMBOLS = ['home', 'play', 'tv', 'apps', 'search', 'settings'];
  var ACT_OPEN = 0, ACT_INFO = 1, ACT_HIDE = 2;

  var tab = TAB_HOME, zone = 'actions', navIndex = 0, actionIndex = 0, stackIndex = 0;
  var overlay = null;                 // 'info', 'apps', 'search', 'settings'
  var stack = [];                     // {type: 'apps' | 'row' | 'note', ...}
  var rowState = {};                  // each row's focus and scroll, by id, kept while its titles change
  var searchRow = null;               // {id: 'search', title, items}
  var featured = [], heroIndex = 0, pick = null;
  var stackY = 0;

  // MARK: - Top bar

  function tabNames() {
    return [tr('tab|Home'), tr('Movies'), tr('TV Shows'), tr('Apps'), tr('Search'), tr('light|Settings')];
  }

  function buildNav() {
    var nav = $('nav');
    nav.innerHTML = '';
    var names = tabNames();
    for (var i = 0; i < names.length; i++) {
      var t = el('div', 'tab', nav);
      t.setAttribute('data-z', 'nav');
      t.setAttribute('data-a', i);
      sym(TAB_SYMBOLS[i], t, 24);
      el('span', '', t, names[i]);
    }
  }

  function updateCorner() {
    var name = Prefs.viewerName(), avatar = $('avatar');
    avatar.textContent = name ? name.charAt(0).toUpperCase() : '';
    setClass(avatar, 'on', !!name);
    var now = new Date(), time;
    try { time = now.toLocaleTimeString(I18N.locale(), {hour: 'numeric', minute: '2-digit'}); } catch (e) { time = now.toTimeString().slice(0, 5); }
    $('clock').textContent = time;
    $('clockHero').querySelector('.time').textContent = time;
    var date;
    try { date = now.toLocaleDateString(I18N.locale(), {weekday: 'long', month: 'long', day: 'numeric'}); } catch (e) { date = now.toDateString(); }
    $('clockHero').querySelector('.date').textContent = date;
  }

  // MARK: - The stack: Your Apps, the search's results, the rows

  function cardW(row) { return row.poster ? POSTER_W : CARD_W; }
  function cardH(row) { return row.poster ? POSTER_H : CARD_H; }

  function pitch(item) {
    if (item.type === 'apps') return CARD_DY + TILE_H + 60 + ROW_GAP;
    if (item.type === 'note') return CARD_DY + 120;
    return CARD_DY + cardH(item) + TEXT_H + ROW_GAP;
  }

  function stackTop(i) {
    var y = ROWS_TOP;
    for (var r = 0; r < i; r++) y += pitch(stack[r]);
    return y;
  }

  function focusable(i) { return stack[i] && stack[i].type !== 'note'; }

  function kind() { return tab === TAB_MOVIES ? 'movie' : tab === TAB_SHOWS ? 'tv' : null; }

  /** The stack from the TV's apps and Data's rows; each row keeps its place (rowState). */
  function buildStack() {
    var focusedId = stack[stackIndex] ? stack[stackIndex].id : null;
    stack = [];
    var apps = Data.apps();
    if (apps.length) stack.push({type: 'apps', id: 'apps', items: apps});
    if (searchRow) stack.push({type: 'row', id: 'search', title: searchRow.title, items: searchRow.items, poster: false});
    var rows = Data.rows(kind());
    for (var i = 0; i < rows.length; i++) stack.push({type: 'row', id: rows[i].id, title: rows[i].title, items: rows[i].items, poster: rows[i].poster});
    var note = statusNote(rows.length);
    if (note) stack.push({type: 'note', id: 'note', text: note});
    for (var s = 0; s < stack.length; s++) {
      var st = rowState[stack[s].id] || (rowState[stack[s].id] = {index: 0, first: 0});
      if (stack[s].items) st.index = Math.min(st.index, Math.max(0, stack[s].items.length - 1));
    }
    stackIndex = 0;
    for (var j = 0; j < stack.length; j++) if (stack[j].id === focusedId) stackIndex = j;
    if (!focusable(stackIndex)) stackIndex = 0;
    renderStack();
    featured = makeFeatured();
    if (heroIndex >= featured.length) heroIndex = 0;
    if (zone === 'stack' && !focusable(stackIndex)) zone = 'nav';
    showTitle(false);
  }

  function statusNote(rows) {
    if (searchRow && searchRow.items.length) return '';
    if (!Data.hasKey()) return tr('Add your free TMDB key in Settings (themoviedb.org) to see what streams on your apps, in your languages.');
    if (Data.loading()) return tr("Loading what's streaming on your apps…");
    if (Data.error()) return Data.error();
    if (!rows) return tr('Nothing here yet: the picks load from TMDB in the background.');
    return '';
  }

  /** The titles the carousel goes through: the first ones with artwork of the most personal row there is. */
  function makeFeatured() {
    var order = ['popular', 'latest', 'web', 'shows', 'trending', 'new'];
    for (var o = 0; o < order.length; o++) {
      for (var i = 0; i < stack.length; i++) {
        if (stack[i].id !== order[o]) continue;
        var out = [];
        for (var k = 0; k < stack[i].items.length && out.length < HERO_FEATURED; k++) if (stack[i].items[k].backdrop) out.push(stack[i].items[k]);
        if (out.length) return out;
      }
    }
    return [];
  }

  function renderStack() {
    var root = $('stack');
    root.innerHTML = '';
    for (var i = 0; i < stack.length; i++) {
      var item = stack[i];
      var row = el('div', 'row', root);
      row.style.top = stackTop(i) + 'px';
      item.el = row;
      if (item.type === 'note') {
        el('div', 'note', row, item.text);
        continue;
      }
      el('h3', '', row, item.type === 'apps' ? tr('Your Apps') : item.title);
      var strip = el('div', 'strip', row);
      item.strip = strip;
      item.cells = [];
      for (var k = 0; k < item.items.length; k++) item.cells.push(item.type === 'apps' ? tile(strip, item.items[k], i, k) : card(strip, item, item.items[k], i, k));
    }
    layoutStack(true);
  }

  function card(parent, row, p, r, k) {
    var w = cardW(row), h = cardH(row);
    var c = el('div', 'card', parent);
    c.style.left = MARGIN + k * (w + CARD_GAP) + 'px';
    c.style.width = w + 'px';
    c.style.height = h + 'px';
    c.setAttribute('data-z', 'stack');
    c.setAttribute('data-a', r);
    c.setAttribute('data-b', k);
    var art = el('div', 'art', c);
    art.style.height = h + 'px';
    if (!row.poster && !p.backdrop) el('div', 'tag', art, p.title);
    var text = el('div', 'text', c);
    text.style.top = h + 12 + 'px';
    text.appendChild(document.createTextNode(p.title));
    var sub = [p.year, Data.serviceName(p) || (p.detailed ? tr('Not on your apps') : '')].filter(function (x) { return x; }).join(' · ');
    el('small', '', text, sub);
    return c;
  }

  function tile(parent, app, r, k) {
    var t = el('div', 'tile', parent);
    t.style.left = MARGIN + k * (TILE_W + TILE_GAP) + 'px';
    t.style.width = TILE_W + 'px';
    t.style.height = TILE_H + 'px';
    t.setAttribute('data-z', 'stack');
    t.setAttribute('data-a', r);
    t.setAttribute('data-b', k);
    appArt(t, app, TILE_H);
    el('div', 'name', t, app.title).style.top = TILE_H + 14 + 'px';
    return t;
  }

  /** An app's picture: its icon on its color, or its name on the color when there is no icon (or it doesn't load). */
  function appArt(parent, app, h) {
    var art = el('div', 'art', parent);
    art.style.height = h + 'px';
    art.style.backgroundColor = app.color || '#2C2C2E';
    function word() {
      art.innerHTML = '';
      var w = el('span', 'word', art, app.title);
      w.style.lineHeight = h + 'px';
    }
    if (app.symbol) {
      var s = sym(app.symbol, art, 64);
      s.style.marginTop = (h - 64) / 2 + 'px';
      return art;
    }
    if (!app.icon) { word(); return art; }
    var img = el('img', '', art);
    img.onerror = word;
    img.src = app.icon;
    return art;
  }

  /** Card art for the titles that show (and a few ahead), as the focus reaches them. */
  function loadArt(item) {
    if (item.type !== 'row' || !item.cells) return;
    var st = rowState[item.id], until = Math.min(item.items.length, st.first + 9);
    for (var k = 0; k < until; k++) {
      var cell = item.cells[k];
      if (cell.loaded) continue;
      cell.loaded = true;
      var p = item.items[k], url = item.poster ? Data.image(p.poster || p.backdrop, 'w342') : Data.image(p.backdrop, 'w300');
      if (url) cell.firstChild.style.backgroundImage = 'url(' + url + ')';
    }
  }

  /** Where each row scrolls to, which ones show, and the stack's own position. */
  function layoutStack(snap) {
    stackY = 0;
    if (zone === 'stack' && stackIndex > 0) {
      var fits = 1080 - pitch(stack[stackIndex]) + ROW_GAP - 24;
      stackY = Math.max(0, stackTop(stackIndex) - Math.min(ROWS_TOP, fits));
    }
    var root = $('stack');
    if (snap) root.style.webkitTransition = root.style.transition = 'none';
    translate(root, 0, -stackY);
    if (snap) {
      root.offsetWidth;   // apply the jump before the transition comes back
      root.style.webkitTransition = root.style.transition = '';
    }
    setClass(body, 'down', stackY > 110);
    for (var i = 0; i < stack.length; i++) {
      var item = stack[i], top = stackTop(i) - stackY;
      item.el.style.opacity = clamp((top - ROW_FADE_TOP) / ROW_FADE);
      item.el.style.visibility = top > 1080 + 40 || top + pitch(item) < 0 ? 'hidden' : '';
      if (!item.strip) continue;
      reveal(item);
      translate(item.strip, -rowState[item.id].first * step(item), 0);
      if (top < 1080) loadArt(item);
    }
  }

  function step(item) { return item.type === 'apps' ? TILE_W + TILE_GAP : cardW(item) + CARD_GAP; }

  /** Scrolls a row so its focused card shows, keeping one card of context at the left (as tvOS rows do). */
  function reveal(item) {
    var st = rowState[item.id], s = step(item);
    var fits = Math.max(1, Math.floor((CONTENT_R - MARGIN) / s)), first = st.first;
    if (st.index < first + 1) first = Math.max(0, st.index - 1);
    if (st.index > first + fits - 2) first = Math.max(0, st.index - fits + 2);
    st.first = Math.max(0, Math.min(first, Math.max(0, item.items.length - fits)));
  }

  // MARK: - The hero

  function actions() {
    var out = [];
    if (!pick) return out;
    if (Data.appOf(pick)) out.push(ACT_OPEN);
    out.push(ACT_INFO);
    if (!isSearchPick(pick)) out.push(ACT_HIDE);
    return out;
  }

  function isSearchPick(p) {
    if (!searchRow) return false;
    for (var i = 0; i < searchRow.items.length; i++) if (searchRow.items[i] === p) return true;
    return false;
  }

  function actionText(a) {
    if (a === ACT_INFO) return tr('More Info');
    if (a === ACT_HIDE) return tr('Not for me');
    var app = Data.serviceName(pick);
    return app ? trf('Open {app}', {app: app}) : tr('Open App');
  }

  /** "2024  •  Tamil  •  Action  •  2h 54m  •  Prime Video". */
  function heroMeta(p) {
    var parts = [];
    if (p.year) parts.push(p.year);
    if (p.lang) parts.push(Data.languageName(p.lang));
    var meta = (p.meta || '').split(' · ');
    for (var i = 0; i < meta.length; i++) if (meta[i] && parts.indexOf(meta[i]) < 0) parts.push(meta[i]);
    if (!Data.appOf(p) && p.detailed) parts.push(tr('Not on your apps'));
    return parts.join('  •  ');
  }

  var shownKey = '', textTimer = 0, photoTimer = 0, photoUrl = '', photoFront = 'photoA';

  /** The title on screen: the focused card's in the rows; at the top (or on Your Apps), the featured title. */
  function currentPick() {
    var item = stack[stackIndex];
    if (zone === 'stack' && item && item.type === 'row') return item.items[rowState[item.id].index] || null;
    if (featured.length) return featured[Math.min(heroIndex, featured.length - 1)];
    for (var i = 0; i < stack.length; i++) if (stack[i].type === 'row' && stack[i].items.length) return stack[i].items[0];
    return null;
  }

  function showTitle(animate) {
    pick = currentPick();
    var key = pick ? Data.key(pick) + '|' + pick.meta + '|' + pick.logo + '|' + (Data.appOf(pick) ? 1 : 0) : '';
    var text = $('heroText');
    setClass($('clockHero'), 'on', !pick);
    // Without a title: the time, and why there's none (no key yet, loading, TMDB's answer) where it can't be missed.
    if (!pick) $('clockHero').querySelector('.hint').textContent = statusNote(0) || tr('EndGrid iQTV+ by EndGrid Labs.');
    if (key !== shownKey) {
      shownKey = key;
      clearTimeout(textTimer);
      if (animate) {
        setClass(text, 'fading', true);
        textTimer = setTimeout(function () { fillHero(); setClass(text, 'fading', false); }, 130);
      } else {
        fillHero();
      }
    }
    if (actionIndex >= actions().length) actionIndex = Math.max(0, actions().length - 1);
    if (zone === 'actions' && !actions().length) zone = 'nav';
    renderActions();
    renderDots();
    clearTimeout(photoTimer);
    photoTimer = setTimeout(showPhoto, animate ? SETTLE_MS : 0);
  }

  function fillHero() {
    var text = $('heroText');
    text.style.visibility = pick ? '' : 'hidden';
    if (!pick) return;
    var p = pick, logo = text.querySelector('.logo'), name = text.querySelector('.name');
    text.querySelector('.label').textContent = Data.appOf(p) ? tr('Now Streaming') : '';
    name.textContent = p.title;
    name.style.visibility = '';
    setClass(logo, 'on', false);
    logo.onload = null;
    if (p.logo) {
      logo.onload = function () {
        if (pick !== p) return;
        setClass(logo, 'on', true);
        name.style.visibility = 'hidden';
      };
      logo.src = Data.image(p.logo, 'w500');
    } else {
      logo.removeAttribute('src');
    }
    text.querySelector('.meta').textContent = heroMeta(p);
    text.querySelector('.overview').textContent = p.overview || '';
  }

  function renderActions() {
    var box = $('actions');
    box.innerHTML = '';
    box.style.visibility = pick ? '' : 'hidden';
    var acts = actions();
    for (var i = 0; i < acts.length; i++) {
      var b = el('div', 'action' + (acts[i] === ACT_OPEN ? ' primary' : ''), box);
      b.setAttribute('data-z', 'actions');
      b.setAttribute('data-a', i);
      sym(acts[i] === ACT_INFO ? 'apps' : acts[i] === ACT_HIDE ? 'not_interested' : 'play', b, 26);
      el('span', '', b, actionText(acts[i]));
    }
    updateFocus();
  }

  function renderDots() {
    var dots = $('dots');
    dots.innerHTML = '';
    var at = -1;
    for (var i = 0; pick && i < featured.length; i++) if (featured[i] === pick) at = i;
    if (featured.length < 2 || at < 0) return;
    for (var k = 0; k < featured.length; k++) el('span', k === at ? 'on' : '', dots);
  }

  /** The title's photo behind everything, cross-fading from the last one once it has loaded. */
  function showPhoto() {
    var url = pick && pick.backdrop ? Data.image(pick.backdrop, 'w1280') : '';
    if (url === photoUrl || (pick && !pick.backdrop)) return;
    photoUrl = url;
    var back = photoFront === 'photoA' ? 'photoB' : 'photoA';
    if (!url) {
      setClass($('photoA'), 'on', false);
      setClass($('photoB'), 'on', false);
      return;
    }
    var img = new Image();
    img.onload = function () {
      if (photoUrl !== url) return;
      $(back).style.backgroundImage = 'url(' + url + ')';
      setClass($(back), 'on', true);
      setClass($(photoFront), 'on', false);
      photoFront = back;
    };
    img.src = url;
  }

  /** Every few seconds, while the focus is at the top and nothing is open, the next featured title cross-fades in. */
  setInterval(function () {
    if (overlay || zone === 'stack' || featured.length < 2 || document.hidden) return;
    heroIndex = (heroIndex + 1) % featured.length;
    showTitle(true);
  }, HERO_ROTATE_MS);

  // MARK: - Focus

  function updateFocus() {
    var tabs = $('nav').childNodes;
    for (var i = 0; i < tabs.length; i++) {
      setClass(tabs[i], 'chosen', i === tab);
      setClass(tabs[i], 'focus', !overlay && zone === 'nav' && i === navIndex);
    }
    setClass(body, 'at-nav', !overlay && zone === 'nav');
    var acts = $('actions').childNodes;
    for (var a = 0; a < acts.length; a++) setClass(acts[a], 'focus', !overlay && zone === 'actions' && a === actionIndex);
    for (var s = 0; s < stack.length; s++) {
      var item = stack[s];
      if (!item.cells) continue;
      var at = !overlay && zone === 'stack' && s === stackIndex ? rowState[item.id].index : -1;
      for (var k = 0; k < item.cells.length; k++) setClass(item.cells[k], 'focus', k === at);
    }
  }

  /** After the focus moved: the springs (CSS transitions), the stack's position and the title on screen. */
  function moved() {
    layoutStack(false);
    updateFocus();
    showTitle(true);
  }

  function lastFocusable() {
    for (var i = stack.length - 1; i >= 0; i--) if (focusable(i)) return i;
    return -1;
  }

  function goTop() {
    zone = actions().length ? 'actions' : 'nav';
    navIndex = tab;
  }

  function arrow(dx, dy) {
    if (zone === 'nav') {
      if (dx) navIndex = Math.max(0, Math.min(TAB_SETTINGS, navIndex + dx));
      else if (dy > 0) {
        if (actions().length) zone = 'actions';
        else if (lastFocusable() >= 0) { zone = 'stack'; stackIndex = firstFocusable(); }
      }
    } else if (zone === 'actions') {
      var n = actions().length;
      if (dx) {
        if (actionIndex + dx < 0 || actionIndex + dx >= n) {
          // Past the ends of the buttons: the previous or next featured title.
          if (featured.length > 1) {
            heroIndex = (heroIndex + dx + featured.length) % featured.length;
            showTitle(true);
            return;
          }
        } else {
          actionIndex += dx;
        }
      } else if (dy < 0) {
        zone = 'nav';
        navIndex = tab;
      } else if (lastFocusable() >= 0) {
        zone = 'stack';
        stackIndex = firstFocusable();
      }
    } else if (zone === 'stack') {
      var item = stack[stackIndex];
      if (dx) {
        var st = rowState[item.id];
        st.index = Math.max(0, Math.min(item.items.length - 1, st.index + dx));
      } else if (dy < 0) {
        var up = stackIndex - 1;
        while (up >= 0 && !focusable(up)) up--;
        if (up < 0) goTop(); else stackIndex = up;
      } else {
        var down = stackIndex + 1;
        while (down < stack.length && !focusable(down)) down++;
        if (down < stack.length) stackIndex = down;
      }
    }
    moved();
  }

  function firstFocusable() {
    for (var i = 0; i < stack.length; i++) if (focusable(i)) return i;
    return 0;
  }

  function ok() {
    if (zone === 'nav') {
      if (navIndex <= TAB_SHOWS) selectTab(navIndex);
      else if (navIndex === TAB_APPS) openApps();
      else if (navIndex === TAB_SEARCH) openSearch();
      else openSettings();
    } else if (zone === 'actions') {
      var a = actions()[actionIndex];
      if (a === ACT_OPEN) openTitle(pick);
      else if (a === ACT_INFO) openInfo();
      else if (a === ACT_HIDE) notForMe(pick);
    } else if (zone === 'stack') {
      var item = stack[stackIndex], k = rowState[item.id].index;
      if (item.type === 'apps') launch(item.items[k]);
      else openInfo();
    }
  }

  function back() {
    if (overlay) { closeOverlay(); return; }
    if (zone === 'stack') { goTop(); moved(); return; }
    if (searchRow) { searchRow = null; buildStack(); return; }
    if (tab !== TAB_HOME) { selectTab(TAB_HOME); return; }
    if (zone !== 'actions' && actions().length) { goTop(); moved(); return; }
    TV.back();
  }

  /** Home, Movies or TV Shows: their rows, from the top. */
  function selectTab(t) {
    if (t === tab) return;
    tab = t;
    navIndex = t;
    stackIndex = 0;
    heroIndex = 0;
    buildStack();
    layoutStack(true);
    updateFocus();
  }

  // MARK: - Opening things

  var toastTimer = 0;
  function toast(text) {
    var t = $('toast');
    t.textContent = text;
    setClass(t, 'on', true);
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { setClass(t, 'on', false); }, 4000);
  }

  function launch(app) {
    if (!app) return;
    TV.launch(app.id, null, function (ok) {
      if (!ok) toast(trf("Couldn't open {app}", {app: app.title}));
    });
  }

  /** A title opens its app (at the app's home: these TVs give no way to open the title itself). */
  function openTitle(p) {
    var app = Data.appOf(p);
    if (app) launch(app);
    else toast(trf('TMDB lists no streaming for it on your apps in {region}.', {region: Data.region()}));
  }

  function notForMe(p) {
    if (!p) return;
    Data.hide(p);
    toast(trf('“{title}” won’t be suggested again.', {title: p.title}));
  }

  // MARK: - More Info

  function openInfo() {
    if (!pick) return;
    var p = pick, panel = $('info').querySelector('.panel');
    panel.innerHTML = '';
    el('h2', '', panel, p.title);
    el('div', 'meta', panel, heroMeta(p));
    el('div', 'body', panel, p.overview || tr('TMDB has no description of this title.'));
    var app = Data.serviceName(p);
    el('div', 'where', panel, Data.appOf(p)
        ? trf('Streams on {app} in {region}, according to TMDB.', {app: app, region: Data.region()})
        : trf('TMDB lists no streaming for it on your apps in {region}.', {region: Data.region()}));
    el('div', 'hint', panel, Data.appOf(p) ? tr('OK opens it · Back closes') : tr('Back closes'));
    openOverlay('info');
  }

  // MARK: - Sheets

  function openOverlay(name) {
    overlay = name;
    setClass($(name), 'open', true);
    updateFocus();
  }

  function closeOverlay() {
    var input = document.activeElement;
    if (input && input.tagName === 'INPUT') input.blur();
    setClass($(overlay), 'open', false);
    overlay = null;
    updateFocus();
  }

  // All Apps, with the TV's inputs and its settings first: a grid, 5 per row.
  var GRID_W = 260, GRID_H = 146, GRID_GAP = 40, GRID_ROW = 216, COLUMNS = 5, GROUP_HEAD = 56, VISIBLE = 640;
  var grid = [], gridAt = 0, gridScroll = 0;   // [{row, col, y, el, run}]

  function openApps() {
    var box = $('apps').querySelector('.box'), scroll = box.querySelector('.scroll');
    box.querySelector('h2').textContent = tr('All Apps');
    scroll.innerHTML = '';
    grid = [];
    var y = 0, row = 0;
    function group(title, entries) {
      if (!entries.length) return;
      el('h4', '', scroll, title).style.cssText = 'position:absolute;left:0;top:' + y + 'px';
      y += GROUP_HEAD;
      for (var i = 0; i < entries.length; i++) {
        var col = i % COLUMNS;
        if (i && !col) { row++; y += GRID_ROW; }
        var t = el('div', 'tile', scroll);
        t.style.left = col * (GRID_W + GRID_GAP) + 'px';
        t.style.top = y + 'px';
        t.style.width = GRID_W + 'px';
        t.style.height = GRID_H + 'px';
        t.setAttribute('data-z', 'grid');
        t.setAttribute('data-a', grid.length);
        appArt(t, entries[i], GRID_H);
        el('div', 'name', t, entries[i].title).style.top = GRID_H + 12 + 'px';
        grid.push({row: row, col: col, y: y, el: t, run: entries[i].run});
      }
      row++;
      y += GRID_ROW + 10;
    }
    TV.inputs(function (inputs) {
      var first = [];
      for (var i = 0; i < inputs.length; i++) {
        if (!inputs[i].connected) continue;
        (function (x) {
          first.push({title: x.label || tr('Live TV'), symbol: x.symbol, color: '#2C2C2E',
            run: function () { TV.launch(x.id, null, function (ok) { if (!ok) toast(trf("Couldn't open {app}", {app: x.label || tr('Live TV')})); }); }});
        })(inputs[i]);
      }
      first.push({title: tr('TV Settings'), symbol: 'tv_settings', color: '#2C2C2E',
        run: function () { TV.settings(function (ok) { if (!ok) toast(trf("Couldn't open {app}", {app: tr('TV Settings')})); }); }});
      group(tr('Inputs'), first);
      var apps = Data.apps().map(function (a) { return {title: a.title, icon: a.icon, color: a.color, run: function () { launch(a); }}; });
      group(tr('Apps'), apps);
      gridAt = Math.min(gridAt, grid.length - 1);
      gridScroll = 0;
      layoutGrid();
      openOverlay('apps');
    });
  }

  function layoutGrid() {
    var cur = grid[gridAt];
    if (!cur) return;
    var top = cur.y;
    if (top - GROUP_HEAD < gridScroll) gridScroll = Math.max(0, top - GROUP_HEAD);
    if (top + GRID_H + 50 > gridScroll + VISIBLE) gridScroll = top + GRID_H + 50 - VISIBLE;
    translate($('apps').querySelector('.scroll'), 0, -gridScroll);
    for (var i = 0; i < grid.length; i++) setClass(grid[i].el, 'focus', i === gridAt);
  }

  function gridArrow(dx, dy) {
    var cur = grid[gridAt];
    if (!cur) return;
    if (dx) {
      var n = gridAt + dx;
      if (n >= 0 && n < grid.length && grid[n].row === cur.row) gridAt = n;
    } else {
      var want = cur.row + dy, best = -1;
      for (var i = 0; i < grid.length; i++) {
        if (grid[i].row !== want) continue;
        if (best < 0 || Math.abs(grid[i].col - cur.col) < Math.abs(grid[best].col - cur.col)) best = i;
      }
      if (best >= 0) gridAt = best;
    }
    layoutGrid();
  }

  // Search: typed with the TV's own keyboard; the results come back as the first row under Your Apps.
  function openSearch() {
    var sheet = $('search'), input = sheet.querySelector('input');
    sheet.querySelector('h2').textContent = tr('Search');
    input.placeholder = tr('A title, an actor, a director…');
    sheet.querySelector('.note').textContent = Data.hasKey()
        ? tr('Type with the remote, then press OK. Each result says where it streams in your region.')
        : tr('Search needs your free TMDB key: add it in Settings.');
    openOverlay('search');
    setTimeout(function () { input.focus(); }, 50);
  }

  function runSearch() {
    var sheet = $('search'), input = sheet.querySelector('input'), q = input.value.replace(/^\s+|\s+$/g, '');
    if (!q || !Data.hasKey()) return;
    input.blur();
    sheet.querySelector('.note').textContent = tr('Searching…');
    Data.search(q, function (found) {
      if (!found) { sheet.querySelector('.note').textContent = Data.error(); return; }
      searchRow = {title: found.length ? trf('Results for “{query}”', {query: q}) : trf('Nothing found for “{query}”', {query: q}), items: found};
      rowState.search = {index: 0, first: 0};
      closeOverlay();
      if (tab !== TAB_HOME) { tab = TAB_HOME; navIndex = TAB_HOME; }
      buildStack();
      for (var i = 0; i < stack.length; i++) if (stack[i].id === 'search' && found.length) { zone = 'stack'; stackIndex = i; }
      moved();
    });
  }

  // Settings: what the Android app keeps in Personalize, typed on the TV; the TV's choice wins over config.properties.
  var settings = [], settingAt = 0;

  function maskKey(k) { return k ? '••••••' + k.slice(-4) : tr('Not set'); }

  function settingRows() {
    return [
      {label: tr('TMDB key'), field: 'tmdbKey', value: function () { return maskKey(Prefs.tmdbKey()); }, reload: true},
      {label: tr('Region'), field: 'region', value: function () { return Prefs.region(); }, reload: true},
      {label: tr('Content languages'), field: 'contentLanguage',
        value: function () { var l = Prefs.contentLanguages(); return l.length ? l.map(Data.languageName).join(', ') : tr('Any language'); }, reload: true},
      {label: tr('Your name'), field: 'viewerName', value: function () { return Prefs.viewerName() || tr('Not set'); }},
      {label: tr('Language'), value: function () { var l = Prefs.language(); return l === 'auto' ? tr('Automatic') : I18N.languageName(l); },
        run: function () {
          var all = ['auto'].concat(I18N.languages()), at = all.indexOf(Prefs.language());
          Prefs.set('language', all[(at + 1) % all.length]);
          I18N.set(Prefs.language());
          buildNav();
          renderSettings();
          Data.reload();
        }},
      {label: tr('Refresh the rows'), value: function () { return Data.loading() ? tr('Loading…') : ''; }, run: function () { Data.reload(); renderSettings(); }},
      {label: tr('TV Settings'), value: function () { return ''; }, run: function () { TV.settings(); }}
    ];
  }

  function openSettings() {
    $('settings').querySelector('h2').textContent = tr('light|Settings');
    renderSettings();
    openOverlay('settings');
  }

  function renderSettings() {
    var list = $('settings').querySelector('.list');
    list.innerHTML = '';
    settings = settingRows();
    for (var i = 0; i < settings.length; i++) {
      var s = settings[i], item = el('div', 'item', list, s.label);
      item.setAttribute('data-z', 'setting');
      item.setAttribute('data-a', i);
      s.el = item;
      s.valueEl = el('span', 'value', item, s.value());
    }
    el('div', 'about', list, 'EndGrid iQTV+ by EndGrid Labs · endgrid.in. ' + tr('On this TV it opens as an app; the TV keeps its own home screen.')
        + ' ' + tr('This product uses the TMDB API but is not endorsed or certified by TMDB.'));
    focusSetting();
  }

  function focusSetting() {
    for (var i = 0; i < settings.length; i++) setClass(settings[i].el, 'focus', i === settingAt);
  }

  var editing = null;

  /** A field: an input over its row, the TV's keyboard comes up; OK keeps it, Back leaves it as it was. */
  function editSetting(s) {
    editing = s;
    s.valueEl.style.display = 'none';
    var input = el('input', '', s.el);
    input.type = 'text';
    input.setAttribute('autocomplete', 'off');
    var raw = Prefs.raw(s.field);
    input.value = raw !== undefined && raw !== null ? raw
        : s.field === 'tmdbKey' ? '' : s.field === 'region' ? Prefs.region() : s.field === 'contentLanguage' ? Prefs.contentLanguages().join(', ') : Prefs.viewerName();
    input.placeholder = s.field === 'contentLanguage' ? 'ta, te, hi' : s.field === 'region' ? 'IN' : '';
    setTimeout(function () { input.focus(); }, 30);
  }

  function endEdit(keep) {
    var s = editing;
    if (!s) return;
    editing = null;
    var input = s.el.querySelector('input');
    if (keep && input) {
      var v = input.value.replace(/^\s+|\s+$/g, '');
      if (s.field === 'region') v = v.toUpperCase().slice(0, 2);
      Prefs.set(s.field, v);
      if (s.reload) Data.reload();
      updateCorner();
    }
    if (input) { input.blur(); s.el.removeChild(input); }
    s.valueEl.style.display = '';
    s.valueEl.textContent = s.value();
  }

  // MARK: - Keys and pointer

  var KEYS = TV.keys;

  document.addEventListener('keydown', function (e) {
    var code = e.keyCode, isBack = code === KEYS.back || code === 461 || code === 10009 || code === 27;
    var input = document.activeElement && document.activeElement.tagName === 'INPUT' ? document.activeElement : null;
    if (input) {
      // Typing: the keys belong to the field, except OK, Back and up / down, which leave it.
      if (code === 13) {
        e.preventDefault();
        if (overlay === 'search') runSearch(); else endEdit(true);
      } else if (isBack) {
        e.preventDefault();
        if (overlay === 'search') closeOverlay(); else endEdit(false);
      } else if ((code === 38 || code === 40) && overlay === 'settings') {
        e.preventDefault();
        endEdit(true);
        settingAt = Math.max(0, Math.min(settings.length - 1, settingAt + (code === 40 ? 1 : -1)));
        focusSetting();
      }
      return;
    }
    if (isBack || code === 8) {
      e.preventDefault();
      back();
      return;
    }
    var dx = code === 37 ? -1 : code === 39 ? 1 : 0, dy = code === 38 ? -1 : code === 40 ? 1 : 0;
    if (!dx && !dy && code !== 13) return;
    e.preventDefault();
    if (overlay === 'info') {
      if (code === 13) { closeOverlay(); openTitle(pick); }
    } else if (overlay === 'apps') {
      if (code === 13) { if (grid[gridAt]) { var run = grid[gridAt].run; closeOverlay(); run(); } } else gridArrow(dx, dy);
    } else if (overlay === 'search') {
      if (code === 13 || dy) $('search').querySelector('input').focus();
    } else if (overlay === 'settings') {
      if (code === 13) {
        var s = settings[settingAt];
        if (s.field) editSetting(s); else if (s.run) s.run();
      } else if (dy) {
        settingAt = Math.max(0, Math.min(settings.length - 1, settingAt + dy));
        focusSetting();
      }
    } else if (code === 13) {
      ok();
    } else {
      arrow(dx, dy);
    }
  });

  /** The element under a pointer that can take the focus: [zone, a, b]. */
  function target(e) {
    for (var n = e.target; n && n !== document; n = n.parentNode) {
      if (n.getAttribute && n.getAttribute('data-z')) return [n.getAttribute('data-z'), +n.getAttribute('data-a'), +n.getAttribute('data-b')];
    }
    return null;
  }

  function point(t) {
    if (!t) return false;
    var z = t[0];
    if (z === 'grid') { if (overlay !== 'apps') return false; gridAt = t[1]; layoutGrid(); return true; }
    if (z === 'setting') { if (overlay !== 'settings') return false; settingAt = t[1]; focusSetting(); return true; }
    if (overlay) return false;
    if (z === 'nav') { zone = 'nav'; navIndex = t[1]; }
    else if (z === 'actions') { zone = 'actions'; actionIndex = t[1]; }
    else if (z === 'stack') {
      if (zone === 'stack' && stackIndex === t[1] && rowState[stack[t[1]].id].index === t[2]) return true;
      zone = 'stack';
      stackIndex = t[1];
      rowState[stack[t[1]].id].index = t[2];
    }
    updateFocus();
    showTitle(true);
    return true;
  }

  document.addEventListener('mouseover', function (e) {
    var t = target(e);
    // Pointing at a row's card only moves the focus there: the stack doesn't jump under the pointer.
    if (t) point(t);
  });

  document.addEventListener('click', function (e) {
    var t = target(e);
    if (t && point(t)) {
      if (t[0] === 'grid') { var run = grid[gridAt].run; closeOverlay(); run(); }
      else if (t[0] === 'setting') { var s = settings[settingAt]; if (s.field) { if (editing !== s) { endEdit(true); editSetting(s); } } else if (s.run) s.run(); }
      else { layoutStack(false); ok(); }
      return;
    }
    // A click on the dimmed screen around a sheet closes it.
    if (overlay && hasClass(e.target, 'overlay')) closeOverlay();
  });

  // MARK: - Start

  buildNav();
  updateCorner();
  setInterval(updateCorner, 10000);
  // Until the first key or click, the screen opens at the top: once the rows arrive, on the featured title's buttons.
  var touched = false;
  document.addEventListener('keydown', function () { touched = true; }, true);
  document.addEventListener('click', function () { touched = true; }, true);
  Data.onChange(function () {
    buildStack();
    if (!touched && !overlay && actions().length && zone !== 'actions') { goTop(); moved(); }
    if (overlay === 'settings' && !editing) {
      for (var i = 0; i < settings.length; i++) settings[i].valueEl.textContent = settings[i].value();
    }
  });
  buildStack();
  goTop();
  if (zone === 'nav' && lastFocusable() >= 0) { zone = 'stack'; stackIndex = firstFocusable(); }
  moved();
  // Opened again from the TV's launcher while running (webOS): back at the top, as Home does on Android.
  document.addEventListener('webOSRelaunch', function () {
    if (overlay) closeOverlay();
    goTop();
    moved();
  });
})();
