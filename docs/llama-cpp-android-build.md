# llama.cpp Android ARM64 + Vulkan 빌드 노트

llama.cpp를 Android ARM64 + Vulkan 백엔드로 직접 빌드할 때 마주친 문제와 해결 과정을 기록한다.

## 환경

| 항목 | 값 |
|------|-----|
| 호스트 OS | Arch Linux |
| NDK | r27.3 (`ndk;27.3.13750724`) — `/opt/android-sdk/ndk/27.3.13750724` |
| NDK 설치 | `/opt/android-sdk/cmdline-tools/latest/bin/sdkmanager "ndk;27.3.13750724"` |
| 타겟 ABI | `arm64-v8a` |
| 타겟 API | `android-28` (Vulkan 1.1 필요) |
| CMake | 4.3.1 |
| Ninja | 1.13.2 |
| glslc | 2026.1 (Vulkan 셰이더 컴파일러) |
| llama.cpp | b8987 (`git clone --depth=1`) |

---

## 문제 1 — `vulkan/vulkan.hpp` not found

**증상:** `ggml-vulkan.cpp` 컴파일 시 `fatal error: 'vulkan/vulkan.hpp' file not found`

**원인:** NDK sysroot에는 C API인 `vulkan.h`만 포함되어 있고, C++ 바인딩인 `vulkan.hpp`는 없다.

**시도 1 (실패):** `-DCMAKE_CXX_FLAGS="-I/usr/include"` 추가
- `/usr/include`를 크로스 컴파일 include 경로에 추가하면 glibc 호스트 헤더가 함께 딸려 들어옴
- 결과: `fatal error: 'gnu/stubs-32.h' file not found`

**시도 2 (실패):** 호스트의 `vulkan.hpp`(v1.4.341)를 NDK sysroot에 직접 복사
- 호스트 `vulkan-headers` 패키지는 v1.4.341이지만 NDK `vulkan_core.h`는 v1.3.275
- 결과: `static assertion failed: Wrong VK_HEADER_VERSION! (275 == 341)`

**시도 3 (실패):** GitHub에서 v1.3.275 `vulkan.hpp` 파일만 NDK sysroot에 복사
- 버전은 맞지만 NDK sysroot 내 기존 `vulkan_core.h`와 세부 타입 차이로 파싱 오류 발생
- 결과: `vulkan_handles.hpp:4995: error: expected ')'` (다수)

**해결:** vulkan 헤더 전체를 **별도 격리 디렉토리**에 두고 해당 경로만 CMake에 전달

```bash
mkdir -p /tmp/vulkan-headers-1.3.275
curl -Ls "https://github.com/KhronosGroup/Vulkan-Headers/archive/refs/tags/v1.3.275.tar.gz" \
  | tar -xz -C /tmp/vulkan-headers-1.3.275 --strip-components=1

# CMake 옵션에 추가
-DCMAKE_CXX_FLAGS="-I/tmp/vulkan-headers-1.3.275/include ..."
-DVulkan_INCLUDE_DIR=/tmp/vulkan-headers-1.3.275/include
```

NDK sysroot는 건드리지 않고, 격리된 디렉토리를 통해 glibc 헤더 오염 없이 vulkan.hpp를 제공한다.

---

## 문제 2 — `spirv/unified1/spirv.hpp` not found

**증상:** `ggml-vulkan.cpp` 컴파일 시 `fatal error: 'spirv/unified1/spirv.hpp' file not found`

**원인:** SPIR-V 헤더가 없음. Vulkan 백엔드가 SPIR-V 명세 헤더를 직접 참조한다.

**해결:** Arch Linux `spirv-headers` 패키지 설치 후 격리 디렉토리에 복사

```bash
sudo pacman -S spirv-headers
mkdir -p /tmp/cross-headers/spirv
cp -r /usr/include/spirv/* /tmp/cross-headers/spirv/

# CMake 옵션에 추가
-DCMAKE_CXX_FLAGS="... -I/tmp/cross-headers"
```

---

## 문제 3 — `undefined symbol: vkGetPhysicalDeviceFeatures2`

**증상:** `libggml-vulkan.so` 링크 시 `ld.lld: error: undefined symbol: vkGetPhysicalDeviceFeatures2`

**원인:** `vkGetPhysicalDeviceFeatures2`는 Vulkan 1.1 core API다. NDK의 `libvulkan.so`는 API 레벨별로 분리되어 있는데, `ANDROID_PLATFORM=android-26`을 지정하면 API 26용 `libvulkan.so`(Vulkan 1.0)를 링크한다. Vulkan 1.1은 API 28부터 보장된다.

CMake의 `FindVulkan`이 `Vulkan_LIBRARY` 캐시를 한번 설정하면 `ANDROID_PLATFORM` 변경만으로는 갱신되지 않아 API 26 라이브러리를 계속 참조했다.

**해결:**
1. `ANDROID_PLATFORM=android-28`로 변경 (Note 10+ 포함 대부분의 현대 기기에서 문제 없음)
2. `Vulkan_LIBRARY` CMake 캐시 변수를 API 28 경로로 **명시적으로** 지정

```bash
NDK_LIB=/opt/android-sdk/ndk/27.3.13750724/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android

cmake ... \
  -DANDROID_PLATFORM=android-28 \
  -DVulkan_LIBRARY=$NDK_LIB/28/libvulkan.so
```

---

## 최종 빌드 커맨드

```bash
export ANDROID_NDK=/opt/android-sdk/ndk/27.3.13750724
NDK_LIB=$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android

cmake -S /tmp/llama.cpp -B /tmp/llama.cpp/build-android \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-28 \
  -DGGML_VULKAN=ON \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=ON \
  -DLLAMA_BUILD_TESTS=OFF \
  -DLLAMA_BUILD_EXAMPLES=OFF \
  -DLLAMA_BUILD_SERVER=OFF \
  -DCMAKE_CXX_STANDARD=17 \
  -DCMAKE_CXX_FLAGS="-I/tmp/vulkan-headers-1.3.275/include -I/tmp/cross-headers" \
  -DVulkan_LIBRARY=$NDK_LIB/28/libvulkan.so \
  -DVulkan_INCLUDE_DIR=/tmp/vulkan-headers-1.3.275/include \
  -G Ninja

cmake --build /tmp/llama.cpp/build-android --config Release -j4
```

빌드 시간: i5-8250U 기준 약 35분 (`-j4`)

---

## 빌드 결과물

strip 후 `app/src/main/jniLibs/arm64-v8a/` 에 배치:

| 파일 | 크기 | 설명 |
|------|------|------|
| `libggml-vulkan.so` | 60MB | Vulkan GPU 백엔드 (SPIR-V 셰이더 600개 내장) |
| `libllama-common.so` | 3.9MB | 공통 유틸리티 |
| `libllama.so` | 2.4MB | 핵심 추론 엔진 |
| `libggml-base.so` | 1.1MB | GGML 기반 |
| `libmtmd.so` | 865KB | 멀티모달 |
| `libggml-cpu.so` | 836KB | CPU 백엔드 |
| `libggml.so` | 123KB | GGML 진입점 |

`libggml-vulkan.so`가 60MB인 이유: Vulkan 백엔드는 모든 연산 셰이더를 SPIR-V 바이트코드로 컴파일해서 바이너리에 정적 내장한다. APK 패키징 시 압축되어 실제 다운로드 크기는 절반 이하.

strip 명령어:
```bash
STRIP=$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip
for f in app/src/main/jniLibs/arm64-v8a/*.so; do
  $STRIP --strip-unneeded "$f"
done
```
