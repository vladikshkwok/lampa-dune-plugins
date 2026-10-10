/* Native Dune movie OSD adapter. Requires dune_plugin_lampa_dune_bridge.zip on the same device. */
(function () {
    'use strict';
    if (window.DuneNativePlayer || typeof AndroidJS === 'undefined') return;
    var original, wrapper, ready, dead = false, sequence = 0;
    var urls = ['http://127.0.0.1/cgi-bin/plugins/lampa_dune_bridge/api',
        'http://127.0.0.1:11080/cgi-bin/plugins/lampa_dune_bridge/api'];
    var diagnostics = {version: '1.0.1', state: 'idle', launches: 0};
    function text(value, limit) {
        return typeof value === 'string' ? value.replace(/<[^>]*>/g, '').slice(0, limit) : '';
    }
    function url(value) {
        if (typeof value !== 'string' || value.length > 16384 || /[\x00-\x20]/.test(value)) return '';
        try { var u = new URL(value); return /^https?:$/.test(u.protocol) ? value : ''; } catch (e) { return ''; }
    }
    function card(data) {
        var active = Lampa.Activity && Lampa.Activity.active();
        return data.card || data.movie || (active && (active.card || active.movie)) || {};
    }
    function payload(link, data) {
        var movie = card(data), selected = data.url || link;
        var list = data.playlist && data.playlist.length ? data.playlist : [data];
        if (!url(selected) || list.length > 250) return null;
        if (data.headers && Object.keys(data.headers).length) return null;
        var items = [], index = -1;
        list.forEach(function (item) {
            if (!item || !url(item.url || selected) || (item.headers && Object.keys(item.headers).length)) return;
            if ((item.url || selected) === selected) index = items.length;
            items.push({url: item.url || selected, name: text(item.title || data.title || '', 512)});
        });
        // Preserve the exact playlist; malformed entries use the usual launch path.
        if (items.length !== list.length || index < 0) return null;
        var poster = data.poster || movie.poster || '';
        if (!poster && movie.poster_path && Lampa.TMDB && Lampa.TMDB.image) poster = Lampa.TMDB.image('t/p/w500' + movie.poster_path);
        var year = (movie.release_date || movie.first_air_date || '').slice(0, 4);
        var description = text(movie.overview || data.description || '', 15000);
        if (year) description = year + (movie.vote_average ? ' · TMDB: ' + Number(movie.vote_average).toFixed(1) : '') + '\n\n' + description;
        var timeline = data.timeline || (list[index] && list[index].timeline);
        if (timeline && Lampa.Timeline) timeline = Lampa.Timeline.view(timeline.hash);
        var position = timeline ? Number(timeline.time || 0) : 0;
        if (!isFinite(position) || position < 0 || position > 604800) position = 0;
        return {title: text(movie.title || movie.name || data.title || 'Lampa', 512),
            description: description, poster: url(poster), items: items, index: index, position: position};
    }
    function base(value, rootOnly) {
        if (!url(value)) return '';
        var u = new URL(value);
        if (u.username || u.password || u.search || u.hash || (rootOnly && u.pathname !== '/')) return '';
        return u.origin + u.pathname.replace(/\/$/, '');
    }
    function directStream(value, source, target) {
        try {
            var u = new URL(value), at = u.pathname.indexOf('/stream/');
            if (at < 0 || u.origin + u.pathname.slice(0, at) !== source ||
                !/^[a-f0-9]{40}$/i.test(u.searchParams.get('link') || '') ||
                !/^[1-9][0-9]*$/.test(u.searchParams.get('index') || '') || !u.searchParams.has('play')) return value;
            if (u.username || u.password) return value;
            var path = u.pathname.slice(at + 8);
            if (!path || path.indexOf('/') >= 0) return value;
            return target + '/stream/' + path + u.search;
        } catch (e) { return value; }
    }
    function prepare(body, callback) {
        var target = base(Lampa.Storage.get('dune_native_torrserver_url', ''), true);
        var source = base(Lampa.Storage.get('dune_native_torrserver_source', ''), false);
        if (!source || !target) return callback(body);
        var selected = body.items[body.index].url;
        if (directStream(selected, source, target) === selected) return callback(body);
        var hash = new URL(selected).searchParams.get('link'), settled = false;
        var timer = setTimeout(function () { done(null); }, 1200);
        function done(value) {
            if (settled || dead) return;
            settled = true; clearTimeout(timer);
            if (typeof value === 'string') { try { value = JSON.parse(value); } catch (e) { value = null; } }
            // Read only: ensure the configured direct server has this torrent before bypassing its proxy.
            if (value && typeof value.hash === 'string' && value.hash.toLowerCase() === hash.toLowerCase()) {
                body.items.forEach(function (item) {
                    var itemHash = new URL(item.url).searchParams.get('link') || '';
                    if (itemHash.toLowerCase() === hash.toLowerCase()) item.url = directStream(item.url, source, target);
                });
            }
            callback(body);
        }
        try {
            Lampa.Android.httpReq({url: target + '/torrents', post_data: JSON.stringify({action:'get', hash:hash}),
                contentType:'application/json', dataType:'json', timeout:1000},
                {complite:done, error:function () { done(null); }});
        } catch (e) { done(null); }
    }
    function settings() {
        if (!Lampa.SettingsApi) return;
        Lampa.SettingsApi.addComponent({component:'dune_native', name:'Dune HD',
            icon:'<svg viewBox="0 0 40 40"><rect x="3" y="7" width="34" height="24" rx="3" fill="none" stroke="currentColor" stroke-width="3"/><path d="M13 36h14" stroke="currentColor" stroke-width="3"/></svg>'});
        Lampa.SettingsApi.addParam({component:'dune_native',
            param:{name:'dune_native_player_enabled', type:'trigger', default:true},
            field:{name:'Обложка и информация Dune'}});
        Lampa.SettingsApi.addParam({component:'dune_native',
            param:{name:'dune_native_torrserver_url', type:'input', values:'', default:'', placeholder:'http://SERVER:8095'},
            field:{name:'Прямой адрес TorrServer', description:'Для штатной строки «Состояние потока». Укажите адрес без прокси-префикса, доступный с Dune. Привязывается к текущему TorrServer Lampa; обычно можно оставить пустым.'},
            onChange:function () {
                var one = Lampa.Storage.get('torrserver_url', ''), two = Lampa.Storage.get('torrserver_url_two', '');
                var selected = Lampa.Storage.get('torrserver_use_link', 'one') === 'two' ? two || one : one || two;
                Lampa.Storage.set('dune_native_torrserver_source', base(selected, false));
            }});
    }
    function request(index, body, callback) {
        if (index >= urls.length) return callback(null);
        var settled = false, watchdog;
        function done(value) {
            if (settled || dead) return;
            settled = true; clearTimeout(watchdog);
            if (value && value.plugin === 'lampa_dune_bridge' &&
                value.launch_url === 'plugin_launcher://{name=lampa_dune_bridge}{action_id=play}') {
                // Android's player chooser only resolves ordinary media schemes.
                // Dune recognizes a one-item M3U containing a plugin launcher.
                return callback(urls[index].replace('/cgi-bin/plugins/', '/plugins/').replace('/api', '/launch.m3u'));
            }
            request(index + 1, body, callback);
        }
        watchdog = setTimeout(function () { done(null); }, 2000);
        try {
            Lampa.Android.httpReq({url: urls[index], post_data: JSON.stringify(body),
                contentType: 'application/json', dataType: 'json', timeout: 1500}, {
                complite: function (value) {
                    if (typeof value === 'string') { try { value = JSON.parse(value); } catch (e) { value = null; } }
                    done(value);
                }, error: function () { done(null); }
            });
        } catch (e) { done(null); }
    }
    function init() {
        settings();
        original = Lampa.Android.openPlayer;
        wrapper = function (link, data) {
            var body, self = this, id = ++sequence;
            if (dead || (Lampa.Storage && Lampa.Storage.get('dune_native_player_enabled', true) === false)) return original.apply(self, arguments);
            try { body = payload(link, data); } catch (e) {}
            if (!body) return original.apply(self, arguments);
            diagnostics.state = 'preparing';
            prepare(body, function (prepared) {
            if (dead || id !== sequence) return;
            request(0, prepared, function (launch) {
                if (dead || id !== sequence) return;
                if (!launch) { diagnostics.state = 'fallback'; original.call(self, link, data); return; }
                // Timeline callbacks were already registered against the original HTTP playlist.
                // The native companion handles series itself; do not let the Android APK build another playlist.
                var nativeData = {}; Object.keys(data).forEach(function (key) { nativeData[key] = data[key]; });
                nativeData.url = launch; delete nativeData.playlist;
                diagnostics.state = 'native'; diagnostics.launches++;
                original.call(self, launch, nativeData);
                // Works in either plugin load order; track the HTTP items, not the launcher URI.
                if (window.DuneLocalProgress && window.DuneLocalProgress.track) window.DuneLocalProgress.track(link, data);
            });
            });
        };
        Lampa.Android.openPlayer = wrapper;
        diagnostics.state = 'ready';
    }
    window.DuneNativePlayer = {
        status: function () { return JSON.parse(JSON.stringify(diagnostics)); },
        destroy: function () {
            dead = true; ++sequence; clearInterval(ready);
            if (original && Lampa.Android.openPlayer === wrapper) Lampa.Android.openPlayer = original;
            delete window.DuneNativePlayer;
        }
    };
    ready = setInterval(function () {
        if (!dead && window.appready && window.Lampa && Lampa.Android && Lampa.Android.httpReq) { clearInterval(ready); init(); }
    }, 200);
})();
