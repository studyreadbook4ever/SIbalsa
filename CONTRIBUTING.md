<!-- SPDX-License-Identifier: Unlicense -->
# 기여

SIbalsa 원본 기여는 루트 LICENSE의 Unlicense로 제공합니다. 기여할 권리가 있는 소스만 보내고, PR에 해당 기여를 Unlicense로 제공한다는 내용을 명시하세요. 제3자 구현의 고지나 라이선스를 지워 원본 코드로 표시하지 마세요.

모델 가중치, tokenizer, 학습 dataset, 실제 대화, 개인 설정, DB, 인증서 개인키와 signing key를 제출하지 않습니다. 새로운 외부 구성 요소가 필요하면 원본 SIbalsa와 그 구성 요소의 권리 및 배포 고지를 구분해 설명합니다.

변경에 맞는 검증을 실행하고 결과와 한계를 기록합니다. 호스트 검증은 `python3 scripts/check.py --sanitizers`, Android 빌드는 `python3 scripts/build_android.py`입니다. 배포 전에는 `python3 scripts/audit_release.py`를 실행합니다. 기기 실행 조건·TLS 검증·원문 미전송·모델 그룹 및 서버별 참여 동의의 경계를 유지하세요.
