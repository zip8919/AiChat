#!/bin/bash
# AiChat 本地构建 + 签名 + 校验
#
# 用途：在没有 Gradle 的环境里，用 aapt2 / javac / d8 / zipalign / apksigner
#       手工复现一份可安装、可运行的 debug 包。
#
# 用法：
#   KEYSTORE_PASSWORD=xxx KEY_ALIAS=mc ./tools/build_debug.sh
#
# 环境变量：
#   ANDROID_HOME    Android SDK 根目录（默认 $HOME/android-sdk）
#   BUILD_TOOLS     构建工具版本目录名（默认取 build-tools 下最新一个）
#   PLATFORM_JAR    可选，指定 android.jar；默认 <platforms 最新>/android.jar
#   LIBS_DIR        第三方依赖目录（jar 或解包后的 classes.jar），默认 libs/
#   OUT_DIR         产物输出目录，默认 build/out
#   KEYSTORE / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD  签名参数
#
# 关键约束（改了会出问题，见 AGENTS.md）：
#   1. R.java 必须由 aapt2 link --java 生成，不能用脚本自己编号。
#      自编的 ID 与 resources.arsc 的类型段（color=01 / id=03 / layout=04 / string=06）
#      对不上，编译能过、安装能过，但一启动就 Resources$NotFoundException 闪退。
#   2. versionCode / versionName / minSdk / targetSdk 从 build.gradle 读取后显式传给
#      aapt2。手工 link 不会自动注入，缺 versionCode 的包系统直接拒装。
#   3. resources.arsc 必须 Stored（不压缩）且 4 字节对齐；targetSdk>=30 时系统强制要求。
#      其余条目正常 deflate，否则包会大一倍多。
#   4. 依赖列表要与 build.gradle 一致，不要额外打入 org.json（系统已提供）。
set -eu

# ---------- 路径 ----------
# REPO_ROOT 可覆盖；默认取脚本所在目录的上一级
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
REPO_ROOT=${REPO_ROOT:-$(cd "$SCRIPT_DIR/.." && pwd)}
ROOT="$REPO_ROOT"
SRC="$ROOT/app/src/main"
GRADLE="$ROOT/app/build.gradle"

SDK=${ANDROID_HOME:-$HOME/android-sdk}
BT_ROOT="$SDK/build-tools"
if [ -z "${BUILD_TOOLS:-}" ]; then
  BUILD_TOOLS=$(ls "$BT_ROOT" 2>/dev/null | sort -V | tail -1)
fi
BT="$BT_ROOT/$BUILD_TOOLS"
[ -x "$BT/aapt2" ] || { echo "找不到 aapt2，请设置 ANDROID_HOME / BUILD_TOOLS"; exit 1; }

if [ -z "${PLATFORM_JAR:-}" ]; then
  PJ=$(ls -d "$SDK"/platforms/android-* 2>/dev/null | sort -V | tail -1)
  PLATFORM_JAR="$PJ/android.jar"
fi
[ -f "$PLATFORM_JAR" ] || { echo "找不到 android.jar：$PLATFORM_JAR"; exit 1; }

LIBS_DIR=${LIBS_DIR:-$ROOT/libs}
OUT_DIR=${OUT_DIR:-$ROOT/build/out}
W="$OUT_DIR/.work"
rm -rf "$W"; mkdir -p "$W/classes" "$W/dex" "$W/stage" "$W/rjava" "$OUT_DIR"

: "${KEYSTORE:=$ROOT/app/keystore/mc.jks}"
: "${KEY_ALIAS:=mc}"
if [ -z "${KEYSTORE_PASSWORD:-}" ]; then
  echo "请通过环境变量提供 KEYSTORE_PASSWORD（不要写进脚本）"; exit 1
fi
: "${KEY_PASSWORD:=$KEYSTORE_PASSWORD}"

echo "== 1/9 预备 AndroidManifest =="
python3 - "$SRC/AndroidManifest.xml" "$W/AndroidManifest.xml" <<'PY'
import sys
src, dst = sys.argv[1], sys.argv[2]
s = open(src, encoding='utf-8').read()
head = s.split('>', 1)[0]
if 'package=' not in head:      # AGP 从 namespace 注入，手工 link 需要显式写
    s = s.replace('<manifest xmlns:android=',
                  '<manifest package="xyz.zip8919.app.aichat" xmlns:android=', 1)
open(dst, 'w', encoding='utf-8').write(s)
PY

echo "== 2/9 读取 gradle 真值 + 生成 BuildConfig =="
g() { grep -oE "$1[[:space:]]+[^[:space:]]+" "$GRADLE" | head -1 | awk '{print $2}' | tr -d "'\""; }
VCODE=$(g 'versionCode'); VNAME=$(g 'versionName')
MINSDK=$(g 'minSdk');      TGTSDK=$(g 'targetSdk')
APPID=$(grep -oE "applicationId[[:space:]]+'[^']+'" "$GRADLE" | sed -E "s/.*'(.*)'/\1/")
echo "   versionCode=$VCODE versionName=$VNAME minSdk=$MINSDK targetSdk=$TGTSDK appId=$APPID"
d="$W/rjava/xyz/zip8919/app/aichat"; mkdir -p "$d"
cat > "$d/BuildConfig.java" <<EOF
package xyz.zip8919.app.aichat;
public final class BuildConfig {
  public static final boolean DEBUG = true;
  public static final String APPLICATION_ID = "$APPID";
  public static final String BUILD_TYPE = "debug";
  public static final int VERSION_CODE = $VCODE;
  public static final String VERSION_NAME = "$VNAME";
  public static final String SENSENOVA_KEY = "";
}
EOF

echo "== 3/9 aapt2 compile =="
"$BT/aapt2" compile --dir "$SRC/res" -o "$W/res.zip"

echo "== 4/9 aapt2 link（同时生成真实 R.java） =="
"$BT/aapt2" link -o "$W/base.apk" -I "$PLATFORM_JAR" \
  --manifest "$W/AndroidManifest.xml" -R "$W/res.zip" \
  --min-sdk-version "$MINSDK" --target-sdk-version "$TGTSDK" \
  --version-code "$VCODE" --version-name "$VNAME" \
  --java "$W/rjava" --auto-add-overlay
R="$d/R.java"; BC="$d/BuildConfig.java"
echo "   R.java 常量数: $(grep -c 'public static final int' "$R")"

echo "== 5/9 javac =="
find "$SRC/java" -name '*.java' | sort > "$W/srcs.txt"
# 依赖来源：libs/*.jar 直接可用；libs/*.aar 需取出其中的 classes.jar 作 classpath，
# 其 assets/ 与 jni/ 会在组装阶段单独抽取；DIST_* 目录下第 3 方库自带的 classes.jar 也算。
DEPS=$(find "$LIBS_DIR" -maxdepth 1 -name '*.jar' 2>/dev/null | sort)
AARS=$(find "$LIBS_DIR" -maxdepth 1 -name '*.aar' 2>/dev/null | sort)
INNER=$(find "$LIBS_DIR" -name 'classes.jar' 2>/dev/null | sort)
for a in $AARS; do
  n=$(basename "$a" .aar)
  mkdir -p "$W/aar/$n"
  unzip -oq "$a" 'classes.jar' -d "$W/aar/$n" 2>/dev/null || true
  [ -f "$W/aar/$n/classes.jar" ] && INNER="$INNER $W/aar/$n/classes.jar"
done
INNER=$(echo $INNER | tr ' ' '\n' | sort -u | tr '\n' ' ')
# 编译期 classpath：若存在完整 android-all jar（含 isDestroyed/SUPPORTED_ABIS/org.json 等
# 真实框架实现），要放在 stub android.jar 之前，否则 javac 会报 cannot find symbol。
# 这类 jar 只给 javac 用，不能作为 d8 输入（否则 org.json 等类重复定义）。
AJ="${ANDROID_ALL_JAR:-}"
if [ -z "$AJ" ]; then
  AJ=$(find "$LIBS_DIR" -maxdepth 1 -name 'android-all*.jar' 2>/dev/null | sort | tail -1)
fi
CP=""
[ -n "$AJ" ] && [ -f "$AJ" ] && CP="$AJ:"
CP="$CP$PLATFORM_JAR"
for j in $DEPS $INNER; do CP="$CP:$j"; done
javac -source 8 -target 8 -encoding UTF-8 -nowarn -proc:none \
  -cp "$CP" -d "$W/classes" @"$W/srcs.txt" "$R" "$BC" 2>"$W/javac.err"
echo "   class 数: $(find "$W/classes" -name '*.class' | wc -l)"
echo "   javac error: $(grep -c 'error:' "$W/javac.err" || true)"
head -20 "$W/javac.err"
[ "$(grep -c 'error:' "$W/javac.err" || true)" = "0" ] || exit 1

echo "== 6/9 d8 =="
find "$W/classes" -name '*.class' > "$W/inputs.txt"
# 只打入应用类 + 第三方库；框架类（含 org.json）由系统提供。
# android-all.jar 仅供 javac 使用，绝不能作为 d8 输入，否则 org.json 等会重复定义。
for j in $DEPS $INNER; do
  [ -n "$AJ" ] && [ "$j" = "$AJ" ] && continue
  echo "$j" >> "$W/inputs.txt"
done
"$BT/d8" --min-api "$MINSDK" --lib "$PLATFORM_JAR" --output "$W/dex" @"$W/inputs.txt" \
  > "$W/d8.log" 2>&1 || { echo "d8 失败"; tail -20 "$W/d8.log"; exit 1; }
ls -la "$W/dex/classes.dex"

echo "== 7/9 组装 stage =="
cd "$W/stage"
unzip -oq "$W/base.apk"
cp "$W/dex/classes.dex" .

# 依赖库自带的 assets / jni 要一并带上（jlatexmath 的字体、conscrypt 的 so）。
# 逐个库显式抽取，缺条目不报错；这些是运行期必需资源，缺了会在渲染公式或 TLS 时崩溃。
# aar 里的目录名是 jni/，安装包内需要放到 lib/
for j in $DEPS $INNER $AARS; do
  [ -f "$j" ] || continue
  [ -n "$AJ" ] && [ "$j" = "$AJ" ] && continue    # 框架 jar 的 assets 不属于应用
  for pat in 'assets/*' 'lib/*'; do
    unzip -oq "$j" "$pat" -d . 2>/dev/null || true
  done
  unzip -oq "$j" 'jni/*' -d "$W/jnitmp" 2>/dev/null || true
done
if [ -d "$W/jnitmp/jni" ]; then
  mkdir -p lib && cp -r "$W/jnitmp/jni/." lib/
fi
# 应用自身 assets（若有）叠加在上层
if [ -d "$SRC/assets" ]; then cp -r "$SRC/assets/." .; fi
# 完整 framework jar 自带的 assets 不属于应用，清掉避免体积虚高
rm -rf assets/geoid_height_map assets/images assets/webkit assets/sounds 2>/dev/null || true
echo "   assets: $(find assets -type f 2>/dev/null | wc -l) 个文件, so: $(find lib -name '*.so' 2>/dev/null | wc -l) 个"

echo "== 8/9 打包 + 对齐 =="
rm -f "$W/merged.apk"
zip -q -r -9 "$W/merged.apk" . -x resources.arsc
zip -q -0 -X "$W/merged.apk" resources.arsc
"$BT/zipalign" -p -f 4 "$W/merged.apk" "$W/aligned.apk"
echo "   merged: $(stat -c%s "$W/merged.apk") -> aligned: $(stat -c%s "$W/aligned.apk")"
echo "   resources.arsc: $(unzip -v "$W/aligned.apk" | grep 'resources.arsc' | awk '{print $2}')"
"$BT/zipalign" -c -v 4 "$W/aligned.apk" > "$W/zipalign_check.txt" 2>&1 \
  && echo "   对齐检查: PASS" || { echo "   对齐检查: FAIL"; exit 1; }

echo "== 9/9 签名 + 校验 =="
# apksigner 的 env: 语法从进程环境读口令，因此先导出（不写入命令行，避免进 shell 历史/ps）
export KEYSTORE_PASSWORD KEY_PASSWORD
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "env:KEYSTORE_PASSWORD" --key-pass "env:KEY_PASSWORD" \
  --out "$W/signed.apk" "$W/aligned.apk"
"$BT/apksigner" verify --verbose "$W/signed.apk" | sed -n "1,5p"

echo "== 成品回读 =="
"$BT/aapt2" dump badging "$W/signed.apk" 2>/dev/null | head -3

echo "== 资源 ID 校验（dex 与资源表逐条比对） =="
python3 "$SCRIPT_DIR/dexcheck.py" "$W/dex/classes.dex" "$W/base.apk" || exit 1

OUT="$OUT_DIR/aichat-$VNAME-$VCODE.apk"
cp "$W/signed.apk" "$OUT"
echo "BUILD_OK -> $OUT  ($(stat -c%s "$OUT") 字节)"
