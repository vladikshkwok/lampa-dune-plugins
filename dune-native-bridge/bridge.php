<?php
// PHP 5.3 compatible; no SDK redistribution, daemon or external metadata service.
class LampaDuneBridge
{
    public static function root() {
        $p = getenv('LAMPA_DUNE_TEST_DIR');
        return $p ? $p : '/tmp/lampa_dune_bridge';
    }
    public static function text($v, $limit) {
        if (!is_string($v)) return '';
        $v = preg_replace('/[\x00-\x08\x0b\x0c\x0e-\x1f]/', '', $v);
        if (function_exists('mb_strcut')) return mb_strcut($v, 0, $limit, 'UTF-8');
        $v = substr($v, 0, $limit);
        while (strlen($v) && json_encode($v) === false) $v = substr($v, 0, -1);
        return $v;
    }
    public static function url($v) {
        if (!is_string($v) || strlen($v) > 16384 || preg_match('/[\x00-\x20]/', $v)) return '';
        $p = parse_url($v);
        return $p && isset($p['host']) && isset($p['scheme']) &&
            in_array(strtolower($p['scheme']), array('http', 'https')) ? $v : '';
    }
    public static function normalize($p) {
        if (!is_array($p) || !isset($p['items']) || !is_array($p['items']) ||
            count($p['items']) < 1 || count($p['items']) > 250) throw new Exception('invalid_playlist');
        $items = array();
        foreach ($p['items'] as $i) {
            if (!is_array($i) || !isset($i['url']) || !self::url($i['url'])) throw new Exception('invalid_url');
            $items[] = array('url' => $i['url'], 'name' => self::text(isset($i['name']) ? $i['name'] : '', 512));
        }
        $index = isset($p['index']) ? $p['index'] : 0;
        if (!is_int($index) || $index < 0 || $index >= count($items)) throw new Exception('invalid_index');
        $position = isset($p['position']) ? $p['position'] : 0;
        if (!is_numeric($position) || !is_finite((float)$position) || $position < 0 || $position > 604800) throw new Exception('invalid_position');
        return array('title' => self::text(isset($p['title']) ? $p['title'] : '', 512),
            'description' => self::text(isset($p['description']) ? $p['description'] : '', 16000),
            'poster' => self::url(isset($p['poster']) ? $p['poster'] : ''),
            'items' => $items, 'index' => $index, 'position' => (float)$position,
            'created' => time());
    }
    public static function save($p) {
        $p = self::normalize($p); $dir = self::root();
        if (!is_dir($dir) && !mkdir($dir, 0700, true)) throw new Exception('storage_unavailable');
        // Dune's HTTP CGI runs as root on some firmware, while the GUI PHP worker
        // runs as the plugin owner. Keep the private session readable by that owner.
        $owner = fileowner(__FILE__);
        if (fileowner($dir) !== $owner && !chown($dir, $owner)) throw new Exception('storage_owner');
        chmod($dir, 0700);
        $json = json_encode($p);
        if ($json === false) throw new Exception('invalid_text');
        $tmp = tempnam($dir, 'session-');
        if (!$tmp) throw new Exception('storage_unavailable');
        if (file_put_contents($tmp, $json) === false) { unlink($tmp); throw new Exception('storage_unavailable'); }
        chmod($tmp, 0600);
        if (fileowner($tmp) !== $owner && !chown($tmp, $owner)) { unlink($tmp); throw new Exception('storage_owner'); }
        if (!rename($tmp, $dir . '/session.json')) { unlink($tmp); throw new Exception('storage_unavailable'); }
        return $p;
    }
    public static function load() {
        $file = self::root() . '/session.json';
        $p = is_file($file) ? json_decode(file_get_contents($file), true) : null;
        if (!$p || !isset($p['created']) || time() - $p['created'] > 43200) throw new Exception('no_recent_session');
        return $p;
    }
    public static function vod($p) {
        $series = array();
        foreach ($p['items'] as $i) $series[] = array('name' => $i['name'],
            'playback_url' => $i['url'], 'playback_url_is_stream_url' => true);
        return array('id' => 'lampa.' . $p['created'], 'name' => $p['title'], 'description' => $p['description'],
            'poster_url' => $p['poster'], 'series' => $series,
            'initial_series_ndx' => $p['index'], 'initial_position_ms' => (int)round($p['position'] * 1000),
            'buffering_ms' => 3000, 'keep_playing_on_reenter' => true, 'skip_dummy_player_on_error' => false,
            'actions' => array());
    }
    public static function movie($p) {
        // Native movie views require the complete SDK record, including empty strings.
        $m = array('name'=>$p['title'], 'description'=>$p['description'], 'poster_url'=>$p['poster'],
            'length_min'=>-1, 'year'=>0, 'details'=>null, 'rate_details'=>null);
        foreach (array('name_original','directors_str','scenarios_str','actors_str','genres_str',
            'rate_imdb','rate_kinopoisk','rate_mpaa','country','budget') as $key) $m[$key] = '';
        return $m;
    }
}
