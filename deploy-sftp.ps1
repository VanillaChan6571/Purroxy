param(
    [Parameter(Mandatory = $true)] [string] $JarPath,
    [Parameter(Mandatory = $true)] [string] $Prefix
)

$ErrorActionPreference = "Stop"
trap { [Console]::Error.WriteLine($_.Exception.Message); exit 1 }

# Reads <PREFIX>_<N>_<NAME> for a specific server, falling back to the shared <PREFIX>_<NAME>.
# Hosts, ports and users are usually per server; password and remote directory usually are not.
function Get-Setting {
    param([string] $Name, [int] $Index, [string] $Default = $null)
    foreach ($key in @("${Prefix}_${Index}_$Name", "${Prefix}_$Name")) {
        $value = [Environment]::GetEnvironmentVariable($key)
        if (-not [string]::IsNullOrWhiteSpace($value)) { return $value }
    }
    if ($null -ne $Default) { return $Default }
    throw "Missing ${Prefix}_${Index}_$Name (or ${Prefix}_$Name)."
}

function Escape-WinScpValue { param([string] $Value) return $Value.Replace('"', '""') }

if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) { throw "JAR not found: $JarPath" }
$jar = (Resolve-Path -LiteralPath $JarPath).Path

if ([Environment]::GetEnvironmentVariable("${Prefix}_SFTP_ENABLED") -ne "1") {
    Write-Host "SFTP upload skipped. Set ${Prefix}_SFTP_ENABLED=1 in deploy-sftp.local.cmd to enable it."
    exit 0
}

$servers = [Environment]::GetEnvironmentVariable("${Prefix}_SERVERS")
if ([string]::IsNullOrWhiteSpace($servers)) { $servers = [Environment]::GetEnvironmentVariable("SERVERS") }
if ([string]::IsNullOrWhiteSpace($servers)) { $servers = "1" }
$count = 0
if (-not [int]::TryParse(($servers -split '\s')[0], [ref] $count) -or $count -lt 1) {
    throw "SERVERS must be a positive whole number, got '$servers'."
}

$winscp = [Environment]::GetEnvironmentVariable("${Prefix}_WINSCP")
if ([string]::IsNullOrWhiteSpace($winscp)) {
    $found = Get-Command "winscp.com" -ErrorAction SilentlyContinue
    if ($found) {
        $winscp = $found.Source
    } else {
        $winscp = @("${env:ProgramFiles(x86)}\WinSCP\WinSCP.com", "${env:ProgramFiles}\WinSCP\WinSCP.com") |
            Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
    }
}
if (-not [string]::IsNullOrWhiteSpace($winscp) -and -not (Test-Path -LiteralPath $winscp -PathType Leaf)) {
    throw "${Prefix}_WINSCP points at a file that does not exist: $winscp"
}

$failures = @()
for ($i = 1; $i -le $count; $i++) {
    $hostName  = Get-Setting "SFTP_HOST" $i
    $port      = Get-Setting "SFTP_PORT" $i "2022"
    $user      = Get-Setting "SFTP_USER" $i
    $password  = Get-Setting "SFTP_PASSWORD" $i ""
    $remoteDir = Get-Setting "SFTP_REMOTE_DIR" $i
    $remoteName = Get-Setting "SFTP_REMOTE_NAME" $i (Split-Path -Path $jar -Leaf)

    # Two transports, chosen the way CatHuntMode chooses: a password means WinSCP, because OpenSSH
    # sftp cannot take one non-interactively. Key or agent auth uses sftp directly and needs no
    # WinSCP install at all, which is the safer setup where the host allows it.
    $key = Get-Setting "SFTP_KEY" $i ""
    $useWinScp = -not [string]::IsNullOrWhiteSpace($password)
    $remotePath = "$remoteDir/$remoteName" -replace '(?<!:)//+', '/'
    Write-Host "[$i/$count] Uploading $(Split-Path -Path $jar -Leaf) to ${user}@${hostName}:${port} -> ${remotePath}"

    try {
        if ($useWinScp) {
            if ([string]::IsNullOrWhiteSpace($winscp)) {
                throw "A password is set but WinSCP.com was not found. Install WinSCP, set ${Prefix}_WINSCP, or use ${Prefix}_SFTP_KEY instead."
            }
            $hostKey = Get-Setting "SFTP_HOSTKEY" $i ""
            if (-not [string]::IsNullOrWhiteSpace($hostKey)) {
                $hostKeyArg = '-hostkey="' + (Escape-WinScpValue $hostKey) + '"'
            } elseif ((Get-Setting "SFTP_ACCEPT_ANY_HOSTKEY" $i "").Trim() -eq "1") {
                $hostKeyArg = "-hostkey=*"
            } else {
                throw "Missing ${Prefix}_SFTP_HOSTKEY. Pin the server host key, or set ${Prefix}_SFTP_ACCEPT_ANY_HOSTKEY=1."
            }
            # The password reaches WinSCP through this file rather than the command line, so it never
            # appears in a process list or a build log. Removed in the finally block either way.
            $scriptFile = Join-Path $env:TEMP ("{0}-winscp-{1}.txt" -f $Prefix.ToLower(), [Guid]::NewGuid().ToString("N"))
            $commands = @(
                "option batch abort",
                "option confirm off",
                ('open sftp://{0}:{1}/ -username="{2}" -password="{3}" {4}' -f (Escape-WinScpValue $hostName), $port,
                    (Escape-WinScpValue $user), (Escape-WinScpValue $password), $hostKeyArg),
                ('mkdir "{0}"' -f (Escape-WinScpValue $remoteDir)),
                ('cd "{0}"' -f (Escape-WinScpValue $remoteDir)),
                ('put "{0}" "{1}"' -f (Escape-WinScpValue $jar), (Escape-WinScpValue $remoteName)),
                "exit"
            )
            try {
                Set-Content -LiteralPath $scriptFile -Value $commands -Encoding ASCII
                & $winscp /ini=nul "/script=$scriptFile"
                if ($LASTEXITCODE -ne 0) { throw "WinSCP exited with code $LASTEXITCODE." }
            } finally {
                Remove-Item -LiteralPath $scriptFile -Force -ErrorAction SilentlyContinue
            }
        } else {
            if (-not (Get-Command "sftp" -ErrorAction SilentlyContinue)) {
                throw "No password is set and OpenSSH sftp is not on PATH. Set ${Prefix}_SFTP_PASSWORD (with WinSCP) or install OpenSSH."
            }
            $batch = Join-Path $env:TEMP ("{0}-sftp-{1}.txt" -f $Prefix.ToLower(), [Guid]::NewGuid().ToString("N"))
            try {
                # A leading dash tells sftp to carry on when the directory already exists.
                Set-Content -LiteralPath $batch -Encoding ASCII -Value @(
                    ('-mkdir "{0}"' -f $remoteDir),
                    ('cd "{0}"' -f $remoteDir),
                    ('put "{0}" "{1}"' -f $jar, $remoteName)
                )
                $sftpArgs = @("-b", $batch, "-P", $port)
                if (-not [string]::IsNullOrWhiteSpace($key)) { $sftpArgs += @("-i", $key) }
                $sftpArgs += "${user}@${hostName}"
                & sftp @sftpArgs
                if ($LASTEXITCODE -ne 0) { throw "sftp exited with code $LASTEXITCODE." }
            } finally {
                Remove-Item -LiteralPath $batch -Force -ErrorAction SilentlyContinue
            }
        }
        Write-Host "[$i/$count] Uploaded."
    } catch {
        # Carry on to the remaining servers: a half-finished rollout is worse when it is also silent.
        Write-Host "[$i/$count] FAILED for ${hostName}: $($_.Exception.Message)"
        $failures += "${hostName}: $($_.Exception.Message)"
    }
}

if ($failures.Count -gt 0) {
    [Console]::Error.WriteLine("SFTP upload failed for $($failures.Count) of $count server(s):")
    $failures | ForEach-Object { [Console]::Error.WriteLine("  $_") }
    exit 1
}
Write-Host "SFTP upload completed for $count server(s)."
