param(
    [string]$ImageName = "streamvault:local",
    [string]$JavaHome = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

if (-not $JavaHome) {
    $localJdk = Join-Path $root "tools\jdk-17.0.18+8"
    if (Test-Path -LiteralPath $localJdk) {
        $JavaHome = $localJdk
    }
}

if ($JavaHome) {
    $env:JAVA_HOME = $JavaHome
    $env:Path = "$JavaHome\bin;$env:Path"
}

$localMvn = Join-Path $root "tools\apache-maven-3.9.9\bin\mvn.cmd"
$maven = if (Test-Path -LiteralPath $localMvn) { $localMvn } else { Join-Path $root "backstage\mvnw.cmd" }
& $maven -f (Join-Path $root "backstage\pom.xml") -DskipTests package
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$jarSource = Join-Path $root "backstage\target\StreamVault-0.0.1-SNAPSHOT.jar"
if (-not (Test-Path -LiteralPath $jarSource)) {
    throw "Backend jar not found: $jarSource"
}

docker build -f (Join-Path $root "Dockerfile") -t $ImageName $root
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

"Built Docker image $ImageName"
