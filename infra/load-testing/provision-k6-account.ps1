param(
    [string] $BaseUrl = 'http://127.0.0.1:8080',
    [string] $Email = 'k6-test@localhost.test'
)

$ErrorActionPreference = 'Stop'

if ($BaseUrl.TrimEnd('/') -ne 'http://127.0.0.1:8080') {
    throw 'BASE_URL is restricted to http://127.0.0.1:8080.'
}

$credentialPath = Join-Path $PSScriptRoot 'k6-credentials.local'
if (Test-Path -LiteralPath $credentialPath) {
    $credentials = Get-Content -LiteralPath $credentialPath -Raw | ConvertFrom-Json
    if ($credentials.email -ne $Email -or [string]::IsNullOrWhiteSpace($credentials.password)) {
        throw 'The local k6 credential file is invalid. Remove it only if the disposable account is no longer needed.'
    }

    $login = Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$BaseUrl/api/auth/login" `
        -ContentType 'application/json' `
        -Body (@{ email = $credentials.email; password = $credentials.password } | ConvertTo-Json -Compress)
    if ($login.StatusCode -ne 200 -or -not (($login.Content | ConvertFrom-Json).token)) {
        throw 'The existing local k6 credentials cannot log in. The account password was not changed.'
    }

    Write-Output 'Existing local k6 account credentials verified.'
    exit 0
}

$random = [byte[]]::new(24)
[System.Security.Cryptography.RandomNumberGenerator]::Fill($random)
$credentials = [pscustomobject]@{
    email = $Email
    password = ([Convert]::ToBase64String($random) + 'aA1!')
}

try {
    $registration = Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$BaseUrl/api/auth/register" `
        -ContentType 'application/json' `
        -Body (@{ name = 'MiniDrive k6 Local Test'; email = $credentials.email; password = $credentials.password } | ConvertTo-Json -Compress)
} catch {
    throw 'Could not create the disposable account. No password was saved; an existing account is never reset by this helper.'
}

if ($registration.StatusCode -ne 201) {
    throw "Unexpected registration HTTP status $($registration.StatusCode)."
}

$credentials | ConvertTo-Json -Compress | Set-Content -LiteralPath $credentialPath -NoNewline -Encoding utf8
Write-Output 'Created a disposable local k6 account and saved its credentials in the ignored local file.'
