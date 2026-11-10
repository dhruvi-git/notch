@echo off
rem Launches the Notch CLI on Windows. Put this folder on your PATH.
if exist "%~dp0notch.jar" (java -jar "%~dp0notch.jar" %*) else (java -jar "%~dp0..\target\notch.jar" %*)
