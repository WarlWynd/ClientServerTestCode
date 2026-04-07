Dim root
root = Left(WScript.ScriptFullName, InStrRev(WScript.ScriptFullName, "\"))

Set WshShell = CreateObject("WScript.Shell")
WshShell.CurrentDirectory = root
WshShell.Run """" & root & "gradlew.bat"" :client:run", 0, False
Set WshShell = Nothing
