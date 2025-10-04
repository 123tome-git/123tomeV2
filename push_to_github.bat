@echo off
echo ====================================
echo Pushing to GitHub: 123tomeV2
echo ====================================
echo.
echo Please enter your GitHub credentials when prompted:
echo Username: 123tome-git
echo Password: Use your Personal Access Token (NOT password)
echo.
echo Get token from: https://github.com/settings/tokens
echo.
pause
cd /d "%~dp0"
git push -u origin main
pause

