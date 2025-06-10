param(
    [Parameter(Mandatory=$false)]
    [ValidateSet("basic", "sensors", "all")]
    [string]$Config = "basic",
    
    [Parameter(Mandatory=$false)]
    [string]$DatasetPath = "classes/dataset_union.csv",
    
    [Parameter(Mandatory=$false)]
    [string]$ModelName = "knn.tree"
)

Write-Host "=== BUILD KNN MODEL ===" -ForegroundColor Green
Write-Host "Configurazione: $Config" -ForegroundColor Yellow
Write-Host "Dataset: $DatasetPath" -ForegroundColor Yellow  
Write-Host "Modello output: $ModelName" -ForegroundColor Yellow
Write-Host ""

# Step 2: Compilazione classi KD-Tree
Write-Host "Step 2: Compilazione KD-Tree..." -ForegroundColor Cyan
javac -d classes src/scr/ai/DataPoint.java src/scr/ai/KDTree.java src/scr/ai/DatasetBuilder.java

if ($LASTEXITCODE -ne 0) {
    Write-Host "ERRORE: Compilazione fallita!" -ForegroundColor Red
    exit 1
}

# Creazione del modello serializzato
Write-Host "Creazione modello KD-Tree..." -ForegroundColor Cyan
Push-Location classes
try {
    java -cp . scr.ai.DatasetBuilder ../$DatasetPath $ModelName $Config
    $buildResult = $LASTEXITCODE
} finally {
    Pop-Location
}

if ($buildResult -ne 0) {
    Write-Host "ERRORE: Creazione modello fallita!" -ForegroundColor Red
    exit 1
}

# Step 3: Compilazione driver K-NN  
Write-Host "Step 3: Compilazione driver K-NN..." -ForegroundColor Cyan
javac -d classes -cp classes src/scr/ai/ActionCache.java src/scr/ai/KNNDriver.java

if ($LASTEXITCODE -ne 0) {
    Write-Host "ERRORE: Compilazione driver fallita!" -ForegroundColor Red
    exit 1
}

# Aggiorna automaticamente la configurazione nel KNNDriver
Write-Host "Aggiornamento configurazione nel KNNDriver..." -ForegroundColor Cyan
$knnDriverPath = "src/scr/ai/KNNDriver.java"
$content = Get-Content $knnDriverPath -Raw

# Trova e sostituisci la riga di configurazione
$configLine = switch ($Config) {
    "basic"   { "    private String[] featureConfig = DatasetBuilder.CONFIG_BASIC;" }
    "sensors" { "    private String[] featureConfig = DatasetBuilder.CONFIG_WITH_SENSORS;" }
    "all"     { "    private String[] featureConfig = DatasetBuilder.CONFIG_ALL_SENSORS;" }
}

$pattern = '    private String\[\] featureConfig = DatasetBuilder\.CONFIG_[A-Z_]+;'
$newContent = $content -replace $pattern, $configLine

if ($content -ne $newContent) {
    Set-Content $knnDriverPath -Value $newContent -NoNewline
    Write-Host "Configurazione aggiornata in KNNDriver.java" -ForegroundColor Green
    
    # Ricompila con la nuova configurazione
    Write-Host "Ricompilazione con nuova configurazione..." -ForegroundColor Cyan
    javac -d classes -cp classes src/scr/ai/ActionCache.java src/scr/ai/KNNDriver.java
    
    if ($LASTEXITCODE -ne 0) {
        Write-Host "ERRORE: Ricompilazione fallita!" -ForegroundColor Red
        exit 1
    }
}

Write-Host ""
Write-Host "=== BUILD COMPLETATO ===" -ForegroundColor Green
Write-Host "Modello: classes/$ModelName" -ForegroundColor Yellow
Write-Host "Configurazione: $Config" -ForegroundColor Yellow
Write-Host ""
Write-Host "Per testare il driver:" -ForegroundColor Cyan
Write-Host "cd classes" -ForegroundColor White
Write-Host "java -cp . scr.Client scr.ai.KNNDriver localhost:3001 verbose:on" -ForegroundColor White