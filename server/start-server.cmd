@echo off
setlocal
cd /d "%~dp0"
set "PYTHONPATH=%~dp0vendor"
if defined KEJIAN_PYTHON goto custom
if exist "%~dp0.venv\Scripts\python.exe" (
  "%~dp0.venv\Scripts\python.exe" server.py
  goto finished
)
if exist "%USERPROFILE%\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe" (
  "%USERPROFILE%\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe" server.py
  goto finished
)
where py >nul 2>nul
if not errorlevel 1 (
  py -3 -c "import xlrd" >nul 2>nul
  if errorlevel 1 goto dependencies
  py -3 server.py
  goto finished
)
where python >nul 2>nul
if errorlevel 1 goto missing
python -c "import xlrd" >nul 2>nul
if errorlevel 1 goto dependencies
python server.py
goto finished
:custom
"%KEJIAN_PYTHON%" server.py
goto finished
:dependencies
echo Please install dependencies first: python -m pip install -r requirements.txt
pause
exit /b 1
:missing
echo Python 3.10+ is required. Install Python and enable Add Python to PATH.
pause
exit /b 1
:finished
if errorlevel 1 (
  echo Server stopped with an error. Review the message above.
  pause
  exit /b 1
)
