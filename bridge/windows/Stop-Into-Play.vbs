' Runs Stop-Into-Play.ps1 with no window: the engine, the tunnel and the keeper stop.
' The "Stop Into Play" desktop icon points here (Install-Shortcut.ps1).
Set fs = CreateObject("Scripting.FileSystemObject")
here = fs.GetParentFolderName(WScript.ScriptFullName)
args = " -Box"
For Each a In WScript.Arguments
  args = args & " " & a
Next
CreateObject("WScript.Shell").Run "powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File """ & here & "\Stop-Into-Play.ps1""" & args, 0, False
