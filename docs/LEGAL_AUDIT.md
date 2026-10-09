<!-- SPDX-License-Identifier: Unlicense -->
# 공개 전 라이선스·출처·비밀정보 검토

검토일: 2026-10-10, 대한민국 시간. 대상은 이 저장소의 공개 예정 소스와 `scripts/build_android.py`가 만든 모델 없는 `SIbalsa.aar`, `SIbalsa-example.apk`입니다. 실제 사용자 데이터 수집 서비스의 운영 허가, 외부 기반 모델의 권리 확인, 상표·특허 전체 조사까지 완료했다는 문서는 아닙니다.

현재 소스와 아래 hash의 최종 배포물 검토에서 공개를 막을 제3자 구현 코드, 사전학습 모델, 실제 사용자 데이터 또는 비밀키는 발견하지 않았습니다. 이 결론은 아래에서 확인한 파일과 배포 방식에 대한 검토 결과입니다. 최종 공개 직전에는 최신 소스와 재빌드한 배포물에 대해 `python3 scripts/audit_release.py`가 다시 성공해야 합니다. 파일이 바뀌면 이전 검사 출력의 inventory hash나 APK/AAR hash를 새 파일의 검증값으로 사용하지 않습니다.

## 원본 소스의 Unlicense

루트 [LICENSE](../LICENSE)는 [공식 Unlicense 문안](https://unlicense.org/)의 권리 포기·사용 허용·무보증 문안을 사용합니다. Java, C++, Python, shell, XML, CMake, 테스트 및 이 저장소에서 작성한 문서는 이 라이선스를 적용합니다. 구현 소스에는 `SPDX-License-Identifier: Unlicense`가 있으며, [OSI의 Unlicense 항목](https://opensource.org/license/unlicense)에서도 이 식별자와 승인 정보를 확인할 수 있습니다.

라이선스 적용 대상으로 삼은 것은 SIbalsa의 자체 구현입니다. 외부 엔진이나 학습 프레임워크를 복사해 이름만 바꾸거나 기존 라이선스를 제거한 구조를 발견하지 않았습니다. 구현 담당자들은 이 작업에서 작성한 소스이며 제3자 구현 snippet이나 다운로드한 모델을 복사하지 않았다고 확인했습니다. 추론, LM-head LoRA 학습, tokenizer 처리, HTTP protocol 및 coordinator는 이 프로젝트의 소스로 구현되어 있습니다. native SHA-256은 모델 파일의 무결성·식별자를 계산하는 자체 구현이며, 표준 알고리즘 상수의 존재를 외부 TLS 구현을 포함했다는 의미로 해석하지 않습니다. TLS·인증서 검증은 Android/Java 및 Python 플랫폼을 사용합니다.

원본 소스의 Unlicense는 외부 저작물의 권리를 변경하지 않습니다. 이후 기여자가 제3자 코드를 추가하거나 다른 라이선스의 모델·tokenizer·dataset을 넣으면 해당 파일과 권리에 대한 검토를 다시 해야 합니다. 이 문서는 AI로 작성한 모든 코드의 독창성이나 전 세계에서의 무침해를 증명하는 문서가 아닙니다.

## 런타임과 빌드 출처

| 구성 요소 | 확인한 출처와 배포 범위 |
| --- | --- |
| Java SDK 및 example | Java 표준 API, Android 플랫폼 API, 저장소의 `ai.sibalsa` / `org.sibalsa` 클래스. 별도 HTTP·UI·telemetry·FL SDK 없음 |
| C++ inference/training | 저장소의 C++17 구현. CMake에서 외부 엔진을 내려받거나 링크하지 않음 |
| Python coordinator·converter·build/test scripts | Python 표준 라이브러리와 저장소 내부 모듈. pip runtime dependency 없음 |
| Android SDK/NDK | 설치된 공식 Android 도구를 사용. NDK 27.2.12479018의 `c++_static` 및 compiler runtime이 단일 Android JNI binary에 포함됨 |
| Android system libraries | device가 제공하는 `libc`, `libdl`, `libm` 등 플랫폼 library. 외부 inference/training engine을 배포하지 않음 |
| GitHub Actions | 호스트 검증용 CI 도구 호출. Action 구현을 앱·AAR에 포함하지 않음 |

“외부 엔진 dependency 없음”은 모든 배포 byte가 Unlicense라는 뜻이 아닙니다. Android NDK의 libc++/compiler runtime에는 별도 라이선스가 유지됩니다. [Android NDK 공식 설명](https://developer.android.com/ndk/guides/cpp-support)은 libc++의 static/shared 형태와 LLVM 라이선스를 설명하며, [LLVM의 공식 LICENSE.TXT](https://github.com/llvm/llvm-project/blob/main/llvm/LICENSE.TXT)에는 Apache 2.0 및 LLVM Exceptions 등 해당 조건이 있습니다.

Android는 두 논리 runtime의 JNI를 단일 `libsibalsa_android.so`에 묶고 내부 C++ exports를 숨깁니다. 이는 NDK가 설명하는 여러 shared library의 static C++ runtime 중복 문제를 피하기 위한 packaging입니다. 추론·학습 객체의 독립성을 물리적 library 파일 두 개와 동일시하지 않습니다. 다른 native SDK와 함께 쓰는 통합 환경의 전체 binary 구성은 통합자가 다시 확인해야 합니다.

빌드 스크립트는 다음 파일을 APK/AAR의 `assets/notices/`에 넣습니다. 원본 소스를 Unlicense로 공개하면서 이 제3자 고지를 제거하거나 Unlicense로 다시 표시하지 않습니다.

| 배포물 entry | 원본 |
| --- | --- |
| `SIbalsa-Unlicense.txt` | 저장소 `LICENSE` |
| `NOTICE.md` | 저장소 [NOTICE.md](../NOTICE.md) |
| `Android-NDK-NOTICE.txt` | 사용한 공식 NDK의 `NOTICE` |
| `Android-NDK-NOTICE.toolchain.txt` | 사용한 공식 NDK의 `NOTICE.toolchain` |

최종 APK/AAR에서 위 네 가지 고지의 사본을 각각 원본과 byte 단위로 비교해 일치를 확인했습니다. native SHA-256 구현을 정확히 설명하는 최신 `NOTICE.md`가 포함되어 있습니다. 실제 확인한 배포물의 hash는 아래 최종 배포물 절에서 확인합니다.

빌드 도구 전체를 저장소에 재배포하지 않습니다. 공식 SDK/NDK, Java, Python, CMake, Ninja 및 CI 실행 환경 자체의 라이선스는 각 제공자의 조건을 따릅니다. `dist/`, `.build/`의 생성물과 로컬 debug signing key는 소스 공개 대상에서 제외됩니다. example APK의 debug 서명은 개인 설치·검증 용도이며 앱 마켓용 release signing identity를 제공하지 않습니다.

## 모델·tokenizer·데이터 경계

공개 소스와 검사한 APK/AAR에는 사전학습 모델, 다운로드된 tokenizer, 사용자 history, 학습 dataset, private integration bundle을 포함하지 않았습니다. 배포물의 assets는 위 네 가지 고지뿐입니다. 테스트는 일시 디렉터리에서 작은 deterministic synthetic 모델과 예시 문자열을 생성합니다. 이것을 실제 사용자 데이터나 사전학습 대화 모델로 공개하지 않습니다.

`convert_model.py`는 사용자가 이미 보유한 파일을 읽는 변환 도구입니다. 특정 모델을 다운로드하거나 사용권을 주지 않습니다. Hugging Face Llama 형식 이름과 tensor mapping은 호환 조건을 기술하며, Meta 등의 모델 사용·재배포 허락을 대신하지 않습니다. 외부 weights, tokenizer, chat template과 이에 기반한 adapter의 사용·변형·재배포·서비스 제공 조건은 통합자가 해당 출처의 실제 라이선스로 확인해야 합니다. 외부 모델과 함께 이 프로젝트를 재배포할 경우 이번 모델 없는 배포물에 대한 검토를 그대로 적용할 수 없습니다.

공개 파일 목록에서 `.sbm`, `.sib`, `.siblora`, `.safetensors`, `.gguf`, `.sibalsa`, DB, 인증서 개인키, keystore 및 tokenizer 파일을 제외합니다. 단순 `.gitignore` 존재만 확인한 것이 아니라 `git ls-files --cached --others --exclude-standard`의 실제 공개 후보 목록을 검사했습니다. 추적된 파일은 ignore 규칙이 생겨도 자동 제거되지 않으므로 최종 inventory 검사를 유지해야 합니다.

## 비밀정보·출처 검사 결과

`scripts/audit_release.py --source-only`는 공개 후보 전체에 대해 symlink·binary/NUL·금지 확장자·local configuration·일반적인 private key/token 패턴 및 구현 파일의 SPDX 표시를 검사했습니다. 별도의 읽기 검사로 GitHub/OpenAI/AWS/Google/Slack key와 자격 증명이 든 URL 패턴을 추가 확인했습니다. 자격 URL 검사 한 건은 `CoreTest.java`가 자격 증명 URL을 거부하는지 시험하는 `localhost`의 가짜 값이었으며, 배포용 자격 증명이 아닙니다. 발견된 실제 비밀정보는 없었습니다. 패턴 검사가 모든 종류의 비밀정보 부재를 수학적으로 증명하지는 않습니다.

소스의 import/include, CMake/build 경로 및 배포 archive 목록을 검토했습니다. 다운로드·vendoring된 구현, 예상 외 Java package, 모델·DB·키가 들어간 assets를 발견하지 않았습니다. NDK 고지는 실제 사용한 설치본과 비교했습니다. test 중 생성된 TLS 인증서와 key, coordinator DB, 모델·adapter 파일은 로컬 생성 디렉터리에 있고 공개 inventory에 포함하지 않습니다.

## GitHub 공개와 실제 서비스 운영의 차이

이번 검토의 결론은 모델·사용자 데이터가 없는 자체 소스 공개와 설명된 reference binary의 배포 범위에 적용합니다. 소스 저장소를 공개하는 행위와 실제 앱/FL coordinator를 운영해 사용자 기여를 받는 행위는 검토 대상이 다릅니다. Unlicense와 FL 구조가 서비스의 개인정보·AI 관련 의무를 면제하지 않습니다.

이 구현은 질문·답변·수정 답변·평가 사유를 기기 내부에 두고, 별도 opt-in과 HTTPS origin/model group 경계로 공동학습 참여를 제한합니다. 서버가 받는 payload는 임시 lease, nonce, proof, 수치 counter와 delta입니다. 그러나 TLS 종료점인 서버는 개별 delta와 네트워크 접속 IP를 볼 수 있습니다. 보안 집계와 차등프라이버시는 구현하지 않았고 업데이트가 언제나 익명정보라고 판정하지 않습니다. 한국 개인정보 보호법의 적용 제외는 다른 정보와 기술·비용 등을 고려해 개인을 더 이상 식별할 수 없는지를 기준으로 하므로, 실제 운영자는 결합 가능성과 운영 로그까지 평가해야 합니다. [개인정보 보호법 제58조의2](https://law.go.kr/lsLinkCommonInfo.do?lsJoLnkSeq=1022694479).

실제 처리가 개인정보 처리에 해당하면 그 운영의 처리 근거, 고지, 보관·삭제 및 안전조치를 정해야 합니다. 국외 운영자·서버를 사용하는 경우에는 직접 수집인지, 제공·위탁·보관 등의 국외 이전인지와 처리 관계를 먼저 확인하고 적용되는 조건을 검토해야 합니다. 이 코드의 opt-in 화면만으로 모든 운영에 필요한 적법한 동의가 완성되었다고 판단하지 않습니다. [개인정보 보호법 현행 본문](https://www.law.go.kr/lsInfoP.do?lsiSeq=283839), [제28조의8 국외 이전](https://www.law.go.kr/LSW/lsLinkCommonInfo.do?chrClsCd=010202&lsJoLnkSeq=1034292881).

생성형 AI를 이용한 제품·서비스를 인공지능사업자로서 제공하는 경우에는 사전 고지와 생성 결과 표시 등 적용 의무를 확인해야 합니다. GitHub 소스 공개만을 실제 생성형 서비스 운영과 동일하게 취급하거나, 반대로 오픈소스라는 이유만으로 모든 운영이 제외된다고 단정하지 않습니다. [인공지능기본법 제31조](https://www.law.go.kr/LSW/lsLinkCommonInfo.do?chrClsCd=010202&lsJoLnkSeq=1031810723).

사용자 feedback에 제3자의 개인정보·저작물이 들어갈 수 있고 adapter에도 학습 정보가 반영될 수 있으므로 외부 모델과 실제 데이터를 연결한 운영은 별도 검토가 필요합니다. 이 저장소의 원문 미전송·무로그 기본값과 엄격한 protocol은 데이터 노출을 줄이는 구현 선택이며, 법적 익명성·완전한 poisoning 방어·외부 권리 무침해의 보장은 아닙니다.

## 최종 배포물 확인

최신 NOTICE, 단일 Android JNI library, 16 KiB ELF 정렬을 포함한 최종 재빌드에 대해 다음 검사가 성공했습니다. source inventory hash는 이 문서 안의 자체 참조 hash 대신 최종 명령 출력과 해당 공개 commit에서 확인합니다.

```sh
python3 scripts/audit_release.py
```

| 배포물 | bytes | SHA-256 |
| --- | ---: | --- |
| `SIbalsa.aar` | 1615640 | `f655060d4625f020a94948df2bc5c4de7eab10801a0f16d631154e2a34220bb2` |
| `SIbalsa-example.apk` | 1606522 | `0288a7c745c7c809eda6656a127cb6dcf55664bb37dbe896d7eafc391909aa40` |

자동 검사 외에 두 배포물에서 native ELF를 직접 추출해 공식 NDK `llvm-readelf`로 확인했습니다. 각 배포물의 arm64-v8a/x86_64에는 `libsibalsa_android.so` 하나씩만 있으며, exported symbol은 JNI entry 17개이고 C++ exports는 없습니다. dynamic dependency는 `libm.so`, `libdl.so`, `libc.so`뿐입니다. AAR의 class 50개는 프로젝트 namespace에 속하고 예상 외 Java package는 없었습니다. APK manifest는 min SDK 26, target SDK 35 및 문서에 기재된 세 권한만 요청하는 debug example입니다.

각 ABI의 LOAD segment는 16384 byte 정렬이고 offset/virtual address의 정렬 조건을 만족했습니다. GNU_RELRO의 virtual address와 memory size를 합친 끝점도 16384 byte 경계이며, `zipalign -c -P 16 4` 검사가 성공했습니다. 이는 [Android 공식 page-size 가이드](https://developer.android.com/guide/practices/page-sizes)의 binary 정렬 조건을 확인한 것이며, 실제 16 KiB 기기에서 전체 앱을 실행했다는 결과는 아닙니다. 상세한 실행 범위는 [VALIDATION.md](VALIDATION.md)에 기록합니다.

이 검사는 공개 후보 inventory hash와 두 배포물의 SHA-256을 출력합니다. 원본 LICENSE/NOTICE의 byte 일치, 필요한 ABI 두 가지의 JNI libraries, AAR namespace와 consumer keep rules, 예상 외 Java package, model/private artifact 제외를 확인합니다. 확인한 범위에서 소스 및 이 모델 없는 배포물 공개를 차단할 미해결 항목은 발견하지 않았습니다. 새 모델·dependency·운영 데이터·서명 방식이 추가되면 이 결론의 대상이 달라지므로 검토를 다시 해야 합니다.
