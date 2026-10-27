#!/data/data/com.termux/files/usr/bin/bash
# Compila, firma y copia el APK a Descargas.
set -e
cd "$(dirname "$0")"
ANDROID_JAR="$HOME/proyectos/android-sdk/platforms/android-36/android.jar"
CLAVE=firma.keystore
APK=docreader.apk
# Tesseract4Android 4.8.0 (Apache 2.0): clases Java y librerías nativas arm64.
TESS=terceros/tess
# PdfBox-Android 2.0.27.0 (Apache 2.0): escritura de PDF para las firmas.
PDFBOX=terceros/pdfbox
rm -rf build && mkdir -p build/clases build/dex

if [ ! -f "$CLAVE" ]; then
    keytool -genkeypair -keystore "$CLAVE" -storepass android -keypass android \
        -alias lectorpdf -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=lectorpdf" >/dev/null 2>&1
fi

aapt2 compile --dir res -o build/recursos.zip
aapt2 link -o build/base.apk -I "$ANDROID_JAR" --manifest AndroidManifest.xml -A assets -A "$PDFBOX/assets" \
    --min-sdk-version 29 --target-sdk-version 34 build/recursos.zip
if ! javac -nowarn -source 11 -target 11 -classpath "$ANDROID_JAR:$TESS/classes.jar:$PDFBOX/classes.jar" -d build/clases \
    $(find src -name '*.java') 2>build/javac.log; then
    cat build/javac.log
    exit 1
fi
d8 --lib "$ANDROID_JAR" --min-api 29 --output build/dex $(find build/clases -name '*.class') \
    "$TESS/classes.jar" "$PDFBOX/classes.jar" 2>&1 \
    | grep -v "androidx.annotation\|bouncycastle\|^Warning\|^$\|Missing class\|referenced from" || true
[ -f build/dex/classes.dex ] || { echo "d8 falló"; exit 1; }
(cd build/dex && aapt add ../base.apk classes.dex >/dev/null)
mkdir -p build/nativas/lib/arm64-v8a
cp "$TESS"/jni/arm64-v8a/*.so build/nativas/lib/arm64-v8a/
(cd build/nativas && aapt add ../base.apk lib/arm64-v8a/*.so >/dev/null)
zipalign -f 4 build/base.apk build/alineado.apk
apksigner sign --ks "$CLAVE" --ks-pass pass:android --key-pass pass:android \
    --out "build/$APK" build/alineado.apk
apksigner verify "build/$APK"
echo "APK: $PWD/build/$APK"
if [ -d "$HOME/storage/downloads" ]; then
    cp "build/$APK" "$HOME/storage/downloads/$APK"
    echo "Copiado a Descargas/$APK"
fi
