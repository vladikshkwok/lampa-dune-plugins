<?php
require_once dirname(__FILE__) . '/../../bridge.php';
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
$method = isset($_SERVER['REQUEST_METHOD']) ? $_SERVER['REQUEST_METHOD'] : 'GET';
if ($method === 'GET') {
    echo json_encode(array('plugin'=>'lampa_dune_bridge','version'=>'1.0.1','protocol'=>1)); exit;
}
if ($method !== 'POST' || !isset($_SERVER['REMOTE_ADDR']) || !in_array($_SERVER['REMOTE_ADDR'],
    array('127.0.0.1','::1','[::1]','::ffff:127.0.0.1','[::ffff:127.0.0.1]'))) {
    header('Status: 403 Forbidden'); echo json_encode(array('error'=>'local_requests_only')); exit;
}
try {
    $raw = file_get_contents('php://input', false, null, 0, 262145);
    if (strlen($raw) > 262144) throw new Exception('payload_too_large');
    LampaDuneBridge::save(json_decode($raw, true));
    echo json_encode(array('plugin'=>'lampa_dune_bridge','launch_url'=>'plugin_launcher://{name=lampa_dune_bridge}{action_id=play}'));
} catch (Exception $e) {
    header('Status: 400 Bad Request'); echo json_encode(array('error'=>$e->getMessage()));
}
