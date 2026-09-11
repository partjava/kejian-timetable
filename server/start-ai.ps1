# Run this in PowerShell. The key is entered without echo and kept in memory only.
$ErrorActionPreference='Stop'
$env:AI_API_URL=Read-Host 'Full HTTPS API URL ending in /chat/completions'
$env:AI_MODEL=Read-Host 'Model name'
$secret=Read-Host 'API key (hidden)' -AsSecureString
$ptr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
try {
    $env:AI_API_KEY=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
}
try {
    & (Join-Path $PSScriptRoot 'start-server.cmd')
} finally {
    Remove-Item Env:AI_API_KEY -ErrorAction SilentlyContinue
    Remove-Item Env:AI_API_URL -ErrorAction SilentlyContinue
    Remove-Item Env:AI_MODEL -ErrorAction SilentlyContinue
}
