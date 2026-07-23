@echo off
REM =====================================================================
REM NITRO BOOST - gera o pacote standalone (app-image) via jpackage.
REM
REM Uso: scripts\jpackage-build.bat   (rodar a partir da raiz do projeto
REM       ou de qualquer lugar - o script resolve os caminhos sozinho)
REM
REM Pre-requisito: JDK 21 (Temurin) com jpackage no PATH (vem junto do
REM JDK, nao precisa instalar nada a mais).
REM
REM O que faz:
REM   1. Compila e empacota o projeto (mvnw package), o que tambem copia
REM      todas as dependencias de runtime (JavaFX nativo, OSHI, JNA,
REM      SQLite, Jackson) para target\jpackage-input via maven-dependency-
REM      plugin (ver pom.xml).
REM   2. Copia o nitroboost.jar principal para essa mesma pasta.
REM   3. Roda o jpackage apontando para essa pasta, gerando uma pasta
REM      "NitroBoost" standalone (app-image - contem NitroBoost.exe + JVM
REM      embutida, nao precisa de Java instalado na maquina de destino)
REM      em target\dist.
REM
REM Nao gera instalador .msi/.exe de instalacao (isso exigiria o WiX
REM Toolset, nao instalado neste ambiente - ver BLOCKERS.md). O tipo
REM "app-image" gerado aqui ja e suficiente para distribuir/rodar sem
REM instalacao: basta copiar a pasta target\dist\NitroBoost inteira.
REM =====================================================================

setlocal
cd /d "%~dp0.."

echo [1/3] Compilando e empacotando o projeto (mvnw package)...
call mvnw.cmd -q clean package -DskipTests
if errorlevel 1 (
    echo [ERRO] Falha ao compilar/empacotar o projeto. Abortando.
    exit /b 1
)

echo [2/3] Copiando nitroboost.jar para target\jpackage-input...
copy /Y "target\nitroboost.jar" "target\jpackage-input\nitroboost.jar" >nul
if errorlevel 1 (
    echo [ERRO] Falha ao copiar o jar principal. Abortando.
    exit /b 1
)

echo [3/3] Gerando app-image via jpackage...
if exist "target\dist\NitroBoost" rmdir /s /q "target\dist\NitroBoost"

jpackage ^
    --type app-image ^
    --input "target\jpackage-input" ^
    --dest "target\dist" ^
    --name "NitroBoost" ^
    --main-jar "nitroboost.jar" ^
    --main-class "com.nitroboost.Launcher" ^
    --app-version "1.0.0" ^
    --vendor "NITRO BOOST" ^
    --description "Otimizacao e controle de performance do Windows 11"

if errorlevel 1 (
    echo [ERRO] jpackage falhou. Verifique se o JDK 21 com jpackage esta no PATH.
    exit /b 1
)

echo.
echo Pronto! App gerado em: target\dist\NitroBoost\NitroBoost.exe
echo Para rodar como Administrador, use run-as-admin.bat na raiz do projeto.
endlocal
