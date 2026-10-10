'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const source=fs.readFileSync(require('node:path').join(__dirname,'../plugins/dune-native-player.js'),'utf8');
const LAUNCH='plugin_launcher://{name=lampa_dune_bridge}{action_id=play}';
const PLAYLIST='http://127.0.0.1/plugins/lampa_dune_bridge/launch.m3u';
function harness(enabled=true){
    const requests=[],launches=[],timeouts=new Map(),storage={};let next=0,interval;
    const AndroidJS={openPlayer:function(...args){launches.push(args)}};
    const Lampa={Android:{httpReq:(p,c)=>requests.push({p,c}),openPlayer:(link,data)=>AndroidJS.openPlayer(link,JSON.stringify(data))},Storage:{get:(key,def)=>key==='dune_native_player_enabled'?enabled:(storage[key]===undefined?def:storage[key]),set:(key,value)=>storage[key]=value},
        Timeline:{view:()=>({time:41})},TMDB:{image:p=>'https://images.example/'+p},Activity:{active:()=>({})}};
    const window={Lampa,appready:true};
    const ctx=vm.createContext({window,Lampa,AndroidJS,URL,JSON,Number,Object,isFinite,
        setInterval:fn=>(interval=fn,1),clearInterval:()=>{},setTimeout:fn=>{timeouts.set(++next,fn);return next},clearTimeout:id=>timeouts.delete(id)});
    vm.runInContext(source,ctx);interval();
    const data=()=>({url:'http://nas:8095/stream/a.mkv?link='+'a'.repeat(40)+'&index=1',title:'File',
        timeline:{hash:123},card:{title:'Film',overview:'<b>Description</b>',poster_path:'/poster.jpg',release_date:'2020-01-01',vote_average:7.8}});
    return {window,AndroidJS,Lampa,requests,launches,timeouts,storage,data,ctx,
        open(d=data()){Lampa.Android.openPlayer(d.url,d);},ok(){requests.shift().c.complite({plugin:'lampa_dune_bridge',launch_url:LAUNCH})}};
}
test('metadata is posted only to loopback and native launch keeps original timeline',()=>{
    const h=harness();const d=h.data();h.open(d);assert.equal(h.launches.length,0);
    const r=h.requests[0];assert.match(r.p.url,/^http:\/\/127\.0\.0\.1\//);
    const body=JSON.parse(r.p.post_data);assert.equal(body.title,'Film');assert.equal(body.position,41);
    assert.equal(body.poster,'https://images.example/t/p/w500/poster.jpg');assert.match(body.description,/2020.*7\.8/);
    assert.doesNotMatch(body.description,/<b>/);h.ok();assert.equal(h.launches[0][0],PLAYLIST);
    const native=JSON.parse(h.launches[0][1]);assert.equal(native.timeline.hash,123);assert.equal(d.url,body.items[0].url);
});
test('selected series and exact playlist survive native conversion',()=>{
    const h=harness(),d=h.data(),a={url:'https://media.example/one',title:'E1'},b={url:d.url,title:'E2'};
    d.playlist=[a,b];h.open(d);const body=JSON.parse(h.requests[0].p.post_data);
    assert.equal(body.index,1);assert.equal(body.items[1].name,'E2');h.ok();
    assert.equal(JSON.parse(h.launches[0][1]).playlist,undefined);assert.equal(d.playlist.length,2);
});
test('missing companion falls back once after both local ports',()=>{
    const h=harness(),d=h.data();h.open(d);h.requests.shift().c.error();
    assert.match(h.requests[0].p.url,/127\.0\.0\.1:11080/);h.requests.shift().c.error();
    assert.equal(h.launches.length,1);assert.equal(h.launches[0][0],d.url);
});
test('hung requests have watchdogs and late callbacks cannot double launch',()=>{
    const h=harness();h.open();const first=h.requests.shift();[...h.timeouts.values()][0]();
    first.c.complite({plugin:'lampa_dune_bridge',launch_url:LAUNCH});assert.equal(h.launches.length,0);
    h.requests.shift().c.error();assert.equal(h.launches.length,1);
});
test('older async launch is discarded when another film is opened',()=>{
    const h=harness();h.open();const old=h.requests.shift();h.open();h.ok();
    old.c.complite({plugin:'lampa_dune_bridge',launch_url:LAUNCH});assert.equal(h.launches.length,1);
});
test('unsupported headers and malformed playlists retain the ordinary player path',()=>{
    for(const patch of [{headers:{Authorization:'secret'}},{playlist:[{url:'file:///secret'}]},{url:'magnet:test'}]){
        const h=harness(),d={...h.data(),...patch};h.open(d);assert.equal(h.requests.length,0);assert.equal(h.launches.length,1);
    }
});
test('disabled plugin does not contact companion',()=>{const h=harness(false);h.open();assert.equal(h.requests.length,0);assert.equal(h.launches.length,1)});
test('unexpected launch URL cannot inject another protocol',()=>{
    const h=harness();h.open();h.requests.shift().c.complite({plugin:'lampa_dune_bridge',launch_url:'file:///etc/passwd'});
    h.requests.shift().c.error();assert.match(h.launches[0][0],/^http:/);
});
test('destroy restores Java bridge and pending callbacks do not launch',()=>{
    const h=harness();h.open();const req=h.requests.shift();h.window.DuneNativePlayer.destroy();
    req.c.complite({plugin:'lampa_dune_bridge',launch_url:LAUNCH});assert.equal(h.launches.length,0);
    h.open();assert.equal(h.launches.length,1);
});
test('repeat load is idempotent',()=>{const h=harness();const f=h.Lampa.Android.openPlayer;vm.runInContext(source,h.ctx);assert.equal(h.Lampa.Android.openPlayer,f)});
test('companion on ATV port uses its own loopback playlist and tracks original HTTP data',()=>{
    const h=harness(), d=h.data(), tracked=[];
    h.window.DuneLocalProgress={track:(link,data)=>tracked.push({link,data})};
    h.open(d);h.requests.shift().c.error();h.ok();
    assert.equal(h.launches[0][0],'http://127.0.0.1:11080/plugins/lampa_dune_bridge/launch.m3u');
    assert.equal(tracked[0].link,d.url);assert.equal(tracked[0].data,d);
    assert.equal(JSON.parse(h.launches[0][1]).url,h.launches[0][0]);
});

function mapped(){
 const h=harness();h.storage.dune_native_torrserver_url='http://direct:8095/';
 h.storage.dune_native_torrserver_source='https://proxy.example/ts-private';
 const d=h.data();d.url='https://proxy.example/ts-private/stream/a.mkv?link='+'a'.repeat(40)+'&index=1&play';
 return {h,d};
}
test('direct server is read-only verified before native URLs change; original timeline URL stays intact',()=>{
 const {h,d}=mapped();d.playlist=[{url:d.url,title:'E1'},{url:d.url.replace('index=1','index=2'),title:'E2'}];h.open(d);
 const probe=h.requests.shift();assert.equal(probe.p.url,'http://direct:8095/torrents');
 assert.equal(JSON.parse(probe.p.post_data).action,'get');probe.c.complite({hash:'a'.repeat(40)});
 const body=JSON.parse(h.requests[0].p.post_data);assert.match(body.items[0].url,/^http:\/\/direct:8095\/stream\//);
 assert.match(body.items[1].url,/index=2&play$/);assert.match(d.url,/^https:\/\/proxy/);h.ok();
 assert.equal(JSON.parse(h.launches[0][1]).timeline.hash,123);
});
test('offline or wrong direct server preserves working proxy playback',()=>{
 for(const value of [null,{hash:'b'.repeat(40)}]){const {h,d}=mapped();h.open(d);h.requests.shift().c.complite(value);
 assert.equal(JSON.parse(h.requests[0].p.post_data).items[0].url,d.url);h.ok();assert.equal(h.launches.length,1);}
});
test('probe watchdog preserves proxy and ignores a late successful probe',()=>{
 const {h,d}=mapped();h.open(d);const probe=h.requests.shift();[...h.timeouts.values()][0]();
 probe.c.complite({hash:'a'.repeat(40)});assert.equal(h.requests.length,1);
 assert.equal(JSON.parse(h.requests[0].p.post_data).items[0].url,d.url);h.ok();assert.equal(h.launches.length,1);
});
test('direct mapping cannot rewrite unrelated servers or accept a prefixed direct address',()=>{
 for(const patch of [{url:'https://other.example/ts-private/stream/a?link='+'a'.repeat(40)+'&index=1&play'},{}]){
 const {h,d}=mapped();Object.assign(d,patch);if(!patch.url)h.storage.dune_native_torrserver_url='http://direct:8095/prefix';
 h.open(d);assert.match(h.requests[0].p.url,/127\.0\.0\.1/);assert.equal(JSON.parse(h.requests[0].p.post_data).items[0].url,d.url);
 }
});
