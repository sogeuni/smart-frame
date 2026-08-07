# MoaBom OneDrive 설정

설정의 `표시 방식`에서 `OneDrive 사진`을 선택하면, OneDrive에 로그인한 후
사용자가 선택한 폴더에 들어 있는 이미지를 무작위 순서로 화면에 표시합니다.
`웹사이트(WebView)`를 선택하면 HTTPS URL 또는 내부 IP의 HTTP URL을 표시합니다.

## 실행 동작

- 기본적으로 30초마다 사진을 전환합니다.
- 기본적으로 15분마다 선택한 폴더를 확인하므로, 앱을 다시 시작하지 않아도
  새로 추가된 이미지를 인식합니다.
- 사진 전환 주기와 폴더 확인 주기는 설정 화면에서 변경할 수 있습니다.
- 선택한 폴더 바로 아래에 있는 이미지 파일만 표시합니다.
- 다운로드한 사진은 화면 크기에 맞게 축소하여 불러오고, 최대 약 200MB까지
  기기에 캐시합니다.

## Microsoft Entra 설정

OneDrive 접근에는 MSAL과 Microsoft Graph의 위임 권한인 `Files.Read`를
사용합니다. Android 앱에는 클라이언트 암호(Client Secret)를 넣지 마세요.

1. Microsoft Entra 관리 센터에서 앱을 등록합니다. 개인 OneDrive와 회사 또는
   학교 계정을 모두 지원하려면 조직 디렉터리 계정과 개인 Microsoft 계정을
   모두 포함하는 지원 계정 유형을 선택합니다.
2. Android 플랫폼을 추가한 후 패키지 이름을 `dev.sogn.moabom`으로
   입력하고, APK 서명에 사용한 인증서의 서명 해시를 등록합니다.
3. Microsoft Graph의 위임 권한 `Files.Read`를 추가합니다.
4. 다른 앱 등록을 사용할 경우 `app/src/main/res/raw/auth_config_single_account.json`의
   `client_id`와 `redirect_uri`를 새 등록값으로 변경합니다. `redirect_uri`의
   서명 해시는 URL 인코딩해야 합니다.
5. 같은 경우 `app/src/main/AndroidManifest.xml`의 MSAL 리디렉션 `<data>`에서
   `android:path`를 새 서명 해시로 변경합니다. Manifest에 입력하는 서명 해시는
   URL 인코딩하면 안 됩니다.

디버그 APK, 로컬 릴리스 APK, Google Play 앱 서명 APK는 각각 서명 인증서가
다를 수 있습니다. Microsoft Entra에 등록한 Android 플랫폼과 위의 두 로컬
설정값은 실제 테스트할 APK의 서명 인증서와 일치해야 합니다.

## 검증

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```
