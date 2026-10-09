<!-- SPDX-License-Identifier: Unlicense -->
# 검증 결과와 재현 범위

검증일: 2026-10-10 KST. 외부 모델·데이터·학습 엔진을 내려받지 않고 자체 생성한 작은 가중치로 실행 경로와 수학을 확인했습니다. 실제 사용자 대화나 인증서 개인키, 생성 모델 파일, 테스트 DB는 공개 소스와 배포 APK/AAR에 포함하지 않습니다.

## 실행한 검사

| 검사 | 결과 | 확인한 내용 |
| --- | --- | --- |
| C++ Release CTest | 통과 | causal full-forward/KV cache, float32/int8, SFT/DPO 전 파라미터 finite difference, loss 감소·생성 토큰 변화, 취소·잘못된 파일 |
| C++ ASan/UBSan CTest | 통과 | 같은 네이티브 경로의 메모리·정의되지 않은 동작 검사 |
| 실제 호스트 JVM JNI | 통과 | 모델 해시, forward, SFT, DPO reference, 핸들 종류·수명·취소 |
| Java core | 346 checks 통과 | 실행 조건, 설정·수치·wire 계약, 서버 상한, feedback 기여 ID, 취소 |
| 독립 변환 검증 | 11 tests 통과 | 생성 safetensors F32/F16/BF16, int8, tied head, strict parser, 실패 시 정리, 독립 Python transformer와 native logits 비교 |
| Coordinator/TLS | 33 tests 통과 | 실제 TLS·hostname, plaintext/구형 TLS 거부, 느린 body deadline, 동시성·replay·receipt, durable 집계·평가·승격·rollback, 70,000차원 모델 그룹 |
| Android 35 x86_64 에뮬레이터 | 49 checks 통과 | 실제 SQLite, JobScheduler constraints, foreground 전송 차단, Byte BPE/한국어 UTF-8, 실제 JNI 추론·학습·취소, 모델 해시, 서버별 consent/queue/cache |
| Android arm64-v8a / x86_64 빌드 | 통과 | 모델 없는 APK/AAR, 단일 JNI .so, 내부 C++ symbol 비공개, APK v2/v3 서명 |
| 배포 출처 검사 | 통과 | 소스·비밀정보·모델 제외, 네 가지 고지, AAR namespace/JNI keep rules, ELF LOAD·RELRO 16 KiB 정렬 |

호스트 검사는 JDK 21, Python 3.14, CMake/Ninja와 시스템 C++ 컴파일러에서 실행했습니다. Android 빌드는 JDK 21, SDK platform 35, build-tools 35.0.0, NDK 27.2.12479018을 사용했습니다. 에뮬레이터는 Android 35 x86_64이며, ARM64 기기 실행이나 실제 휴대폰 속도 측정을 대신하지 않습니다.

## 실제 Android → TLS coordinator 경로

작은 int8 모델과 rank 2 어댑터를 생성했습니다. vocabulary 258, hidden dimension 8, adapter dimension 532입니다. byte tokenizer는 이 생성 모델만을 위한 테스트 tokenizer입니다.

1. 화면 켜짐, 충전 해제, Wi-Fi 부재 상태에서 물리적 gate가 거부하는지 검사했습니다. foreground transport 거부는 DNS/요청 전에 발생하는 경로를 별도 기기 테스트로 확인했습니다.
2. 화면이 꺼지고 충전 중이며 검증된 Wi-Fi에 연결된 상태에서 실제 JobService가 SFT를 수행하고, 올바른 개발 CA·hostname으로 로컬 HTTPS coordinator에 숫자 변경분을 제출했습니다.
3. 다른 호스트 클라이언트가 같은 모델·공통 어댑터에서 실제 네이티브 SFT를 수행해 두 번째 기여를 제출했습니다. 서버는 두 기여를 집계하고 후보 version 2를 만들었습니다.
4. 별도 로컬 evaluator가 native `score`로 baseline/candidate 어댑터를 실제 생성 모델에 적용했습니다. 해당 fixture sequence의 loss는 5.6082852761 → 5.5940916105였습니다. 평가 pass 뒤에도 명시적인 `promote`가 필요했고, active version 2가 공개되었습니다.
5. 추가 라운드의 후보 version 3도 같은 평가·승격 절차로 공개했습니다. 최종 배포 APK에서 앱 프로세스를 종료한 뒤에도 등록된 작업을 유지하고 새 프로세스의 비동기 모델 초기화를 거쳐 작업이 실행되는지 확인했습니다. 새 TLS 기여가 서버에서 수락됐고 기기 DB에서 feedback 2개가 사용 처리, 대기 전송 0개, cached adapter version 3/dimension 532인 것을 확인했습니다.

유휴 상태 테스트는 전용 userdebug 에뮬레이터에서 scheduler의 유휴 이벤트를 명시적으로 발생시켰습니다. OS dump에서 CHARGING·BATTERY_NOT_LOW·STORAGE_NOT_LOW·IDLE·CONNECTIVITY가 충족된 실제 JobService 실행을 확인했습니다. foreground 조건 미충족 검사는 shell의 강제 실행을 사용했으며, 양성 경로를 제약 무시 강제 실행의 결과로만 기록하지 않았습니다. Android가 실제 휴대폰에서 언제 작업을 시작할지는 이 테스트로 보장하지 않습니다.

위 evaluator는 생성 fixture의 선택한 sequence에 대한 기계적 회귀 검증입니다. 실제 LLM의 독립 holdout 품질 평가, 한국어 대화 품질 또는 연합학습 일반화 효과를 입증하지 않습니다. 반복된 익명 기여는 서로 다른 실사용자라는 인증 근거도 아닙니다.

## 재현 명령

저장소 루트에서 실행합니다.

```sh
python3 scripts/check.py --sanitizers
python3 scripts/build_android.py
python3 scripts/audit_release.py
```

기기 검사용 APK는 별도 생성물입니다. 공개 example APK에는 테스트 모델을 넣지 않습니다.

```sh
python3 scripts/build_device_tests.py
adb -s emulator-5554 install --no-incremental -r dist/SIbalsa-example.apk
adb -s emulator-5554 install --no-incremental -r .build/android-tests/SIbalsa-device-tests.apk
adb -s emulator-5554 shell am instrument -w -e mode smoke \
  org.sibalsa.tests/org.sibalsa.example.DeviceInstrumentation
```

`am instrument` 자체의 shell exit code만 확인하지 말고 출력의 `49 checks passed`와 예외 부재를 확인합니다. bootstrap 모드는 `--development-ca`로 테스트 APK에 명시적으로 넣은 공개 CA와 `-e server https://...`가 필요합니다. 테스트 인증서·키·모델·DB는 `.build/` 등 공개에서 제외된 경로에 둡니다. scheduler와 배터리·Wi-Fi 상태 조작은 자신이 만든 테스트 에뮬레이터에서만 사용합니다.

`scripts/audit_release.py`는 공개 후보 inventory와 artifact SHA-256을 출력합니다. APK 서명은 Android SDK `apksigner verify --verbose`, ZIP 정렬은 `zipalign -c -P 16 4`로 확인했습니다. ELF 정렬은 binary header에서 검사하며, 16 KiB 기기에서의 전체 실행 시험을 완료했다고 주장하지 않습니다.

## 남은 실제 제품 검증

실제 휴대폰에서 특정 사전학습 모델을 통합한 tokens/s, 메모리, 발열·배터리, GPU/NPU, 16 KiB 환경 실행, 사용자 feedback의 품질 향상은 측정하지 않았습니다. 기본 엔진은 scalar CPU reference이고 출력층 LoRA만 학습합니다. 각 통합자는 모델·tokenizer·chat template의 정확한 호환성과 해당 모델의 권리, 독립적인 품질 평가, 실제 서버 운영 조건을 확인해야 합니다.
