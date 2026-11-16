[CmdletBinding()]
param([hashtable]$InputEnvironment)

# Read-only preparation screen, NOT authorization or a replacement for Java IntegrationGate.
# Does not call Docker, Maven, JDBC, RabbitMQ, Flyway or network APIs.
$ErrorActionPreference = 'Stop'
if ($null -eq $InputEnvironment) {
    $InputEnvironment = @{}
    foreach ($entry in @(Get-ChildItem Env:)) { $InputEnvironment[$entry.Name] = $entry.Value }
}
$issues = New-Object System.Collections.Generic.List[string]
$prefix = 'JVG_NOTIFICATION_IT_'
$required = @('APPROVED','RUN_ID','DOCKER_CONTEXT','DOCKER_ENDPOINT','DB_PORT','RABBIT_PORT','DB_URL',
    'DB_USERNAME','DB_PASSWORD','RABBIT_USERNAME','RABBIT_PASSWORD','RABBIT_VHOST','EXCHANGE','QUEUE_PREFIX','APPROVAL_FILE')
foreach ($name in $required) {
    if ([string]::IsNullOrWhiteSpace([string]$InputEnvironment[($prefix + $name)])) { $issues.Add("Missing $prefix$name") }
}
foreach ($name in $InputEnvironment.Keys) {
    if ($name -match '^(NOTIFICATION_|SPRING_|DB_|RABBITMQ_|PG|DOCKER_)' -or $name -in @('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS')) {
        $issues.Add('Inherited infrastructure override refused; values withheld')
        break
    }
}
if ($issues.Count -eq 0) {
    if ($InputEnvironment[($prefix+'APPROVED')] -cne 'true') { $issues.Add('Explicit reviewed approval flag is absent') }
    $run = [string]$InputEnvironment[($prefix+'RUN_ID')]
    if ($run -cnotmatch '^[a-f0-9]{12}$') { $issues.Add('Exclusive 12-hex run ID required') }
    $ports = @()
    foreach ($name in @('DB_PORT','RABBIT_PORT')) {
        $number = 0
        if (![int]::TryParse([string]$InputEnvironment[($prefix+$name)], [ref]$number) -or $number -lt 1024 -or $number -gt 65535 -or
            $number -in @(5432,55434,5672,5673,15672,15673,9092,19092,18080,18081,8080,8081,8082)) { $issues.Add('Alternate isolated port required') }
        $ports += $number
    }
    if ($ports[0] -eq $ports[1]) { $issues.Add('Database and broker ports must differ') }
    $expected = @{
        DB_URL = "jdbc:postgresql://127.0.0.1:$($ports[0])/live_jvg_test_${run}_notification"
        DB_USERNAME = "jvg9f_${run}_notification"; RABBIT_USERNAME = "jvg9f_${run}_notification"
        RABBIT_VHOST = "9f_${run}_notification"; EXCHANGE = "9f_${run}_notification_updates"
        QUEUE_PREFIX = "notification.9f_${run}.integration"
    }
    foreach ($name in $expected.Keys) {
        if ($InputEnvironment[($prefix+$name)] -cne $expected[$name]) { $issues.Add("Nonexclusive $name refused; values withheld") }
    }
    $endpoint = [string]$InputEnvironment[($prefix+'DOCKER_ENDPOINT')]
    if (!($endpoint.StartsWith('npipe:////./pipe/') -or $endpoint.StartsWith('unix:///'))) { $issues.Add('Explicit local Docker endpoint required') }
    if ([string]$InputEnvironment[($prefix+'DOCKER_CONTEXT')] -cnotmatch '^[A-Za-z0-9][A-Za-z0-9_.-]*$') { $issues.Add('Explicit Docker context required') }
    foreach ($name in @('DB_PASSWORD','RABBIT_PASSWORD')) {
        $value = [string]$InputEnvironment[($prefix+$name)]
        if ($value.Length -lt 32 -or $value -match '(password|changeme|placeholder|generate|secret|guest)') { $issues.Add('Private authorized test credentials required; values withheld') }
    }
    if ($InputEnvironment[($prefix+'DB_PASSWORD')] -ceq $InputEnvironment[($prefix+'RABBIT_PASSWORD')]) { $issues.Add('Independent database/broker credentials required') }
    try {
        $repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
        $scope = [IO.Path]::GetFullPath((Join-Path $repo ".9f/$run")) + [IO.Path]::DirectorySeparatorChar
        $approval = [IO.Path]::GetFullPath([string]$InputEnvironment[($prefix+'APPROVAL_FILE')])
        if (!$approval.StartsWith($scope, [StringComparison]::OrdinalIgnoreCase) -or !(Test-Path -LiteralPath $approval -PathType Leaf)) {
            $issues.Add('Existing run-owned approval file required; no manifest is generated')
        }
    } catch { $issues.Add('Invalid run-owned approval path; values withheld') }
}
if ($issues.Count -gt 0) {
    [ordered]@{ result = 'BLOCKED_BEFORE_INFRASTRUCTURE'; issues = @($issues); infrastructure_access = 'NONE' } | ConvertTo-Json -Depth 3
    exit 2
}
[ordered]@{ result = 'INPUT_SCREEN_ONLY'; infrastructure_access = 'NONE';
    next = 'Not authorized to execute: Java IntegrationGate must validate the manifest, real paths, fingerprints and actual resource ownership.' } | ConvertTo-Json
exit 0
