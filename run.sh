#!/bin/bash
# ============================================================
#  run.sh — Script de compilation et execution (Linux/macOS)
#  SMA Gestion Incendie Foret — MadKit 5.3.1
# ============================================================

MADKIT_JAR="lib/madkit-5.3.1.jar"
SRC_DIR="src"
OUT_DIR="out"
MAIN_CLASS="sma.incendie.launcher.Main"

# ── Verification du JAR ──────────────────────────────────────
if [ ! -f "$MADKIT_JAR" ]; then
    echo ""
    echo " ERREUR : MadKit JAR introuvable dans lib/"
    echo " Telechargez madkit-5.3.1.jar depuis http://www.madkit.org"
    echo " et placez-le dans le dossier lib/"
    echo ""
    exit 1
fi

mkdir -p "$OUT_DIR"

# ── Compilation ──────────────────────────────────────────────
echo ""
echo " ===  Compilation...  ==="
echo ""

find "$SRC_DIR" -name "*.java" | sort > /tmp/sources.txt

javac -encoding UTF-8 \
      -cp "$MADKIT_JAR" \
      -d "$OUT_DIR" \
      -sourcepath "$SRC_DIR" \
      @/tmp/sources.txt

if [ $? -ne 0 ]; then
    echo ""
    echo " ECHEC DE LA COMPILATION"
    echo ""
    exit 1
fi

echo ""
echo " ===  Compilation reussie ! Lancement...  ==="
echo ""

# ── Execution ────────────────────────────────────────────────
java -Xmx512m \
     -Dfile.encoding=UTF-8 \
     -cp "$MADKIT_JAR:$OUT_DIR" \
     $MAIN_CLASS
