package com.hdlee73.englishstudy

object R {
    object drawable {
        const val app_icon = 1001
        const val dv_ic_notif = 1002
        const val ic_database = 1003
        const val ic_next = 1004
        const val ic_pause = 1005
        const val ic_play = 1006
        const val ic_previous = 1007
        const val ic_settings = 1008
        const val ic_translation = 1009
        const val stat_sys_download_done = 1010
        const val ic_launcher_foreground = 1011
        const val ic_launcher_background = 1012
        const val ls_ic_add = 1013
        const val ls_ic_arrow_down = 1014
        const val ls_ic_arrow_up = 1015
        const val ls_ic_back = 1016
        const val ls_ic_check = 1017
        const val ls_ic_clear_list = 1018
        const val ls_ic_delete = 1019
        const val ls_ic_drag = 1020
        const val ls_ic_edit = 1021
        const val ls_ic_fast_forward = 1022
        const val ls_ic_fast_rewind = 1023
        const val ls_ic_folder = 1024
        const val ls_ic_folder_add = 1025
        const val ls_ic_folder_move = 1026
        const val ls_ic_folder_open = 1027
        const val ls_ic_grid = 1028
        const val ls_ic_list = 1029
        const val ls_ic_more = 1030
        const val ls_ic_pause = 1031
        const val ls_ic_play = 1032
        const val ls_ic_refresh = 1033
        const val ls_ic_repeat = 1034
        const val ls_ic_repeat_all = 1035
        const val ls_ic_repeat_one = 1036
        const val ls_ic_settings = 1037
        const val ls_ic_shuffle = 1038
        const val ls_ic_skip_next = 1039
        const val ls_ic_skip_previous = 1040
        const val ls_ic_next = 1039
        const val ls_ic_prev = 1040
        const val ls_ic_sort = 1041
        const val ls_ic_stop = 1042
        const val ls_ic_subtitles = 1043
        const val ls_ic_timer = 1044

        fun pathForId(id: Int): String = when (id) {
            app_icon -> "drawable/app_icon.png"
            dv_ic_notif -> "drawable/dv_ic_notif.xml"
            ic_database -> "drawable/ic_database.xml"
            ic_next -> "drawable/ic_next.xml"
            ic_pause -> "drawable/ic_pause.xml"
            ic_play -> "drawable/ic_play.xml"
            ic_previous -> "drawable/ic_previous.xml"
            ic_settings -> "drawable/ic_settings.xml"
            ic_translation -> "drawable/ic_translation.xml"
            stat_sys_download_done -> "drawable/ic_database.xml"
            ic_launcher_foreground -> "drawable/ic_launcher_foreground.xml"
            ic_launcher_background -> "drawable/ic_launcher_background.xml"
            ls_ic_add -> "drawable/ls_ic_add.xml"
            ls_ic_arrow_down -> "drawable/ls_ic_arrow_down.xml"
            ls_ic_arrow_up -> "drawable/ls_ic_arrow_up.xml"
            ls_ic_back -> "drawable/ls_ic_back.xml"
            ls_ic_check -> "drawable/ls_ic_check.xml"
            ls_ic_clear_list -> "drawable/ls_ic_clear_list.xml"
            ls_ic_delete -> "drawable/ls_ic_delete.xml"
            ls_ic_drag -> "drawable/ls_ic_drag.xml"
            ls_ic_edit -> "drawable/ls_ic_edit.xml"
            ls_ic_fast_forward -> "drawable/ls_ic_fast_forward.xml"
            ls_ic_fast_rewind -> "drawable/ls_ic_fast_rewind.xml"
            ls_ic_folder -> "drawable/ls_ic_folder.xml"
            ls_ic_folder_add -> "drawable/ls_ic_folder_add.xml"
            ls_ic_folder_move -> "drawable/ls_ic_folder_move.xml"
            ls_ic_folder_open -> "drawable/ls_ic_folder_open.xml"
            ls_ic_grid -> "drawable/ls_ic_grid.xml"
            ls_ic_list -> "drawable/ls_ic_list.xml"
            ls_ic_more -> "drawable/ls_ic_more.xml"
            ls_ic_pause -> "drawable/ls_ic_pause.xml"
            ls_ic_play -> "drawable/ls_ic_play.xml"
            ls_ic_refresh -> "drawable/ls_ic_refresh.xml"
            ls_ic_repeat -> "drawable/ls_ic_repeat.xml"
            ls_ic_repeat_all -> "drawable/ls_ic_repeat_all.xml"
            ls_ic_repeat_one -> "drawable/ls_ic_repeat_one.xml"
            ls_ic_settings -> "drawable/ls_ic_settings.xml"
            ls_ic_shuffle -> "drawable/ls_ic_shuffle.xml"
            ls_ic_skip_next -> "drawable/ls_ic_skip_next.xml"
            ls_ic_skip_previous -> "drawable/ls_ic_skip_previous.xml"
            ls_ic_sort -> "drawable/ls_ic_sort.xml"
            ls_ic_stop -> "drawable/ls_ic_stop.xml"
            ls_ic_subtitles -> "drawable/ls_ic_subtitles.xml"
            ls_ic_timer -> "drawable/ls_ic_timer.xml"
            else -> "drawable/app_icon.png"
        }
    }

    object mipmap {
        const val ic_launcher = 1001
        const val ic_launcher_round = 1001
        const val ic_launcher_foreground = 1011
    }

    object raw {
        const val result_success = 2001
        const val result_incorrect = 2002

        fun pathForId(id: Int): String = when (id) {
            result_success -> "raw/result_success.wav"
            result_incorrect -> "raw/result_incorrect.wav"
            else -> "raw/result_success.wav"
        }
    }

    object layout {
        const val ls_activity_main = 3001
    }

    object style {
        const val ls_Theme_Listening = 4001
    }
}
