@echo off
rem Compiles and runs the pure-Java tests outside Gradle.
rem
rem The class list is explicit rather than a wildcard: the compile only succeeds while these
rem classes stay free of android.* and org.json, so this script doubles as that check.
setlocal

set "ROOT=%~dp0.."
cd /d "%ROOT%"

set "JAVAC=javac"
set "JAVA=java"
where javac >nul 2>nul || (
  for %%D in (
    "D:\android\android\jbr"
    "%LOCALAPPDATA%\Programs\Android Studio\jbr"
    "C:\Program Files\Android\Android Studio\jbr"
  ) do if exist "%%~D\bin\javac.exe" (
    set "JAVAC=%%~D\bin\javac.exe"
    set "JAVA=%%~D\bin\java.exe"
  )
)

if not exist "tests\build" mkdir "tests\build"

"%JAVAC%" -encoding UTF-8 -d tests\build ^
  app\src\main\java\com\kejian\app\CourseColors.java ^
  app\src\main\java\com\kejian\app\ScheduleRules.java ^
  app\src\main\java\com\kejian\app\XlsReader.java ^
  app\src\main\java\com\kejian\app\Sheets.java ^
  tests\CourseColorsTest.java ^
  tests\ScheduleRulesTest.java ^
  tests\XlsReaderTest.java ^
  tests\CsvLimitsTest.java
if errorlevel 1 exit /b 1

"%JAVA%" -Dstdout.encoding=UTF-8 -cp tests\build CourseColorsTest || exit /b 1
"%JAVA%" -Dstdout.encoding=UTF-8 -cp tests\build ScheduleRulesTest || exit /b 1
"%JAVA%" -Dstdout.encoding=UTF-8 -cp tests\build XlsReaderTest || exit /b 1
"%JAVA%" -Dstdout.encoding=UTF-8 -cp tests\build CsvLimitsTest || exit /b 1
