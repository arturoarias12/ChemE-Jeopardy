<#
File: run.ps1
Description: PowerShell launcher that builds and starts the ChemE Jeopardy server.
Author: Arturo Arias
Last updated: 2026-05-04
#>

param(
    [int]$Port = 8080,
    [string]$GameFile = ''
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

& "$root\build.ps1"
$javaArgs = @($Port)
if (-not [string]::IsNullOrWhiteSpace($GameFile)) {
    $javaArgs += $GameFile
}
java -cp out com.chemejeopardy.Main @javaArgs
