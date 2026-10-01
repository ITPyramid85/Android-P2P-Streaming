param(
    [Parameter(Mandatory = $true)]
    [string]$TargetExe
)

$ErrorActionPreference = "Stop"
$subject = "CN=MProject Code Signing"

if (-not (Test-Path -LiteralPath $TargetExe)) {
    throw "File not found: $TargetExe"
}

function Get-CodeSignCertificate {
    $existing = Get-ChildItem Cert:\CurrentUser\My -CodeSigningCert -ErrorAction SilentlyContinue |
        Where-Object { $_.Subject -eq $subject -and $_.HasPrivateKey -and $_.NotAfter -gt (Get-Date) } |
        Select-Object -First 1
    if ($existing) {
        return $existing
    }

    $cert = New-SelfSignedCertificate `
        -Type CodeSigningCert `
        -Subject $subject `
        -KeyAlgorithm RSA `
        -KeyLength 2048 `
        -HashAlgorithm SHA256 `
        -CertStoreLocation Cert:\CurrentUser\My `
        -NotAfter (Get-Date).AddYears(10) `
        -KeyUsage DigitalSignature

    $cerPath = Join-Path $env:TEMP "mproject-codesign.cer"
    Export-Certificate -Cert $cert -FilePath $cerPath | Out-Null
    & certutil.exe -user -addstore -f Root $cerPath | Out-Null
    & certutil.exe -user -addstore -f TrustedPublisher $cerPath | Out-Null
    Remove-Item -LiteralPath $cerPath -Force -ErrorAction SilentlyContinue
    return $cert
}

function Sign-File([string]$path, $cert) {
    $result = Set-AuthenticodeSignature -FilePath $path -Certificate $cert -HashAlgorithm SHA256
    if ($result.Status -ne "Valid") {
        Write-Warning "Could not sign $path : $($result.Status) $($result.StatusMessage)"
    }
}

$cert = Get-CodeSignCertificate
$files = @($TargetExe)
$dir = Split-Path -Parent $TargetExe
foreach ($name in @("libgcc_s_seh-1.dll", "libstdc++-6.dll", "libwinpthread-1.dll")) {
    $dll = Join-Path $dir $name
    if (Test-Path -LiteralPath $dll) {
        $files += $dll
    }
}

foreach ($file in $files) {
    Sign-File $file $cert
}
