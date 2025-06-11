# Guida

## Step 1. - ottenere il dataset
Da \Torcs
'''
javac -d classes src/scr/*.java src/scr/ai/SimpleGear.java src/scr/ai/DataLoggerDriver.java
cd classes
java -cp . scr.Client scr.ai.DataLoggerDriver localhost:3001 verbose:on
#### remoto
java -cp . scr.Client scr.ai.DataLoggerDriver host:172.19.196.17 port:3001 verbose:on
'''
### 1.a -pulizia e verifica del dataset
compila .utils/dataset_clean.py

## Step 2. - Costruzione del KD-Tree
Da \Torcs
'''
javac -d classes src/scr/ai/DataPoint.java src/scr/ai/KDTree.java src/scr/ai/DatasetBuilder.java
'''
Poi crea il file serializzato:
'''
cd classes
java -cp . scr.ai.DatasetBuilder ../classes/dataset_union.csv knn.tree
'''

## Step 3. - Compilazione del driver K-NN
Da \Torcs
'''
javac -d classes -cp classes src/scr/ai/ActionCache.java src/scr/ai/KNNDriver.java
'''

## Step 4. - lancio della guida autonoma
Da \Torcs
'''
cd classes
java -cp . scr.Client scr.ai.KNNDriver localhost:3001 verbose:on
'''

## aa

# Configurazione basic (default)
.\.build_knn.ps1

# Configurazione con sensori
.\.build_knn.ps1 -Config sensors

# Configurazione completa
.\.build_knn.ps1 -Config all

# Con dataset personalizzato
.\.build_knn.ps1 -Config sensors -DatasetPath "my_dataset.csv" -ModelName "my_model.tree"