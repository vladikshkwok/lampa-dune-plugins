const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(require('node:path').join(__dirname, '../plugins/dune-local-progress.js'), 'utf8');
const KEY = 'dune_local_progress_v1';
const HASH = 'a'.repeat(40);
const media = (index = 1, host = 'nas.lan:8095') => `http://${host}/stream/title?link=${HASH}&index=${index}&play`;
const status = (time = 12, index = 1, extra = {}) => ({product_id:'tv184a', product_name:'Pro Vision 4K',
    protocol_version:'6', player_state:'file_playback', playback_url:media(index),
    playback_position:String(time), playback_duration:'600', ...extra});
function harness(saved = {}, opts = {}) {
    let clock = 1000000, next = 0, timers = new Map(), storage = new Map(Object.entries(saved)), activeProfile = 0;
    const requests = [], updates = [], launches = [], history = new Map(), listeners = {}, shared = [];
    const settings = {torrserver_tracktimecode:true, ...opts.settings};
    const set = (fn, delay, interval) => {const id = ++next; timers.set(id, {fn, at:clock+delay, interval}); return id;};
    const clear = id => timers.delete(id);
    const Lampa = {Timeline:{
        view:hash => ({hash, profile:activeProfile, ...(history.get(hash)||{}), handler(){throw new Error('must not call cloud/TorrServer handler');}}),
        update:value => {updates.push({...value}); history.set(value.hash, {...value});}
    }, Android:{
        httpReq:(params, callbacks) => requests.push({params, callbacks}),
        openPlayer:function(...args){launches.push(args); return 'opened';}
    }, Utils:{hash:value => 'h:' + value}, Storage:{field:name => settings[name]},
    // Mirrors Lampa's parser for a name it cannot read: season from folder, episode 0.
    Torserver:{parse:data => {const season = /Season.0?(\d+)/i.test(data.path) ? Number(data.path.match(/Season.0?(\d+)/i)[1]) : 1;
        const result = {season, episode:0, serial:!!data.movie.number_of_seasons,
            hash_string:data.is_file ? data.path : [season, '', 0, data.movie.original_title].join('')};
        result.hash = 'h:' + result.hash_string; return result;},
        url:() => opts.torrserver ?? 'http://nas.lan:8098/torrserver',
        // Lampa's own viewedSet: POST <url>/viewed {action:'set', hash, file_index, timecode}.
        viewedSet:opts.viewedSet === null ? undefined : (hash, file_index, timecode, ok, fail) => {
            shared.push({action:'set', hash, file_index, timecode}); if (opts.viewedSet) opts.viewedSet();
            assert.equal(typeof ok, 'function'); assert.equal(typeof fail, 'function');}}};
    const eventTarget = {addEventListener:(name,fn)=>listeners[name]=fn,removeEventListener:name=>delete listeners[name]};
    const window = {appready:true, Lampa, location:{protocol:opts.protocol || 'http:'}, ...eventTarget};
    const context = vm.createContext({window, Lampa, AndroidJS:{}, URL,
        Date:{now:()=>clock}, localStorage:{getItem:k=>storage.get(k)||null,setItem:(k,v)=>storage.set(k,v),removeItem:k=>storage.delete(k)},
        setTimeout:(fn,d)=>set(fn,d,0),clearTimeout:clear,setInterval:(fn,d)=>set(fn,d,d),clearInterval:clear,
        document:{...eventTarget}, console});
    const tick = ms => {const end = clock+ms; let steps=0;
        while (true) {
            const entry=[...timers].filter(([,t])=>t.at<=end).sort((a,b)=>a[1].at-b[1].at)[0];
            if(!entry)break;
            if(++steps>10000)throw new Error('timer loop');
            const [id,t]=entry;clock=t.at;
            if(t.interval)t.at+=t.interval;else timers.delete(id);
            t.fn();
        }
        clock=end;
    };
    vm.runInContext(source,context);tick(200);
    const entry = (hash='101', index=1, host) => ({url:media(index,host), timeline:Lampa.Timeline.view(hash)});
    const open = (data=entry()) => Lampa.Android.openPlayer(data.url,data);
    const respond = (value, error=false) => {
        const r=requests.shift();assert.ok(r,'native request expected');
        if(error)r.callbacks.error();else r.callbacks.complite(value);
        return r;
    };
    return {window,Lampa,context,tick,entry,open,respond,requests,updates,launches,storage,history,shared,settings,
        setProfile:p=>activeProfile=p,event:name=>listeners[name]?.(),clock:()=>clock};
}
test('launch never waits for the API and every progress request is loopback',()=>{
    const h=harness();assert.equal(h.open(),'opened');assert.equal(h.launches.length,1);h.tick(50);
    assert.match(h.requests[0].params.url,/^http:\/\/127\.0\.0\.1\//);
    h.respond(null,true);assert.match(h.requests[0].params.url,/^http:\/\/127\.0\.0\.1:11080\//);
    h.respond(status());assert.equal(h.updates[0].time,12);assert.equal(h.updates[0].received,true);
});
test('public/LAN TorrServer rewrites match by torrent and file index',()=>{
    const h=harness();h.open(h.entry('101',1,'lampa.example'));h.tick(50);h.respond(status(61));
    assert.equal(h.updates[0].hash,'101');assert.equal(h.updates[0].time,61);
});
test('unrelated files and native players do not overwrite progress',()=>{
    const h=harness();h.open();h.tick(50);h.respond(status(20,2));
    h.tick(1000);h.respond(status(30,1,{playback_url:media().replace(HASH,'b'.repeat(40))}));
    assert.equal(h.updates.length,0);
});
test('stop retains last sample and does not mark the film completed',()=>{
    const h=harness();h.open();h.tick(50);h.respond(status(37));h.tick(1000);
    h.respond(status(0,1,{player_state:'navigator',playback_position:undefined,playback_duration:undefined}));
    assert.equal(h.updates.at(-1).time,37);assert.ok(h.updates.at(-1).percent<100);
    assert.equal(h.window.DuneLocalProgress.status().state,'stopped');assert.equal(h.storage.has(KEY),false);
});
test('backward seek including zero records the actual new position',()=>{
    const h=harness();h.open();h.tick(50);h.respond(status(150));h.tick(1000);h.respond(status(40));
    h.tick(1000);h.respond(status(0));assert.equal(h.updates.at(-1).time,0);
});
test('only actually opened playlist items get progress',()=>{
    const h=harness();const a=h.entry('101',1),b=h.entry('102',2),c=h.entry('103',3);
    h.open({...a,playlist:[a,b,c]});h.tick(50);h.respond(status(12,1));h.tick(1000);h.respond(status(27,3));
    assert.deepEqual([...new Set(h.updates.map(x=>x.hash))],['101','103']);
});
test('changing account profile never writes into another profile',()=>{
    const h=harness();h.open();h.tick(50);h.setProfile(7);h.respond(status(20));assert.equal(h.updates.length,0);
});
test('samples persist without media URLs, tokens or serial numbers and restore after reload',()=>{
    const h=harness();const data=h.entry();data.url='https://online.example/movie?secret=TOKEN';
    h.open(data);h.tick(50);h.respond(status(99,1,{playback_url:data.url,serial_number:'SERIAL'}));
    const saved=h.storage.get(KEY);assert.ok(saved);assert.doesNotMatch(saved,/TOKEN|SERIAL|online\.example/);
    const restored=harness({[KEY]:saved});assert.equal(restored.updates[0].time,99);
});
test('malformed or missing position/duration cannot erase a valid sample',()=>{
    const h=harness();h.open();h.tick(50);h.respond(status(50));
    for(const patch of [{playback_duration:'0'},{playback_position:null},{playback_position:'NaN'},
        {playback_position:'99999'},{playback_duration:undefined},{playback_duration:Infinity}]){
        h.tick(1000);h.respond(status(0,1,patch));
    }
    assert.equal(h.updates.length,1);assert.equal(h.updates[0].time,50);
});
test('expired waiting sessions stop even when the API cannot be reached',()=>{
    const h=harness();h.open();h.tick(190000);assert.equal(h.window.DuneLocalProgress.status().state,'expired');
    assert.equal(h.storage.has(KEY),false);assert.equal(h.launches.length,1);
});
test('late responses from an earlier launch are ignored',()=>{
    const h=harness();h.open(h.entry('101',1));h.tick(50);h.open(h.entry('102',2));
    h.respond(status(44,1));assert.equal(h.updates.length,0);h.tick(1000);h.respond(status(16,2));
    assert.equal(h.updates[0].hash,'102');
});
test('request watchdog rejects callbacks that arrive after timeout',()=>{
    const h=harness();h.open();h.tick(50);const first=h.requests.shift();h.tick(3000);
    first.callbacks.complite(status(78));assert.equal(h.updates.length,0);
    h.respond(status(9));assert.equal(h.updates[0].time,9);
});
test('destroy cancels timers and restores the original player method',()=>{
    const h=harness();h.open();h.tick(50);h.window.DuneLocalProgress.destroy();
    h.respond(status(100));h.tick(10000);assert.equal(h.updates.length,0);assert.equal(h.requests.length,0);
    h.open();assert.equal(h.launches.length,2);assert.equal(h.window.DuneLocalProgress,undefined);
});
test('installation is idempotent and creates no requests while idle',()=>{
    const h=harness();const original=h.Lampa.Android.openPlayer;vm.runInContext(source,h.context);
    h.tick(10000);assert.equal(h.Lampa.Android.openPlayer,original);assert.equal(h.requests.length,0);
});
test('ambiguous duplicate stream URLs cannot update the wrong timeline',()=>{
    const h=harness();const a=h.entry('101'),b=h.entry('102');h.open({...a,playlist:[b]});
    h.tick(1000);assert.equal(h.requests.length,0);assert.equal(h.updates.length,0);assert.equal(h.launches.length,1);
});
test('torrent episodes with Lampa-unreadable names get their own timelines',()=>{
    const h=harness(), parse=h.Lampa.Torserver.parse, movie={number_of_seasons:8, original_title:'Game of Thrones'};
    const dir='Игра Престолов (Game Of Thrones) UHD HDR/Season_01/';
    const files=[1,2].map(n=>({path:dir+'Game.of.Thrones.S01E.0'+n+'.UHD.BluRay.REMUX.2160p.TrueHD.mkv'}));
    const info=files.map(f=>parse({movie,files,filename:f.path,path:f.path}));
    assert.deepEqual(info.map(i=>[i.season,i.episode,i.hash]),[[1,1,'h:11Game of Thrones'],[1,2,'h:12Game of Thrones']]);
    for (const [name,season,episode] of [['Show.S02.E05.mkv',2,5],['Show 3x07 web.mkv',3,7],['Show - Серия 4.mkv',1,4],['Show.Ep.09.mkv',1,9]])
        assert.deepEqual((({season,episode})=>[season,episode])(parse({movie,files,filename:name,path:'x/'+name})),[season,episode]);
    const odd=['a/Pilot.mkv','a/Winter is coming.mkv'].map(path=>({path}));
    assert.notEqual(parse({movie,files:odd,path:odd[0].path}).hash,parse({movie,files:odd,path:odd[1].path}).hash);
    assert.equal(parse({movie:{original_title:'Film'},files,path:files[0].path}).hash,'h:10Film');
    h.window.DuneLocalProgress.destroy();assert.equal(h.Lampa.Torserver.parse({movie,files,path:files[0].path}).episode,0);
});
const play = (h, seconds, from = 0, extra) => {for (let i = 0; i <= seconds; i++) {if (!h.requests.length) h.tick(1000); h.respond(status(from + i, 1, extra));}};
test('TorrServer gets Lampa viewed timecode after 30 s, then every 30 s, rounded',()=>{
    const h=harness();h.open();h.tick(50);play(h,29,100);assert.deepEqual(h.shared,[]);
    h.tick(1000);h.respond(status(130.4));
    assert.deepEqual(h.shared,[{action:'set',hash:HASH,file_index:1,timecode:130}]);
    play(h,28,131);assert.equal(h.shared.length,1);h.tick(1000);h.respond(status(160.6));
    assert.deepEqual(h.shared.map(x=>x.timecode),[130,161]);assert.equal(h.window.DuneLocalProgress.status().shared,2);
});
test('a seek is shared at once, including a jump back to zero',()=>{
    const h=harness();h.open();h.tick(50);play(h,30,10);assert.deepEqual(h.shared.map(x=>x.timecode),[40]);
    h.tick(1000);h.respond(status(500));h.tick(1000);h.respond(status(0));
    assert.deepEqual(h.shared.map(x=>x.timecode),[40,500,0]);
});
test('stop shares the last real position, never the duration',()=>{
    const h=harness();h.open();h.tick(50);play(h,5,300);
    h.tick(1000);h.respond(status(0,1,{player_state:'navigator',playback_position:undefined,playback_duration:undefined}));
    assert.deepEqual(h.shared,[{action:'set',hash:HASH,file_index:1,timecode:305}]);
    assert.equal(h.updates.at(-1).time,305);
});
test('public token route and other file indexes map to the torrent hash and file index',()=>{
    const h=harness({}, {torrserver:'https://lampa.example/ts-x', protocol:'https:'});
    const data=h.entry('101',4,'lampa.example');h.open(data);h.tick(50);h.respond(status(70,4,{playback_url:data.url}));
    h.tick(1000);h.respond(status(0,4,{player_state:'navigator',playback_url:''}));
    assert.deepEqual(h.shared,[{action:'set',hash:HASH,file_index:4,timecode:70}]);
});
test('launching the next file shares the previous one before switching',()=>{
    const h=harness();h.open(h.entry('101',1));h.tick(50);h.respond(status(42));h.open(h.entry('102',2));
    assert.deepEqual(h.shared,[{action:'set',hash:HASH,file_index:1,timecode:42}]);
});
test('a restored session shares its last sample when it ends',()=>{
    const h=harness();h.open();h.tick(50);play(h,31,100);const saved=h.storage.get(KEY);
    const r=harness({[KEY]:saved});assert.deepEqual(r.shared,[]);
    r.tick(50);r.respond(status(0,1,{player_state:'navigator'}));
    assert.deepEqual(r.shared,[{action:'set',hash:HASH,file_index:1,timecode:131}]);
});
test('nothing is shared when tracking is off, on online URLs, mixed content or old Lampa',()=>{
    const cases=[[{settings:{torrserver_tracktimecode:false}}],[{torrserver:''}],
        [{protocol:'https:',torrserver:'http://192.168.3.1:8095'}],[{viewedSet:null}],[{},'https://online.example/movie.mkv']];
    for (const [opts,url] of cases) {
        const h=harness({},opts);const data=h.entry();if(url)data.url=url;h.open(data);h.tick(50);
        play(h,40,0,url?{playback_url:url}:undefined);
        h.tick(1000);h.respond(status(0,1,{player_state:'navigator'}));
        assert.deepEqual(h.shared,[],JSON.stringify(opts));assert.equal(h.updates.at(-1).time,40);
    }
});
test('a failing TorrServer write never affects local progress or polling',()=>{
    const h=harness({}, {viewedSet:()=>{throw new Error('offline');}});h.open();h.tick(50);play(h,31,0);
    assert.equal(h.shared.length,1);assert.equal(h.updates.at(-1).time,31);
    h.tick(1000);assert.equal(h.requests.length,1);h.respond(status(32));assert.equal(h.updates.at(-1).time,32);
});
