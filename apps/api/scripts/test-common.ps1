$ErrorActionPreference = "Stop"

$script:ApiTestPort = if ([string]::IsNullOrWhiteSpace($env:API_TEST_PORT)) {
  8080
} else {
  [int]$env:API_TEST_PORT
}
$script:ApiTestWebSocketPort = if ([string]::IsNullOrWhiteSpace($env:API_TEST_WEBSOCKET_PORT)) {
  8081
} else {
  [int]$env:API_TEST_WEBSOCKET_PORT
}
$script:ApiTestPostgresPort = if ([string]::IsNullOrWhiteSpace($env:API_TEST_POSTGRES_PORT)) {
  5432
} else {
  [int]$env:API_TEST_POSTGRES_PORT
}
$script:ApiTestRedisPort = if ([string]::IsNullOrWhiteSpace($env:API_TEST_REDIS_PORT)) {
  6379
} else {
  [int]$env:API_TEST_REDIS_PORT
}
$script:ApiTestComposeFile = $env:API_TEST_COMPOSE_FILE
$script:ApiTestComposeProject = $env:API_TEST_COMPOSE_PROJECT

function Invoke-TestCompose {
  param(
    [string[]]$ComposeArguments
  )

  $dockerArguments = @("compose")
  if (-not [string]::IsNullOrWhiteSpace($script:ApiTestComposeFile)) {
    $dockerArguments += @("-f", $script:ApiTestComposeFile)
  }
  if (-not [string]::IsNullOrWhiteSpace($script:ApiTestComposeProject)) {
    $dockerArguments += @("-p", $script:ApiTestComposeProject)
  }
  $dockerArguments += $ComposeArguments
  $output = & docker @dockerArguments
  if ($LASTEXITCODE -ne 0) {
    throw "docker compose failed with exit code $LASTEXITCODE"
  }
  return $output
}

. (Join-Path $PSScriptRoot "classpath.ps1")

function Start-ComposeInfra {
  param(
    [string]$RepoRoot
  )

  Push-Location $RepoRoot
  try {
    Invoke-TestCompose -ComposeArguments @("up", "-d", "postgres", "redis") | Out-Null
  }
  finally {
    Pop-Location
  }
}

function Wait-ForInfra {
  param(
    [string]$RepoRoot,
    [int]$TimeoutSeconds = 60
  )

  $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
  while ((Get-Date) -lt $deadline) {
    $postgresReady = Test-TcpPort -HostName "127.0.0.1" -Port $script:ApiTestPostgresPort
    $redisReady = Test-TcpPort -HostName "127.0.0.1" -Port $script:ApiTestRedisPort

    if ($postgresReady -and $redisReady) {
      return
    }

    Start-Sleep -Seconds 1
  }

  throw "PostgreSQL and Redis did not become ready within $TimeoutSeconds seconds"
}

function Test-TcpPort {
  param(
    [string]$HostName,
    [int]$Port
  )

  $client = New-Object System.Net.Sockets.TcpClient
  try {
    $async = $client.BeginConnect($HostName, $Port, $null, $null)
    $connected = $async.AsyncWaitHandle.WaitOne(1000, $false)
    if (-not $connected) {
      return $false
    }
    $client.EndConnect($async)
    return $true
  } catch {
    return $false
  } finally {
    $client.Dispose()
  }
}

function Stop-ComposeInfra {
  param(
    [string]$RepoRoot
  )

  Push-Location $RepoRoot
  try {
    Invoke-TestCompose -ComposeArguments @("down", "-v") | Out-Null
  }
  finally {
    Pop-Location
  }
}

function Start-ApiProcess {
  param(
    [string]$ProjectRoot
  )

  $classPath = Get-JavaClassPath
  $startInfo = New-Object System.Diagnostics.ProcessStartInfo
  $startInfo.FileName = "java"
  $startInfo.Arguments = "-cp `"$classPath`" com.classroom.api.Main"
  $startInfo.WorkingDirectory = $ProjectRoot
  $startInfo.UseShellExecute = $false
  $startInfo.CreateNoWindow = $true

  $process = New-Object System.Diagnostics.Process
  $process.StartInfo = $startInfo
  [void]$process.Start()
  return $process
}

function Stop-ApiProcess {
  param(
    [System.Diagnostics.Process]$Process
  )

  if ($null -ne $Process -and -not $Process.HasExited) {
    $Process.Kill($true)
    if (-not $Process.WaitForExit(5000)) {
      throw "API process $($Process.Id) did not exit within 5 seconds"
    }
  }
}

function Wait-ForApi {
  param(
    [int]$TimeoutSeconds = 40
  )

  $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
  while ((Get-Date) -lt $deadline) {
    try {
      $response = Invoke-WebRequest -Uri "http://localhost:$script:ApiTestPort/api/health" -UseBasicParsing -TimeoutSec 3
      if ($response.StatusCode -eq 200) {
        return
      }
    } catch {
      Start-Sleep -Milliseconds 500
    }
  }

  throw "API did not become healthy within $TimeoutSeconds seconds"
}

function Wait-ForPort {
  param(
    [string]$HostName,
    [int]$Port,
    [int]$TimeoutSeconds = 20
  )

  $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
  while ((Get-Date) -lt $deadline) {
    if (Test-TcpPort -HostName $HostName -Port $Port) {
      return
    }
    Start-Sleep -Milliseconds 500
  }

  throw "Port $Port on $HostName did not become ready within $TimeoutSeconds seconds"
}

function Invoke-ApiJson {
  param(
    [string]$Method,
    [string]$Path,
    [string]$Token,
    [object]$Body
  )

  $headers = @{}
  if ($Token) {
    $headers["Authorization"] = "Bearer $Token"
  }

  $requestParams = @{
    Uri = "http://localhost:$script:ApiTestPort$Path"
    Method = $Method
    Headers = $headers
    UseBasicParsing = $true
    TimeoutSec = 20
  }

  if ($null -ne $Body) {
    $requestParams["Body"] = ($Body | ConvertTo-Json -Compress -Depth 6)
    $requestParams["ContentType"] = "application/json"
  }

  try {
    $response = Invoke-WebRequest @requestParams
    $content = if ($response.Content) { $response.Content | ConvertFrom-Json } else { $null }
    return [pscustomobject]@{
      StatusCode = [int]$response.StatusCode
      Body = $content
      Raw = $response.Content
    }
  } catch {
    if ($null -eq $_.Exception.Response) {
      throw
    }

    $stream = $_.Exception.Response.GetResponseStream()
    $reader = New-Object System.IO.StreamReader($stream)
    $raw = $reader.ReadToEnd()
    $parsed = $null
    if ($raw) {
      try {
        $parsed = $raw | ConvertFrom-Json
      } catch {
        $parsed = $raw
      }
    }

    return [pscustomobject]@{
      StatusCode = [int]$_.Exception.Response.StatusCode
      Body = $parsed
      Raw = $raw
    }
  }
}

function Assert-True {
  param(
    [bool]$Condition,
    [string]$Message
  )

  if (-not $Condition) {
    throw $Message
  }
}

function New-Token {
  param(
    [string]$DisplayName,
    [string]$Role,
    [string]$UserId
  )

  $response = Invoke-ApiJson -Method "POST" -Path "/api/auth/token" -Token $null -Body @{
    userId = $UserId
    displayName = $DisplayName
    role = $Role
  }

  Assert-True ($response.StatusCode -eq 200) "Failed to issue token for $DisplayName"
  return $response.Body.accessToken
}

function Query-AuditCount {
  param(
    [string]$RepoRoot
  )

  Push-Location $RepoRoot
  try {
    $count = Invoke-TestCompose -ComposeArguments @("exec", "-T", "postgres", "psql", "-U", "classroom", "-d", "classroom", "-t", "-A", "-c", "select count(*) from audit_logs where action_type = 'EXPORT_GENERATED';")
    return [int]($count.Trim())
  }
  finally {
    Pop-Location
  }
}

function Query-QuestionCountByState {
  param(
    [string]$RepoRoot,
    [string]$State
  )

  Push-Location $RepoRoot
  try {
    $count = Invoke-TestCompose -ComposeArguments @("exec", "-T", "postgres", "psql", "-U", "classroom", "-d", "classroom", "-t", "-A", "-c", "select count(*) from questions where state = '$State';")
    return [int]$count.Trim()
  }
  finally {
    Pop-Location
  }
}

function Receive-WebSocketText {
  param(
    [System.Net.WebSockets.ClientWebSocket]$Socket,
    [int]$TimeoutSeconds = 10
  )

  $buffer = New-Object byte[] 4096
  $segment = [System.ArraySegment[byte]]::new($buffer)
  $cancellation = New-Object System.Threading.CancellationTokenSource
  try {
    $cancellation.CancelAfter([TimeSpan]::FromSeconds($TimeoutSeconds))
    $result = $Socket.ReceiveAsync($segment, $cancellation.Token).GetAwaiter().GetResult()
    return [System.Text.Encoding]::UTF8.GetString($buffer, 0, $result.Count)
  }
  finally {
    $cancellation.Dispose()
  }
}

function Open-RawWebSocket {
  param(
    [string]$Token,
    [string]$ClassroomCode,
    [string]$SessionId
  )

  $client = New-Object System.Net.Sockets.TcpClient
  $client.Connect("127.0.0.1", $script:ApiTestWebSocketPort)
  $stream = $client.GetStream()
  $stream.ReadTimeout = 10000
  $stream.WriteTimeout = 10000
  $key = [Convert]::ToBase64String([System.Text.Encoding]::ASCII.GetBytes([guid]::NewGuid().ToString("N")))
  $request = "GET /ws?token=$([Uri]::EscapeDataString($Token))&classroomCode=$ClassroomCode&sessionId=$SessionId HTTP/1.1`r`nHost: 127.0.0.1:$script:ApiTestWebSocketPort`r`nUpgrade: websocket`r`nConnection: Upgrade`r`nSec-WebSocket-Version: 13`r`nSec-WebSocket-Key: $key`r`n`r`n"
  $bytes = [System.Text.Encoding]::ASCII.GetBytes($request)
  $stream.Write($bytes, 0, $bytes.Length)
  $stream.Flush()

  $headerBytes = New-Object System.Collections.Generic.List[byte]
  while ($true) {
    $value = $stream.ReadByte()
    if ($value -lt 0) {
      throw "WebSocket server closed during handshake"
    }
    $headerBytes.Add([byte]$value)
    $count = $headerBytes.Count
    if ($count -ge 4 `
        -and $headerBytes[$count - 4] -eq 13 `
        -and $headerBytes[$count - 3] -eq 10 `
        -and $headerBytes[$count - 2] -eq 13 `
        -and $headerBytes[$count - 1] -eq 10) {
      break
    }
  }

  $response = [System.Text.Encoding]::ASCII.GetString($headerBytes.ToArray())
  Assert-True ($response.Contains("101 Switching Protocols")) "WebSocket handshake failed: $response"
  return [pscustomobject]@{
    Client = $client
    Stream = $stream
  }
}

function Read-RawWebSocketTextFrame {
  param(
    [object]$Connection
  )

  $stream = $Connection.Stream
  $first = $stream.ReadByte()
  $second = $stream.ReadByte()
  if ($first -lt 0 -or $second -lt 0) {
    throw "WebSocket connection closed before frame"
  }

  $payloadLength = $second -band 127
  if ($payloadLength -eq 126) {
    $lengthBytes = New-Object byte[] 2
    [void]$stream.Read($lengthBytes, 0, 2)
    $payloadLength = ($lengthBytes[0] -shl 8) + $lengthBytes[1]
  } elseif ($payloadLength -eq 127) {
    throw "Large WebSocket frames are not supported in this test helper"
  }

  $payload = New-Object byte[] $payloadLength
  $offset = 0
  while ($offset -lt $payloadLength) {
    $read = $stream.Read($payload, $offset, $payloadLength - $offset)
    if ($read -le 0) {
      throw "WebSocket frame ended unexpectedly"
    }
    $offset += $read
  }

  return [System.Text.Encoding]::UTF8.GetString($payload)
}

function Close-RawWebSocket {
  param(
    [object]$Connection
  )

  if ($null -eq $Connection) {
    return
  }

  try {
    $Connection.Stream.Dispose()
  } catch {
  }

  try {
    $Connection.Client.Dispose()
  } catch {
  }
}
