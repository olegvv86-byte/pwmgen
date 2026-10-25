@echo off
echo === Сборка POBEDA.exe ===
if exist Build rmdir /s /q Build
if exist obj rmdir /s /q obj
if exist bin rmdir /s /q bin
dotnet publish POBEDA.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -o Build
echo.
echo Готово. Скопируйте только один файл:
echo   Build\POBEDA.exe
echo .NET ставить не нужно. Нужен WebView2 (есть в Win10/11).
echo.
pause
