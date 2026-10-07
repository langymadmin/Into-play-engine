' Runs Start-Into-Play.ps1 with no window at all - not even the console flash
' that "powershell -WindowStyle Hidden" still shows. The desktop icon points here.
Set fs = CreateObject("Scripting.FileSystemObject")
here = fs.GetParentFolderName(WScript.ScriptFullName)
args = ""
For Each a In WScript.Arguments
  args = args & " " & a
Next
CreateObject("WScript.Shell").Run "powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File """ & here & "\Start-Into-Play.ps1""" & args, 0, False
