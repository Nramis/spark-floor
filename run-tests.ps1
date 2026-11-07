$ErrorActionPreference = 'Stop'
$classes = Join-Path $PSScriptRoot 'build/geometry-tests'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources = @(
    'src/org/example/sparkfloor/FloorDistance.java',
    'src/org/example/sparkfloor/GroundProjection.java',
    'src/org/example/sparkfloor/FloorPath.java',
    'src/org/example/sparkfloor/Trapezoid.java',
    'DistanceTest.java',
    'TrapezoidTest.java',
    'FloorPathTest.java'
) | ForEach-Object {
    Join-Path $PSScriptRoot $_
}

& javac --release 8 -encoding UTF-8 -d $classes $sources
if ($LASTEXITCODE -ne 0) {
    throw 'Geometry compilation failed'
}

foreach ($test in @('DistanceTest', 'TrapezoidTest', 'FloorPathTest')) {
    & java -cp $classes $test
    if ($LASTEXITCODE -ne 0) {
        throw "$test failed"
    }
}
