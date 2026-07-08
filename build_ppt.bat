@echo off
cd /d "%~dp0"
echo ============================================
echo  Building ForgeTDM_Demo_Deck.pptx
echo ============================================
echo.
echo [1/2] Installing python-pptx (one-time)...
py -m pip install python-pptx --quiet 2>nul || python -m pip install python-pptx --quiet
echo [2/2] Generating the deck...
py build_pptx.py 2>nul || python build_pptx.py
echo.
echo Done. Look for ForgeTDM_Demo_Deck.pptx in this folder.
echo (If you see a "not recognized" error above, Python isn't installed.)
echo.
pause
