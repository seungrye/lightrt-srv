# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 개발 원칙

이 프로젝트는 **Spec 주도 + TDD** 방식으로 진행한다.

1. 기능 추가나 변경 전에 먼저 [SPEC.md](SPEC.md)에 명세를 작성하거나 수정한다.
2. 명세를 기반으로 실패하는 테스트를 먼저 작성한다.
3. 테스트를 통과시키는 구현을 작성한다.

명세 없이 구현을 먼저 작성하거나, 테스트 없이 기능을 추가하지 않는다.

## 문서

- [아키텍처](docs/architecture.md) — 컴포넌트 구조, 데이터 흐름, 핵심 설계 결정
- [개발 가이드](docs/development.md) — 빌드/테스트 명령어, 테스트 작성 패턴
- [컴포넌트 명세](SPEC.md) — 상세 컴포넌트 동작, API 명세, 테스트 전략
- [기능 및 사용법](README.md) — 앱 기능, API 사용 예제, 내장 모델 목록
- [llama.cpp Android 빌드 노트](docs/llama-cpp-android-build.md) — Vulkan 빌드 시 발생한 문제와 해결 과정, 최종 빌드 커맨드
