# Torrenta's desktop notification on Windows: a toast through the WinRT notifications API.
# The text arrives as parameters, never inside this script, and goes into the XML as text
# nodes, so no name can become markup. Run with -NoProfile -NonInteractive -File.
param(
    [string]$Title = "Torrenta",
    [string]$Message = ""
)
$ErrorActionPreference = "Stop"
[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
[Windows.UI.Notifications.ToastNotification, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
[Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime] | Out-Null

$xml = [Windows.UI.Notifications.ToastNotificationManager]::GetTemplateContent(
    [Windows.UI.Notifications.ToastTemplateType]::ToastText02)
$texts = $xml.GetElementsByTagName("text")
$texts.Item(0).AppendChild($xml.CreateTextNode($Title)) | Out-Null
$texts.Item(1).AppendChild($xml.CreateTextNode($Message)) | Out-Null

# A toast needs a registered application id; PowerShell's own is on every Windows 10 and 11.
$appId = "{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}\WindowsPowerShell\v1.0\powershell.exe"
$toast = [Windows.UI.Notifications.ToastNotification]::new($xml)
[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier($appId).Show($toast)
