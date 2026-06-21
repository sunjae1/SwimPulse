param(
    [string]$NgrokDomain = "unnamable-preset-contact.ngrok-free.dev",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$FrontendDir = Join-Path $Root "frontend"
$NgrokDir = Join-Path $Root "ngrok"
$NgrokExe = Join-Path $NgrokDir "ngrok.exe"

function Start-DevWindow {
    param(
        [string]$Title,
        [string]$WorkingDirectory,
        [string]$Command
    )

    $escapedTitle = $Title.Replace("'", "''")
    $escapedDirectory = $WorkingDirectory.Replace("'", "''")
    $escapedCommand = $Command.Replace("'", "''")
    Start-Process pwsh -ArgumentList @(
        "-NoExit",
        "-ExecutionPolicy", "Bypass",
        "-Command",
        "`$Host.UI.RawUI.WindowTitle = '$escapedTitle'; Set-Location '$escapedDirectory'; $escapedCommand"
    )
}

function Wait-LocalPort {
    param(
        [int]$Port,
        [int]$TimeoutSeconds = 60
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $client = New-Object System.Net.Sockets.TcpClient
        try {
            $connect = $client.BeginConnect("127.0.0.1", $Port, $null, $null)
            if ($connect.AsyncWaitHandle.WaitOne(500)) {
                $client.EndConnect($connect)
                return $true
            }
        } catch {
        } finally {
            $client.Close()
        }
        Start-Sleep -Seconds 1
    }
    return $false
}

if (!(Test-Path $FrontendDir)) {
    throw "frontend directory not found: $FrontendDir"
}

Write-Host "[1/3] Starting frontend dev server..."
Start-DevWindow -Title "SwimPulse frontend" -WorkingDirectory $FrontendDir -Command "npm run dev"

Write-Host "[2/3] Starting Docker services..."
Set-Location $Root
if ($SkipBuild) {
    docker compose up -d
} else {
    docker compose up -d --build
}

Write-Host "[3/3] Waiting for localhost:3000 before starting ngrok..."
if (!(Wait-LocalPort -Port 3000 -TimeoutSeconds 90)) {
    Write-Warning "localhost:3000 did not open within 90 seconds. Starting ngrok anyway."
}

if (Test-Path $NgrokExe) {
    $ngrokCommand = ".\ngrok.exe http --domain=$NgrokDomain 3000"
    Start-DevWindow -Title "SwimPulse ngrok" -WorkingDirectory $NgrokDir -Command $ngrokCommand
} else {
    $ngrokCommand = "ngrok http --domain=$NgrokDomain 3000"
    Start-DevWindow -Title "SwimPulse ngrok" -WorkingDirectory $Root -Command $ngrokCommand
}

Write-Host ""
Write-Host "SwimPulse local dev is starting."
Write-Host "Frontend: http://localhost:3000"
Write-Host "Backend:  http://localhost:8080"
Write-Host "ngrok:    https://$NgrokDomain"
Write-Host ""
Write-Host "Tip: use -SkipBuild when you do not need to rebuild backend."
