package com.hdlee73.englishstudy.docvoice.core

/**
 * ffmpeg 실행 파일 탐지. m4a/webm/ogg 처럼 순수 JVM 으로는 풀 수 없는 형식을 다룰 때만 쓴다.
 * 설치돼 있지 않으면 [available] 이 false 이고, 호출한 쪽에서 사용자에게 분명한 메시지를 보여 준다.
 */
internal object Ffmpeg {
    /** PATH 에서 찾은 `ffmpeg` 실행 이름. 없으면 null. 최초 1회만 확인한다. */
    val command: String? by lazy {
        runCatching {
            val proc = ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start()
            proc.inputStream.use { it.readBytes() }
            if (proc.waitFor() == 0) "ffmpeg" else null
        }.getOrNull()
    }

    val available: Boolean get() = command != null
}
