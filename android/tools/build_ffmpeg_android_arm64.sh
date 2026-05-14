#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

FFMPEG_VERSION="${FFMPEG_VERSION:-n8.1.1}"
ANDROID_API="${ANDROID_API:-24}"

ABI="arm64-v8a"
ARCH="aarch64"
CPU="armv8-a"
TOOLCHAIN_ARCH="aarch64-linux-android"
HOST_TAG="darwin-x86_64"

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  echo "ERROR: 请先设置 ANDROID_NDK_HOME"
  echo "例如：export ANDROID_NDK_HOME=~/Library/Android/sdk/ndk/你的NDK版本"
  exit 1
fi

NDK="$ANDROID_NDK_HOME"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"

if [ ! -d "$TOOLCHAIN" ]; then
  echo "ERROR: 找不到 NDK toolchain: $TOOLCHAIN"
  echo "请检查 ANDROID_NDK_HOME 是否正确"
  exit 1
fi

CC="$TOOLCHAIN/bin/${TOOLCHAIN_ARCH}${ANDROID_API}-clang"
CXX="$TOOLCHAIN/bin/${TOOLCHAIN_ARCH}${ANDROID_API}-clang++"
AR="$TOOLCHAIN/bin/llvm-ar"
RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
STRIP="$TOOLCHAIN/bin/llvm-strip"
NM="$TOOLCHAIN/bin/llvm-nm"
SYSROOT="$TOOLCHAIN/sysroot"

if [ ! -f "$CC" ]; then
  echo "ERROR: 找不到编译器: $CC"
  exit 1
fi

BUILD_ROOT="$PROJECT_DIR/ffmpeg_build"
SRC_DIR="$BUILD_ROOT/src/ffmpeg"
PREFIX="$BUILD_ROOT/output/$ABI"

APP_INCLUDE_DIR="$PROJECT_DIR/app/src/main/cpp/ffmpeg/include"
APP_JNILIBS_DIR="$PROJECT_DIR/app/src/main/jniLibs/$ABI"

mkdir -p "$BUILD_ROOT/src"
mkdir -p "$PREFIX"

if [ ! -d "$SRC_DIR/.git" ]; then
  git clone https://git.ffmpeg.org/ffmpeg.git "$SRC_DIR"
fi

cd "$SRC_DIR"

git fetch --tags
git checkout "$FFMPEG_VERSION"

make distclean >/dev/null 2>&1 || true

./configure \
  --prefix="$PREFIX" \
  --target-os=android \
  --arch="$ARCH" \
  --cpu="$CPU" \
  --enable-cross-compile \
  --cc="$CC" \
  --cxx="$CXX" \
  --ar="$AR" \
  --ranlib="$RANLIB" \
  --strip="$STRIP" \
  --nm="$NM" \
  --sysroot="$SYSROOT" \
  --enable-shared \
  --disable-static \
  --enable-pic \
  --disable-programs \
  --disable-doc \
  --disable-debug \
  --disable-symver \
  --disable-avdevice \
  --enable-network \
  --enable-jni \
  --enable-mediacodec \
  --disable-autodetect \
  --disable-gpl \
  --disable-nonfree \
  --extra-cflags="-fPIC" \
  --extra-ldflags="-Wl,-z,max-page-size=16384"

CONFIG_MAK="ffbuild/config.mak"

cp "$CONFIG_MAK" "$CONFIG_MAK.bak"

sed -i.bak \
  -e 's#^SLIBNAME_WITH_MAJOR=.*#SLIBNAME_WITH_MAJOR=$(SLIBNAME)#' \
  -e 's#^SLIB_INSTALL_NAME=.*#SLIB_INSTALL_NAME=$(SLIBNAME)#' \
  -e 's#^SLIB_INSTALL_LINKS=.*#SLIB_INSTALL_LINKS=#' \
  "$CONFIG_MAK"

make -j"$(sysctl -n hw.logicalcpu)"
make install

rm -rf "$APP_INCLUDE_DIR"
rm -rf "$APP_JNILIBS_DIR"

mkdir -p "$APP_INCLUDE_DIR"
mkdir -p "$APP_JNILIBS_DIR"

cp -R "$PREFIX/include/"* "$APP_INCLUDE_DIR/"

for lib in avcodec avformat avutil swresample swscale; do
  cp "$PREFIX/lib/lib${lib}.so" "$APP_JNILIBS_DIR/"
done

"$STRIP" "$APP_JNILIBS_DIR/"*.so || true

echo ""
echo "FFmpeg Android arm64-v8a build success."
echo ""
echo "Headers:"
echo "$APP_INCLUDE_DIR"
echo ""
echo "Shared libraries:"
ls -lh "$APP_JNILIBS_DIR"
