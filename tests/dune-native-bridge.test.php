<?php
require (getenv('LAMPA_DUNE_SOURCE') ? getenv('LAMPA_DUNE_SOURCE') : dirname(__FILE__) . '/../dune-native-bridge') . '/bridge.php';
$checks=0;
function check($ok) { global $checks; if (!$ok) throw new Exception('assertion failed: ' . ($checks+1)); $checks++; }
$p=array('title'=>'Название','description'=>'Описание','poster'=>'https://images.example/poster',
 'items'=>array(array('url'=>'http://nas:8095/stream/a?link='.str_repeat('a',40).'&index=1','name'=>'E1'),array('url'=>'https://media.example/2','name'=>'E2')),
 'index'=>1,'position'=>12.5);
$s=LampaDuneBridge::normalize($p);$v=LampaDuneBridge::vod($s);
check($v['initial_series_ndx']===1 && $v['initial_position_ms']===12500);
check($v['poster_url']===$p['poster'] && $v['description']===$p['description']);
check(count($v['series'])===2 && $v['series'][1]['name']==='E2');
check($v['actions']===array() && !isset($v['timer']));
$m=LampaDuneBridge::movie($s);check(count($m)===17 && $m['name_original']==='' && $m['length_min']===-1);
check($v['buffering_ms']===3000 && $v['keep_playing_on_reenter']===true);
foreach(array(array('index'=>-1),array('index'=>2),array('position'=>-1),array('items'=>array()),array('items'=>array(array('url'=>'file:///private')))) as $patch){
 $bad=$p;foreach($patch as $k=>$value)$bad[$k]=$value;$caught=false;
 try{LampaDuneBridge::normalize($bad);}catch(Exception $e){$caught=true;}check($caught);
}
check(LampaDuneBridge::url("http://example/x\nheader")==='');
check(LampaDuneBridge::text("Title\0",512)==='Title');
check(json_encode(LampaDuneBridge::text(str_repeat('я',300),511))!==false);
putenv('LAMPA_DUNE_TEST_DIR=/tmp/lampa_dune_bridge_unit_test');
LampaDuneBridge::save($p);$saved=LampaDuneBridge::load();check($saved['title']===$p['title']);
unlink(LampaDuneBridge::root().'/session.json');rmdir(LampaDuneBridge::root());
echo json_encode(array('checks'=>$checks,'passed'=>true));
