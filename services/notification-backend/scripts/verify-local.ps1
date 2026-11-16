[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$JdkHome,
    [Parameter(Mandatory = $true)][string]$MavenCommand,
    [Parameter(Mandatory = $true)][string]$LocalRepository
)

# Offline local verification only. This script never selects the external IT profile.
$ErrorActionPreference = 'Stop'
$modulePath = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$javaPath = Join-Path $JdkHome 'bin/java.exe'
$javacPath = Join-Path $JdkHome 'bin/javac.exe'
foreach ($toolPath in @($javaPath, $javacPath, $MavenCommand)) {
    if (!(Test-Path -LiteralPath $toolPath -PathType Leaf)) { throw 'An explicit existing JDK/Maven tool is required; installation is prohibited.' }
}
if ([IO.Path]::GetFileName($MavenCommand) -ine 'mvn.cmd') {
    throw 'Use bin/mvn.cmd from an existing Maven distribution; wrappers can download before Maven processes offline flags.'
}
if (!(Test-Path -LiteralPath $LocalRepository -PathType Container)) { throw 'An existing local Maven repository is required; downloads are prohibited.' }

$previousEnvironment = @{}
$exitCode = 1
$evidencePath = Join-Path $modulePath ('target/local-verification-' + (Get-Date -Format 'yyyyMMdd-HHmmssfff'))
try {
    # Child/process environment only; preserve and restore every removed value without printing it.
    foreach ($entry in @(Get-ChildItem Env:)) {
        if ($entry.Name -match '^(NOTIFICATION_|SPRING_|DB_|RABBITMQ_|PG|DOCKER_|JVG_NOTIFICATION_IT_)' -or
            $entry.Name -in @('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','MAVEN_OPTS','MAVEN_ARGS','JAVA_HOME')) {
            $previousEnvironment[$entry.Name] = $entry.Value
            [Environment]::SetEnvironmentVariable($entry.Name, $null, 'Process')
        }
    }
    if (!$previousEnvironment.ContainsKey('JAVA_HOME')) { $previousEnvironment['JAVA_HOME'] = $null }
    $env:JAVA_HOME = [IO.Path]::GetFullPath($JdkHome)
    $oldPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue' # java -version writes its normal banner to stderr on Java 17.
    $javaVersion = @(& $javaPath -version 2>&1 | ForEach-Object { $_.ToString() })
    $javaExit = $LASTEXITCODE
    $javacVersion = @(& $javacPath -version 2>&1 | ForEach-Object { $_.ToString() })
    $javacExit = $LASTEXITCODE
    $mavenVersion = @(& $MavenCommand -version 2>&1 | ForEach-Object { $_.ToString() })
    $mavenExit = $LASTEXITCODE
    $ErrorActionPreference = $oldPreference
    if ($javaExit -ne 0 -or $javacExit -ne 0 -or ($javaVersion -join ' ') -notmatch 'version "17\.' -or
        ($javacVersion -join ' ') -notmatch 'javac 17\.') { throw 'Both java and javac must be verified JDK 17 executables.' }
    if ($mavenExit -ne 0 -or ($mavenVersion -join ' ') -notmatch 'Apache Maven (\d+\.\d+\.\d+)') {
        throw 'The existing Maven distribution must report a usable version.'
    }
    if ([version]$Matches[1] -lt [version]'3.6.3') { throw 'Maven 3.6.3 or later is required by the Spring Boot build.' }

    New-Item -ItemType Directory -Path $evidencePath -ErrorAction Stop | Out-Null
    if (Test-Path -LiteralPath (Join-Path $modulePath 'target/surefire-reports')) {
        Copy-Item -LiteralPath (Join-Path $modulePath 'target/surefire-reports') -Destination (Join-Path $evidencePath 'previous-surefire-reports') -Recurse
    }
    $arguments = @('-o','-B','-ntp','-f',(Join-Path $modulePath 'pom.xml'),
        "-Dmaven.repo.local=$([IO.Path]::GetFullPath($LocalRepository))",'-P!notification-integration',
        '-Dnotification.it.profile.enabled=false','-DskipITs=true',
        '-Dnotification.storage.enabled=false','-Dnotification.messaging.enabled=false',
        '-Dspring.flyway.enabled=false','-Dspring.sql.init.mode=never',
        '-Dspring.rabbitmq.dynamic=false','-Dspring.rabbitmq.listener.simple.auto-startup=false',
        '-Dmanagement.health.rabbit.enabled=false','verify')
    $verificationStartedUtc = [DateTime]::UtcNow
    $ErrorActionPreference = 'Continue'
    & $MavenCommand @arguments *> (Join-Path $evidencePath 'verify.log')
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($null -eq $exitCode) { $exitCode = 1 }
    $suiteFiles = @(Get-ChildItem -LiteralPath (Join-Path $modulePath 'target/surefire-reports') -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTimeUtc -ge $verificationStartedUtc })
    $totals = @{ tests = 0; failures = 0; errors = 0; skipped = 0 }
    foreach ($suiteFile in $suiteFiles) {
        $suite = ([xml](Get-Content -LiteralPath $suiteFile.FullName -Raw -Encoding UTF8)).testsuite
        if ($suite.name -match 'Notification(Postgres|Rabbit)IT$') { throw 'Unexpected external suite report; inspect the run and do not claim local-only success.' }
        foreach ($metric in @('tests','failures','errors','skipped')) { $totals[$metric] += [int]$suite.GetAttribute($metric) }
    }
    if ($suiteFiles.Count -eq 0 -or $totals.tests -eq 0 -or $totals.failures -gt 0 -or $totals.errors -gt 0) { $exitCode = 1 }
    if (Test-Path -LiteralPath (Join-Path $modulePath 'target/surefire-reports')) {
        Copy-Item -LiteralPath (Join-Path $modulePath 'target/surefire-reports') -Destination (Join-Path $evidencePath 'surefire-reports') -Recurse
    }
    [ordered]@{ result = $(if ($exitCode -eq 0) {'PASSED_LOCAL_ONLY'} else {'FAILED_LOCAL_ONLY'}); exit_code = $exitCode;
        tests = $totals.tests; failures = $totals.failures; errors = $totals.errors; skipped = $totals.skipped;
        external_integration = 'NOT_EXECUTED'; java = $javaVersion; javac = $javacVersion; maven = $mavenVersion } |
        ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $evidencePath 'summary.json') -Encoding UTF8
    Get-Content -LiteralPath (Join-Path $evidencePath 'verify.log') -Tail 16
    Write-Output "Evidence: $evidencePath"
} catch {
    # Exception text is generated locally; do not print arbitrary process environment values.
    Write-Error 'Local verification failed. Check explicit tool paths, repository availability and the local evidence directory.' -ErrorAction Continue
    $exitCode = 1
} finally {
    foreach ($name in $previousEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process') }
}
exit $exitCode
