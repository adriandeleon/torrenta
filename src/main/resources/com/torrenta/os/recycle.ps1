# Moves one file or folder to the Recycle Bin. Torrenta runs it with the path as -Path.
param([Parameter(Mandatory = $true)][string]$Path)
$ErrorActionPreference = 'Stop'
try {
    Add-Type -AssemblyName Microsoft.VisualBasic
    $ui = [Microsoft.VisualBasic.FileIO.UIOption]::OnlyErrorDialogs
    $bin = [Microsoft.VisualBasic.FileIO.RecycleOption]::SendToRecycleBin
    if (Test-Path -LiteralPath $Path -PathType Container) {
        [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteDirectory($Path, $ui, $bin)
    } elseif (Test-Path -LiteralPath $Path) {
        [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteFile($Path, $ui, $bin)
    }
} catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}
