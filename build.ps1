<#
File: build.ps1
Description: PowerShell build helper that compiles Java sources into the out directory.
Author: Arturo Arias
Last updated: 2026-05-04
#>

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

if (-not (Test-Path out)) {
    New-Item -ItemType Directory -Path out | Out-Null
}

$files = Get-ChildItem -Recurse src -Filter *.java | ForEach-Object { $_.FullName }
if (-not $files) {
    throw 'No Java source files were found under src/.'
}

javac -d out $files
Write-Host 'Compilation complete.'
