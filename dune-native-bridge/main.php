<?php
require_once 'bridge.php';
class LampaDunePluginFw extends DunePluginFw
{
    public function call_plugin($json) {
        $ctx = json_decode($json); $cookies = isset($ctx->plugin_cookies) ? $ctx->plugin_cookies : new stdClass();
        $data = null; $type = 'gui_action';
        try {
            if ($ctx->op_type_code === 'get_vod_info') {
                $data = LampaDuneBridge::vod(LampaDuneBridge::load()); $type = 'plugin_vod_info';
            } elseif ($ctx->op_type_code === 'get_folder_view') {
                $p = LampaDuneBridge::load(); $type = 'plugin_folder_view';
                $data = array('view_kind'=>'view_movie', 'data'=>array(
                    'movie'=>LampaDuneBridge::movie($p),
                    'has_multiple_series'=>false, 'has_right_button'=>false,
                    'left_button_caption'=>'Смотреть', 'left_button_action'=>array('handler_string_id'=>'plugin_vod_play',
                        'data'=>array('vod_info'=>LampaDuneBridge::vod($p)))));
            } else {
                $input = isset($ctx->input_data) ? $ctx->input_data : new stdClass();
                $command = isset($input->command) ? $input->command : '';
                if ($command === 'play') {
                    $data = array('handler_string_id' => 'plugin_open_folder',
                    'data' => array('media_url'=>'session', 'post_action'=>array('handler_string_id'=>'plugin_vod_play',
                        'data'=>array('vod_info'=>LampaDuneBridge::vod(LampaDuneBridge::load())))));
                }
            }
            return json_encode(array('has_data'=>$data !== null, 'data_type'=>$type,
                'data'=>$data, 'plugin_cookies'=>$cookies, 'is_error'=>false));
        } catch (Exception $e) {
            return json_encode(array('has_data'=>false, 'plugin_cookies'=>$cookies, 'is_error'=>true,
                'error_action'=>array('handler_string_id'=>'plugin_show_error',
                    'data'=>array('title'=>'Lampa', 'msg_lines'=>array('Нет подготовленного видео. Запустите его из Lampa.')))));
        }
    }
}
DunePluginFw::$instance = new LampaDunePluginFw();
