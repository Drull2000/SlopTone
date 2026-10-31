# Builds sloptone-1.0.9.jar against the Forge 26.3 install of the local launcher (no Gradle needed).
$ErrorActionPreference = 'Stop'
$mc   = "$env:APPDATA\.minecraft"
$jdk  = "$mc\runtime\java-runtime-epsilon\windows\java-runtime-epsilon\bin"
$root = $PSScriptRoot
$ver  = Get-Content "$mc\versions\Forge 26.3\Forge 26.3.json" -Raw | ConvertFrom-Json

$cp = @()
foreach ($l in $ver.libraries) {
    $p = $l.name.Split(':')
    $file = "$($p[1])-$($p[2])"
    if ($p.Length -gt 3) { $file += "-$($p[3])" }
    $cp += "$mc\libraries\$($p[0].Replace('.', '\'))\$($p[1])\$($p[2])\$file.jar"
}
$cp += Get-ChildItem "$mc\libraries\net\minecraftforge" -Recurse -Filter "*26.3-66.0.2*.jar" | ForEach-Object FullName
$cp = $cp | Where-Object { Test-Path $_ } | Select-Object -Unique

$out = "$root\build"
Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$out\classes" | Out-Null
$sources = Get-ChildItem "$root\src\main\java" -Recurse -Filter *.java | ForEach-Object FullName
$argFile = "$out\javac.args"
@('-encoding', 'UTF-8', '--release', '25', '-nowarn', '-d', "`"$out\classes`"", '-cp', "`"$($cp -join ';')`"") + ($sources | ForEach-Object { "`"$_`"" }) |
    ForEach-Object { $_.Replace('\', '/') } | Set-Content -Encoding ascii $argFile
& "$jdk\javac.exe" "@$argFile"
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

# Mixin 0.8.7 only knows class versions up to Java 21; the mixin classes use no newer features,
# so mark them as Java 21 (major 65) class files.
Get-ChildItem "$out\classes\ru\baritoneru\mixin" -Filter *.class | ForEach-Object {
    $bytes = [IO.File]::ReadAllBytes($_.FullName)
    $bytes[6] = 0; $bytes[7] = 65
    [IO.File]::WriteAllBytes($_.FullName, $bytes)
}

Copy-Item -Recurse "$root\src\main\resources\*" "$out\classes\"
Copy-Item "$root\LICENSE.txt", "$root\LICENSE_RU.txt" "$out\classes\"
"MixinConfigs: sloptone.mixins.json" | Set-Content -Encoding ascii "$out\MANIFEST.MF"
$jar = "$out\sloptone-1.0.9.jar"
& "$jdk\jar.exe" --create --file $jar --manifest "$out\MANIFEST.MF" -C "$out\classes" .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }
Write-Host "Built $jar"
