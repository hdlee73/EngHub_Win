# EngHub for Windows

영어 학습 앱 **EngHub**의 Windows 데스크톱 버전입니다. 안드로이드 버전([hdlee73/EngHub](https://github.com/hdlee73/EngHub))과 같은 소스·같은 화면 구성·같은 아이콘을 쓰며, 안드로이드 버전 번호를 따라갑니다.

- 앱 이름 / 버전: EngHub / 1.32.0 (`build.gradle.kts`의 `appVersion` 한 곳에서 관리)
- 만든이: 이현덕 (hdlee73@gmail.com)
- 업데이트: 앱 정보 창에서 이 저장소의 최신 릴리스를 확인하고, 새 버전이 있으면 설치 파일(.msi)을 받아 바로 설치합니다. 릴리스 내용은 [RELEASE_NOTES.md](RELEASE_NOTES.md)에 있습니다.

## 메뉴
Words(단어·사전) · Reading · Flashcards · Quiz · Phrases · Translate · Speaking · Listening(MP3 플레이어, 자막) · DocVoice(문서↔MP3, 자막 만들기)

- 뒤로가기 = **Esc**
- 설정·학습 기록은 `%USERPROFILE%\.enghub` 에 저장됩니다.
- 시작 중 오류가 나면 안내 창이 뜨고 `%USERPROFILE%\EngHub_crash.log` 에 기록됩니다.

## 안드로이드 버전과 다른 점
- 음성 받아쓰기: Windows 음성 인식(SAPI) 사용 (안드로이드는 Whisper). MP3 변환은 ffmpeg가 필요합니다.
- 업데이트는 Play 스토어가 아니라 GitHub 릴리스의 `.msi` 설치 파일입니다.

## 빌드
```
./gradlew.bat run                 # 실행
./gradlew.bat test                # 단위 테스트
./gradlew.bat createDistributable # build/compose/binaries/main/app/EngHub/EngHub.exe
./gradlew.bat packageMsi          # 설치 파일
```
`v*` 태그를 올리면 GitHub Actions가 `.msi`와 `.zip`을 만들어 릴리스에 올립니다. (JDK 21 필요)
