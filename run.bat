@echo off
REM ============================================================
REM  run.bat — Script de compilation et execution (Windows)
REM  SMA Gestion Incendie Foret — MadKit 5.3.1
REM ============================================================

setlocal

REM ── Configuration ────────────────────────────────────────────
set MADKIT_JAR=lib\madkit-5.3.1.jar
set SRC_DIR=src
set OUT_DIR=out
set MAIN_CLASS=sma.incendie.launcher.Main

REM ── Verification du JAR MadKit ───────────────────────────────
if not exist "%MADKIT_JAR%" (
    echo.
    echo  ERREUR : MadKit JAR introuvable dans lib\
    echo  Telechargez madkit-5.3.1.jar depuis http://www.madkit.org
    echo  et placez-le dans le dossier lib\
    echo.
    pause
    exit /b 1
)

REM ── Creation du dossier de sortie ────────────────────────────
if not exist "%OUT_DIR%" mkdir "%OUT_DIR%"

REM ── Compilation ──────────────────────────────────────────────
echo.
echo  ===  Compilation en cours...  ===
echo.

javac -encoding UTF-8 ^
      -cp "%MADKIT_JAR%" ^
      -d "%OUT_DIR%" ^
      -sourcepath "%SRC_DIR%" ^
      "%SRC_DIR%\sma\incendie\utils\AGRConstants.java" ^
      "%SRC_DIR%\sma\incendie\messages\AlerteMessage.java" ^
      "%SRC_DIR%\sma\incendie\messages\OrdreMessage.java" ^
      "%SRC_DIR%\sma\incendie\messages\RapportMessage.java" ^
      "%SRC_DIR%\sma\incendie\messages\MeteoMessage.java" ^
      "%SRC_DIR%\sma\incendie\messages\SimpleMessage.java" ^
      "%SRC_DIR%\sma\incendie\gui\SimulationGUI.java" ^
      "%SRC_DIR%\sma\incendie\agents\AgentCapteur.java" ^
      "%SRC_DIR%\sma\incendie\agents\AgentDrone.java" ^
      "%SRC_DIR%\sma\incendie\agents\AgentMeteo.java" ^
      "%SRC_DIR%\sma\incendie\agents\AgentCoordinateur.java" ^
      "%SRC_DIR%\sma\incendie\agents\AgentChefOperations.java" ^
      "%SRC_DIR%\sma\incendie\agents\AgentPompier.java" ^
      "%SRC_DIR%\sma\incendie\agents\AgentVehicule.java" ^
      "%SRC_DIR%\sma\incendie\agents\AgentHelicoptere.java" ^
      "%SRC_DIR%\sma\incendie\launcher\LauncherAgent.java" ^
      "%SRC_DIR%\sma\incendie\launcher\Main.java"

if errorlevel 1 (
    echo.
    echo  ECHEC DE LA COMPILATION — Verifiez les erreurs ci-dessus.
    echo.
    pause
    exit /b 1
)

echo.
echo  ===  Compilation reussie !  ===
echo.

REM ── Execution ────────────────────────────────────────────────
echo  ===  Lancement du SMA...  ===
echo.

java -Xmx512m ^
     -Dfile.encoding=UTF-8 ^
     -cp "%MADKIT_JAR%;%OUT_DIR%" ^
     %MAIN_CLASS%

endlocal
