@echo off
setlocal enabledelayedexpansion
rem Builds jobhunter.jar with only the JDK (Java 21+). Add "test" to run the self-tests first.
cd /d "%~dp0"
if exist out rmdir /s /q out
if exist out-test rmdir /s /q out-test
mkdir out
rem quoted, forward-slash paths so folders with spaces work
(for /r src\main\java %%f in (*.java) do @(set "p=%%f" & echo "!p:\=/!")) > sources.txt
javac -encoding UTF-8 -d out @sources.txt || goto :fail
xcopy /s /e /y /q src\main\resources\* out\ > nul
if "%1"=="test" (
  mkdir out-test
  (for /r src\test\java %%f in (*.java) do @(set "p=%%f" & echo "!p:\=/!")) > test-sources.txt
  javac -encoding UTF-8 -cp out -d out-test @test-sources.txt || goto :fail
  java -cp "out;out-test" jobhunter.SelfTest || goto :fail
  del test-sources.txt
)
jar --create --file jobhunter.jar --main-class jobhunter.Main -C out . || goto :fail
del sources.txt
echo Built jobhunter.jar
goto :eof
:fail
echo BUILD FAILED
exit /b 1
