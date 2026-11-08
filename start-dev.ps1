$ErrorActionPreference = 'Stop'

$envFile = Join-Path $PSScriptRoot '.env'
if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) {
    throw 'Copy .env.example to .env and fill in the required settings.'
}

$composeFile = Join-Path $PSScriptRoot 'compose.yaml'
$devFile = Join-Path $PSScriptRoot 'docker/compose.dev.yaml'
docker compose --project-directory $PSScriptRoot --env-file $envFile -f $composeFile -f $devFile up -d
exit $LASTEXITCODE
