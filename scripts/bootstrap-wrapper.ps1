$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$destination = Join-Path $root "gradle\wrapper\gradle-wrapper.jar"
$jarUri = "https://raw.githubusercontent.com/gradle/gradle/v8.14.3/gradle/wrapper/gradle-wrapper.jar"
$checksumUri = "https://services.gradle.org/distributions/gradle-8.14.3-wrapper.jar.sha256"
$temp = Join-Path ([IO.Path]::GetTempPath()) ([Guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path $temp | Out-Null
try {
  $jar = Join-Path $temp "wrapper.jar"
  $checksum = Join-Path $temp "wrapper.sha256"
  Invoke-WebRequest -Uri $jarUri -OutFile $jar
  Invoke-WebRequest -Uri $checksumUri -OutFile $checksum
  $expected = (Get-Content -Raw $checksum).Trim()
  if ($expected -notmatch '^[0-9a-fA-F]{64}$') { throw "Invalid published SHA-256" }
  $actual = (Get-FileHash -Algorithm SHA256 $jar).Hash
  if ($actual -ne $expected) { throw "Official Gradle wrapper hash mismatch" }
  New-Item -ItemType Directory -Force -Path (Split-Path $destination) | Out-Null
  Move-Item -Force $jar $destination
  Write-Host "Official Gradle wrapper JAR downloaded and verified."
} finally { Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $temp }
