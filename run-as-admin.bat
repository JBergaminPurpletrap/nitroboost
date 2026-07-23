@echo off
REM =====================================================================
REM NITRO BOOST - relanca a si mesmo elevado (UAC) e abre o aplicativo.
REM
REM A maioria das acoes do NITRO BOOST (parar servicos, editar o registro,
REM desinstalar bloatware, trocar plano de energia) exige privilegios de
REM Administrador no Windows. Este script verifica se ja esta rodando
REM elevado; se nao estiver, pede elevacao via UAC (prompt padrao do
REM Windows) e relanca a si mesmo, depois abre o NitroBoost.exe empacotado.
REM
REM Pre-requisito: o pacote ja gerado em target\dist\NitroBoost\NitroBoost.exe
REM (rode scripts\jpackage-build.bat primeiro se ainda nao existir).
REM =====================================================================

setlocal
set "EXE=%~dp0target\dist\NitroBoost\NitroBoost.exe"

REM Verifica se ja esta rodando com privilegios de administrador
net session >nul 2>&1
if %errorlevel% == 0 goto :run

echo Solicitando elevacao de administrador (UAC)...
powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
exit /b

:run
if not exist "%EXE%" (
    echo [ERRO] Nao encontrei "%EXE%".
    echo Rode primeiro: scripts\jpackage-build.bat
    pause
    exit /b 1
)

echo Iniciando NITRO BOOST como Administrador...
start "" "%EXE%"
endlocal
