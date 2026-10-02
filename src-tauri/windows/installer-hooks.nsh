; Stop/remove only Quark tasks pointing at this installation before uninstalling
; its executable. Other installed copies and GUI startup are untouched.
!macro NSIS_HOOK_PREUNINSTALL
  IfFileExists "$INSTDIR\mctier.exe" 0 +2
    ExecWait '"$INSTDIR\mctier.exe" --quark-background-uninstall'
!macroend
