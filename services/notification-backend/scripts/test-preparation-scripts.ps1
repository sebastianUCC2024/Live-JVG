[CmdletBinding()]
param()

# Synthetic invalid input tests only. No files, credentials, infrastructure or approval are created.
$ErrorActionPreference = 'Stop'
$screen = Join-Path $PSScriptRoot 'test-integration-inputs.ps1'
$checks = 0
function New-InvalidFixture {
    $run = '012345abcdef'
    return @{
        JVG_NOTIFICATION_IT_APPROVED = 'true'; JVG_NOTIFICATION_IT_RUN_ID = $run
        JVG_NOTIFICATION_IT_DOCKER_CONTEXT = 'fixture-context'
        JVG_NOTIFICATION_IT_DOCKER_ENDPOINT = 'npipe:////./pipe/fixture-only'
        JVG_NOTIFICATION_IT_DB_PORT = '25432'; JVG_NOTIFICATION_IT_RABBIT_PORT = '25672'
        JVG_NOTIFICATION_IT_DB_URL = "jdbc:postgresql://127.0.0.1:25432/live_jvg_test_${run}_notification"
        JVG_NOTIFICATION_IT_DB_USERNAME = "jvg9f_${run}_notification"
        JVG_NOTIFICATION_IT_RABBIT_USERNAME = "jvg9f_${run}_notification"
        JVG_NOTIFICATION_IT_DB_PASSWORD = 'fixture-db-input-only-aaaaaaaaaaaaaaaaaaaa'
        JVG_NOTIFICATION_IT_RABBIT_PASSWORD = 'fixture-rabbit-input-only-bbbbbbbbbbbbbbbbbbbb'
        JVG_NOTIFICATION_IT_RABBIT_VHOST = "9f_${run}_notification"
        JVG_NOTIFICATION_IT_EXCHANGE = "9f_${run}_notification_updates"
        JVG_NOTIFICATION_IT_QUEUE_PREFIX = "notification.9f_${run}.integration"
        JVG_NOTIFICATION_IT_APPROVAL_FILE = (Join-Path $PSScriptRoot 'fixture-manifest-does-not-exist.json')
    }
}
function Assert-Blocked([hashtable]$Fixture, [string]$ExpectedIssue) {
    $output = (& $screen -InputEnvironment $Fixture | Out-String)
    $code = $LASTEXITCODE
    $result = $output | ConvertFrom-Json
    if ($code -ne 2 -or $result.result -ne 'BLOCKED_BEFORE_INFRASTRUCTURE' -or
        $result.infrastructure_access -ne 'NONE' -or ($result.issues -join ' ') -notmatch $ExpectedIssue) {
        throw 'Preparation screen failed a synthetic invalid-input assertion.'
    }
    if ($output -match 'fixture-db-input-only|fixture-rabbit-input-only') { throw 'Credential-like fixture value leaked.' }
    $script:checks++
}
Assert-Blocked @{} 'Missing JVG_NOTIFICATION_IT_'
$fixture = New-InvalidFixture; $fixture.NOTIFICATION_STORAGE_ENABLED = 'true'
Assert-Blocked $fixture 'Inherited infrastructure override'
$fixture = New-InvalidFixture; $fixture.JVG_NOTIFICATION_IT_DB_PORT = '5432'
Assert-Blocked $fixture 'Alternate isolated port'
$fixture = New-InvalidFixture; $fixture.JVG_NOTIFICATION_IT_DB_URL = 'jdbc:postgresql://127.0.0.1:25432/live_jvg'
Assert-Blocked $fixture 'Nonexclusive DB_URL'
$fixture = New-InvalidFixture; $fixture.JVG_NOTIFICATION_IT_DOCKER_ENDPOINT = 'tcp://example.invalid:2375'
Assert-Blocked $fixture 'Explicit local Docker endpoint'
$fixture = New-InvalidFixture; $fixture.JVG_NOTIFICATION_IT_RABBIT_PASSWORD = $fixture.JVG_NOTIFICATION_IT_DB_PASSWORD
Assert-Blocked $fixture 'Independent database/broker credentials'
Assert-Blocked (New-InvalidFixture) 'Existing run-owned approval file'
try {
    & (Join-Path $PSScriptRoot 'verify-local.ps1') -JdkHome (Join-Path $PSScriptRoot 'missing-fixture-jdk') `
        -MavenCommand (Join-Path $PSScriptRoot 'missing-fixture-maven') -LocalRepository (Join-Path $PSScriptRoot 'missing-fixture-cache')
    throw 'Missing tools must refuse local verification.'
} catch {
    if ($_.Exception.Message -notmatch 'existing JDK/Maven') { throw }
    $checks++
}
Write-Output "Preparation script checks passed: $checks; external access: NONE"
exit 0
