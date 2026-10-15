/* Dune HD local playback progress for Lampa Android. No external progress service. */
(function () {
    'use strict';
    if (window.DuneLocalProgress || typeof AndroidJS === 'undefined') return;
    var KEY = 'dune_local_progress_v1';
    var endpoints = ['http://127.0.0.1/cgi-bin/do?cmd=status&result_syntax=json',
        'http://127.0.0.1:11080/cgi-bin/do?cmd=status&result_syntax=json'];
    var session = null, references = {}, endpoint = null, probing = false;
    var busy = false, timer = null, watchdog = null, ready = null;
    var original = null, wrapper = null, parseOriginal = null, parseWrapper = null, dead = false, sequence = 0;
    var diagnostics = {version: '1.2.0', state: 'idle', polls: 0, samples: 0, endpoint: null, last: null};

    function now() { return Date.now(); }
    function number(value) {
        if (typeof value !== 'number' && (typeof value !== 'string' || !/^\d+(\.\d+)?$/.test(value))) return null;
        var n = Number(value);
        return isFinite(n) && n >= 0 ? n : null;
    }
    function digest(value) {
        // Four independent 32-bit lanes; URLs/tokens are never stored in localStorage.
        var lanes = [2166136261, 5381, 52711, 1315423911], out = '', i, j;
        for (i = 0; i < value.length; i++) {
            var c = value.charCodeAt(i);
            for (j = 0; j < lanes.length; j++) {
                lanes[j] = ((lanes[j] * (j * 2 + 31)) ^ (c + j * 257)) | 0;
            }
        }
        for (j = 0; j < lanes.length; j++) out += ('00000000' + (lanes[j] >>> 0).toString(16)).slice(-8);
        return out;
    }
    function identity(raw) {
        if (typeof raw !== 'string' || raw.length > 16384 || !/^https?:\/\//i.test(raw)) return null;
        try {
            var u = new URL(raw), hash = u.searchParams.get('link'), index = u.searchParams.get('index');
            if (/\/stream\//.test(u.pathname) && /^[a-f\d]{40}$/i.test(hash || '') && /^[1-9]\d*$/.test(index || '')) {
                return 'torrent:' + hash.toLowerCase() + ':' + Number(index);
            }
            u.hash = '';
            return 'url:' + digest(u.href);
        } catch (e) { return null; }
    }
    // Lampa's torrent parser misses names such as "Show.S01E.01" or "S01.E01"
    // and then gives every file of a season episode 0, i.e. one shared
    // timeline hash: progress of one episode would show on all of them.
    var EPISODE_RES = [/\bs(\d{1,2})[\s.\-]*e[\s.\-]*(\d{1,3})(?!\d)/i, /\b(\d{1,2})x(\d{1,3})\b/i,
        /(?:^|[\s.\-])e(?:p|pisode)?[\s.\-]*(\d{1,3})(?!\d)/i, /(?:серия|эпизод)[\s.\-]*(\d{1,3})(?!\d)/i];
    function episodeOf(path) {
        var name = String(path || '').split('/').pop().replace(/\.[^.]+$/, '').replace(/_/g, ' '), i, m;
        for (i = 0; i < EPISODE_RES.length; i++) {
            m = name.match(EPISODE_RES[i]);
            if (m) return m.length > 2 ? {season: parseInt(m[1], 10), episode: parseInt(m[2], 10)} : {season: null, episode: parseInt(m[1], 10)};
        }
        return null;
    }
    function fixParse(parse) {
        return function (data) {
            var result = parse.apply(this, arguments);
            try {
                var movie = data && data.movie;
                if (!result || data.is_file || !movie || !movie.number_of_seasons || result.episode) return result;
                var found = episodeOf(data.path), title = movie.original_title || movie.original_name || '';
                if (found && found.episode > 0) {
                    result.season = found.season || result.season || 1; result.episode = found.episode;
                    result.hash_string = [result.season, result.season > 10 ? ':' : '', result.episode, title].join('');
                } else if (Array.isArray(data.files) && data.files.length > 1) {
                    result.hash_string = data.path; // own progress per file rather than one shared
                } else return result;
                result.hash = Lampa.Utils.hash(result.hash_string);
            } catch (e) {}
            return result;
        };
    }
    function profile(hash) { return Number(Lampa.Timeline.view(hash).profile || 0); }
    function persist() {
        try {
            if (session) localStorage.setItem(KEY, JSON.stringify(session));
            else localStorage.removeItem(KEY);
        } catch (e) {}
    }
    function apply(item, sample) {
        if (profile(item.hash) !== item.profile) return;
        // Only local timeline storage/UI. Do not send plugin timecodes to cloud/TorrServer.
        Lampa.Timeline.update({hash: item.hash, profile: item.profile, time: sample.time,
            duration: sample.duration, percent: sample.percent, received: true});
        var ref = references[item.hash];
        if (ref) {
            ref.time = sample.time; ref.duration = sample.duration; ref.percent = sample.percent;
        }
    }
    function restore() {
        try {
            var saved = JSON.parse(localStorage.getItem(KEY) || 'null');
            if (!saved || !Array.isArray(saved.items) || saved.items.length > 250 ||
                !number(saved.created) || now() - saved.created > 12 * 3600000) return;
            saved.items = saved.items.filter(function (i) {
                return i && /^-?\d{1,20}$/.test(String(i.hash)) && String(i.hash) !== '0' &&
                    typeof i.key === 'string' && /^(torrent:[a-f\d]{40}:\d+|url:[a-f\d]{32})$/.test(i.key) &&
                    number(i.profile) !== null;
            });
            if (!saved.items.length) return;
            session = saved;
            saved.items.forEach(function (item) {
                var s = item.sample;
                if (s && number(s.time) !== null && number(s.duration) > 0 && number(s.percent) !== null && s.percent <= 100) apply(item, s);
            });
        } catch (e) { session = null; }
    }
    function finish(reason) {
        if (session) {
            session.items.forEach(function (i) { if (i.sample) apply(i, i.sample); });
        }
        session = null; references = {}; persist(); diagnostics.state = reason || 'idle';
        clearTimeout(timer); timer = null;
    }
    function schedule(delay) {
        clearTimeout(timer);
        if (!dead && session) timer = setTimeout(poll, delay || 1000);
    }
    function device(status) {
        return status && typeof status.product_id === 'string' && typeof status.product_name === 'string' &&
            status.product_name.length > 0 && Number(status.protocol_version) >= 5 && typeof status.player_state === 'string';
    }
    function request(url, done) {
        var settled = false;
        function end(value) {
            if (settled || dead) return;
            settled = true; clearTimeout(watchdog); watchdog = null; done(value);
        }
        watchdog = setTimeout(function () { end(null); }, 3000);
        try {
            Lampa.Android.httpReq({url: url, timeout: 2000, dataType: 'json'}, {
                complite: function (status) {
                    if (typeof status === 'string') { try { status = JSON.parse(status); } catch (e) { status = null; } }
                    end(status);
                },
                error: function () { end(null); }
            });
        } catch (e) { end(null); }
    }
    function discover(done) {
        probing = true;
        function next(i) {
            if (i >= endpoints.length) { probing = false; done(null); return; }
            request(endpoints[i], function (status) {
                if (!device(status)) return next(i + 1);
                endpoint = endpoints[i]; diagnostics.endpoint = endpoint;
                probing = false; done(status);
            });
        }
        next(0);
    }
    function consume(status) {
        if (!session || !status) return;
        var t = now(), key = identity(status.playback_url), item = null;
        if (status.player_state === 'file_playback' && key) {
            session.items.some(function (i) { if (i.key === key) { item = i; return true; } return false; });
        }
        if (item) {
            var time = number(status.playback_position), duration = number(status.playback_duration);
            if (time === null || duration === null || duration <= 0 || time > duration + 5) return;
            time = Math.min(time, duration);
            var sample = {time: time, duration: duration, percent: Math.min(100, time / duration * 100)};
            session.state = 'playing'; session.lastSeen = t; item.sample = sample;
            diagnostics.state = 'playing'; diagnostics.samples++;
            diagnostics.last = {hash: item.hash, time: time, duration: duration, percent: sample.percent};
            persist(); apply(item, sample);
        } else if (session.state === 'playing' && status.player_state !== 'file_playback') {
            // Keep the last real sample. Stopping does not mean 100% watched.
            finish('stopped');
        }
    }
    function poll() {
        if (dead || busy || probing || !session) return;
        var token = session.id;
        if (now() - session.created > 12 * 3600000 ||
            (session.state === 'waiting' && now() - session.created > 180000)) return finish('expired');
        busy = true; diagnostics.polls++;
        function done(status) {
            busy = false;
            if (dead) return;
            if (session && session.id === token) consume(status);
            schedule();
        }
        if (!endpoint) discover(done);
        else request(endpoint, done);
    }
    function begin(link, data) {
        finish();
        var all = [data || {}].concat(data && Array.isArray(data.playlist) ? data.playlist : []), items = [], seen = {}, keys = {}, ambiguous = {};
        all.slice(0, 251).forEach(function (entry, index) {
            var tl = entry && entry.timeline, hash = tl && String(tl.hash), key = identity(entry && entry.url || (index === 0 && link));
            if (!key || !hash || !/^-?\d{1,20}$/.test(hash) || hash === '0' || seen[hash]) return;
            if (keys[key] && keys[key] !== hash) ambiguous[key] = true;
            keys[key] = hash;
            seen[hash] = true; references[hash] = tl;
            items.push({hash: hash, profile: profile(hash), key: key});
        });
        items = items.filter(function (item) { return !ambiguous[item.key]; });
        if (!items.length) return;
        session = {id: String(now()) + ':' + (++sequence), created: now(), state: 'waiting', items: items};
        diagnostics.state = 'waiting'; diagnostics.last = null;
        persist(); schedule(50);
    }
    function wake() { if (session && !busy && !probing) schedule(50); }
    function init() {
        if (!Lampa.Android || !Lampa.Android.httpReq || !Lampa.Android.openPlayer || !Lampa.Timeline || !Lampa.Timeline.update) return;
        if (Lampa.Torserver && typeof Lampa.Torserver.parse === 'function' && Lampa.Utils && Lampa.Utils.hash) {
            parseOriginal = Lampa.Torserver.parse;
            Lampa.Torserver.parse = parseWrapper = fixParse(parseOriginal);
        }
        original = Lampa.Android.openPlayer;
        wrapper = function (link, data) {
            try { begin(link, data); } catch (e) { finish('error'); }
            return original.apply(this, arguments);
        };
        Lampa.Android.openPlayer = wrapper;
        window.addEventListener('focus', wake); window.addEventListener('pageshow', wake);
        document.addEventListener('visibilitychange', wake);
        restore(); schedule(50);
        diagnostics.state = session ? 'restored' : 'ready';
    }
    window.DuneLocalProgress = {
        track: function (link, data) { if (!dead) begin(link, data); },
        status: function () { return JSON.parse(JSON.stringify(diagnostics)); },
        destroy: function () {
            dead = true; clearInterval(ready); clearTimeout(timer); clearTimeout(watchdog);
            if (original && Lampa.Android.openPlayer === wrapper) Lampa.Android.openPlayer = original;
            if (parseOriginal && Lampa.Torserver.parse === parseWrapper) Lampa.Torserver.parse = parseOriginal;
            window.removeEventListener('focus', wake); window.removeEventListener('pageshow', wake);
            document.removeEventListener('visibilitychange', wake); delete window.DuneLocalProgress;
        }
    };
    ready = setInterval(function () {
        if (!dead && window.appready && window.Lampa) { clearInterval(ready); init(); }
    }, 200);
})();
