set -e
. /workspace/tools/env.sh >/dev/null 2>&1
C=/workspace/tools/gradle-home/caches/modules-2/files-2.1
j(){ find $C/$1 -name "$2" ! -name "*sources*" | sort | tail -1; }
KV=2.3.21
CP_C="$(j org.jetbrains.kotlin/kotlin-compiler-embeddable kotlin-compiler-embeddable-$KV.jar):$(j org.jetbrains.kotlin/kotlin-stdlib kotlin-stdlib-$KV.jar):$(j org.jetbrains.kotlin/kotlin-script-runtime "kotlin-script-runtime-$KV.jar"):$(j org.jetbrains.kotlin/kotlin-reflect "kotlin-reflect-*.jar"):$(j org.jetbrains.kotlin/kotlin-daemon-embeddable "kotlin-daemon-embeddable-$KV.jar"):$(j org.jetbrains.intellij.deps/trove4j "trove4j*.jar"):$(j org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm "kotlinx-coroutines-core-jvm-*.jar"):$(j org.jetbrains/annotations "annotations-*.jar")"
APP=${SRC:-/workspace/work/iridis-src}/app/build/tmp/kotlin-classes/fullRelease
LIBS="${SRC:-/workspace/work/iridis-src}/app/build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/fullRelease/processFullReleaseResources/R.jar:$(j org.jetbrains.kotlin/kotlin-stdlib kotlin-stdlib-$KV.jar):$(j org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm 'kotlinx-coroutines-core-jvm-*.jar'):$(j org.jetbrains.kotlinx/kotlinx-serialization-core-jvm '*.jar'):$(j org.jetbrains.kotlinx/kotlinx-serialization-json-jvm '*.jar'):${JUNIT:-/workspace/tools/gradle-home/wrapper/dists/gradle-8.11.1-bin/bpt9gzteqjrbo1mjrsomdt32c/gradle-8.11.1/lib/junit-4.13.2.jar:/workspace/tools/gradle-home/wrapper/dists/gradle-8.11.1-bin/bpt9gzteqjrbo1mjrsomdt32c/gradle-8.11.1/lib/hamcrest-core-1.3.jar}"
AJ=$ANDROID_HOME/platforms/android-35/android.jar
OUT=/tmp/t268; rm -rf $OUT; mkdir -p $OUT
# 2.5.4: SRC_ONLY=1 只编译并运行 v254 源码断言（不依赖本版重新编译的 main 类）
if [ "${SRC_ONLY:-0}" = 1 ]; then
  T=${SRC:-/workspace/work/iridis-src}/app/src/test/java/com/iris/localai
  java -Xmx1g -cp "$CP_C" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -nowarn -jvm-target 17 -cp "$LIBS" -d $OUT $T/v254/V254Test.kt 2>&1 | grep -E "^.*error" | head -20
  cd ${SRC:-/workspace/work/iridis-src} && java -cp "$OUT:$LIBS" org.junit.runner.JUnitCore com.iris.localai.v254.V254Test 2>&1 | grep -E -A6 "^OK|^Tests run|^[0-9]+\) " | head -40
  exit 0
fi
T=${SRC:-/workspace/work/iridis-src}/app/src/test/java/com/iris/localai
java -Xmx1g -cp "$CP_C" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -nowarn -jvm-target 17 -Xfriend-paths=$APP -module-name app_fullRelease -cp "$APP:$LIBS:$AJ" -d $OUT $T/v229/*.kt $T/v2210/*.kt $T/v2212/*.kt $T/v2213/*.kt $T/v2214/*.kt $T/v2215/*.kt $T/v2216/*.kt $T/v2217/*.kt $T/v230/GuiMikaV230Test.kt $T/v230/McpV230Test.kt $T/v230/TranslateV230Test.kt $T/v231/*.kt $T/v232/*.kt $T/v233/*.kt $T/v234/*.kt $T/v235/*.kt $T/v236/*.kt $T/v240/*.kt $T/v241/*.kt $T/v242/*.kt $T/v243/*.kt $T/v244/*.kt $T/v245/*.kt $T/v246/*.kt $T/v250/*.kt $T/v251/*.kt $T/v252/*.kt $T/v253/*.kt $T/v254/*.kt $T/v255/*.kt $T/v256/*.kt $T/v257/*.kt $T/v258/*.kt $T/v259/*.kt $T/v260/*.kt $T/v261/*.kt $T/v262/*.kt $T/v263/*.kt $T/v264/*.kt $T/v265/*.kt $T/v266/*.kt $T/v267/*.kt $T/v268/*.kt $T/v269/*.kt 2>&1 | grep -E "^.*error" | head -20
CLS=$(cd $OUT && find . -name "*Test.class" ! -name '*$*' | sed 's#^\./##;s#\.class$##;s#/#.#g' | tr '\n' ' ')
cd ${SRC:-/workspace/work/iridis-src} && java -cp "$OUT:$APP:$LIBS:$AJ" org.junit.runner.JUnitCore $CLS 2>&1 | grep -E -A6 "^OK|^Tests run|^[0-9]+\) " | head -30
