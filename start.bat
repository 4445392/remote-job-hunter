@echo off
rem Builds if needed, then keeps searching every few hours and serves the dashboard at http://localhost:8080
cd /d "%~dp0"
if not exist jobhunter.jar call build.bat
java -jar jobhunter.jar daemon --every-hours 3
pause
