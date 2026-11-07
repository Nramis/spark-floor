$ErrorActionPreference = 'Stop'
$classes = Join-Path $PSScriptRoot 'build/geometry-tests'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources = @(
    'src/org/example/sparkfloor/FloorDistance.java',
    'src/org/example/sparkfloor/GroundProjection.java',
    'src/org/example/sparkfloor/FloorPath.java',
    'src/org/example/sparkfloor/Trapezoid.java',
    'src/org/example/sparkfloor/VoiceCommands.java',
    'src/org/example/sparkfloor/Segmentation.java',
    'src/org/example/sparkfloor/SceneDescription.java',
    'src/org/example/sparkfloor/ImageQuality.java',
    'src/org/example/sparkfloor/CameraGuidance.java',
    'src/org/example/sparkfloor/ResultSnapshot.java',
    'src/org/example/sparkfloor/AssistiveFeedback.java',
    'DistanceTest.java',
    'TrapezoidTest.java',
    'FloorPathTest.java',
    'VoiceCommandsTest.java',
    'SceneDescriptionTest.java',
    'ImageQualityTest.java',
    'CameraGuidanceTest.java',
    'AssistiveFeedbackTest.java'
) | ForEach-Object {
    Join-Path $PSScriptRoot $_
}

& javac --release 8 -encoding UTF-8 -d $classes $sources
if ($LASTEXITCODE -ne 0) {
    throw 'Geometry compilation failed'
}

foreach ($test in @('DistanceTest', 'TrapezoidTest', 'FloorPathTest', 'VoiceCommandsTest', 'SceneDescriptionTest', 'ImageQualityTest', 'CameraGuidanceTest', 'AssistiveFeedbackTest')) {
    & java -cp $classes $test
    if ($LASTEXITCODE -ne 0) {
        throw "$test failed"
    }
}
