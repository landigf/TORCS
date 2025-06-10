import pandas as pd
from sklearn.preprocessing import StandardScaler, MinMaxScaler, RobustScaler
import numpy as np
import seaborn as sns
import matplotlib.pyplot as plt

# Leggi il file CSV
df = pd.read_csv("classes/dataset_union.csv")

# Analizza le colonne e suggerisce normalizzazione
def analyze_normalization(df):
    results = {}
    
    for column in df.columns:
        if df[column].dtype in ['int64', 'float64']:
            data = df[column].dropna()
            
            # Calcola statistiche
            mean_val = data.mean()
            std_val = data.std()
            min_val = data.min()
            max_val = data.max()
            q25 = data.quantile(0.25)
            q75 = data.quantile(0.75)
            iqr = q75 - q25
            
            # Rileva outliers
            outliers = len(data[(data < q25 - 1.5*iqr) | (data > q75 + 1.5*iqr)])
            outlier_ratio = outliers / len(data)
            
            # Determina normalizzazione migliore
            if outlier_ratio > 0.1:  # Molti outliers
                suggestion = "RobustScaler"
                reason = f"Molti outliers ({outlier_ratio:.2%})"
            elif min_val >= 0:  # Tutti valori positivi
                suggestion = "MinMaxScaler"
                reason = "Valori non negativi, range fisso"
            else:  # Distribuzione normale
                suggestion = "StandardScaler"
                reason = "Distribuzione approssimativamente normale"
            
            results[column] = {
                'min': min_val,
                'max': max_val,
                'mean': mean_val,
                'std': std_val,
                'outlier_ratio': outlier_ratio,
                'suggestion': suggestion,
                'reason': reason
            }
    
    return results

# Analizza e stampa risultati
analysis = analyze_normalization(df)

print("ANALISI NORMALIZZAZIONE PER KNN:")
print("=" * 50)

for col, stats in analysis.items():
    print(f"\n{col}:")
    print(f"  Range: [{stats['min']:.3f}, {stats['max']:.3f}]")
    print(f"  Media: {stats['mean']:.3f}, Std: {stats['std']:.3f}")
    print(f"  Outliers: {stats['outlier_ratio']:.2%}")
    print(f"  → Raccomandazione: {stats['suggestion']}")
    print(f"    Motivo: {stats['reason']}")

# Applica normalizzazione consigliata
def apply_normalization(df, analysis):
    df_normalized = df.copy()
    scalers = {}
    
    for col, stats in analysis.items():
        if stats['suggestion'] == 'StandardScaler':
            scaler = StandardScaler()
        elif stats['suggestion'] == 'MinMaxScaler':
            scaler = MinMaxScaler()
        else:  # RobustScaler
            scaler = RobustScaler()
        
        df_normalized[col] = scaler.fit_transform(df[[col]])
        scalers[col] = scaler
    
    return df_normalized, scalers

# Applica normalizzazione
df_normalized, scalers_dict = apply_normalization(df, analysis)

print(f"\n\nDATASET NORMALIZZATO SALVATO")
print(f"Shape originale: {df.shape}")
print(f"Shape normalizzato: {df_normalized.shape}")

# Salva il dataset normalizzato
df_normalized.to_csv("classes/dataset_normalized.csv", index=False)

'''

print("\n" + "="*60)
print("ANALISI ESPLORATIVA DEL DATASET NORMALIZZATO")
print("="*60)

# Configura il plotting
plt.style.use('default')
sns.set_palette("husl")

# 1. Statistiche descrittive
print("\nSTATISTICHE DESCRITTIVE:")
print(df_normalized.describe())

# 2. Boxplot per tutte le variabili numeriche (escluso gear)
numeric_cols = [col for col in df_normalized.columns if col != 'gear' and df_normalized[col].dtype in ['int64', 'float64']]

if len(numeric_cols) > 0:
    fig, axes = plt.subplots(nrows=(len(numeric_cols)+2)//3, ncols=3, figsize=(15, 5*((len(numeric_cols)+2)//3)))
    axes = axes.flatten() if len(numeric_cols) > 3 else [axes] if len(numeric_cols) == 1 else axes
    
    for i, col in enumerate(numeric_cols):
        sns.boxplot(data=df_normalized, y=col, ax=axes[i])
        axes[i].set_title(f'Boxplot - {col} (normalizzato)')
        axes[i].grid(True, alpha=0.3)
    
    # Nascondi subplot vuoti
    for j in range(i+1, len(axes)):
        axes[j].set_visible(False)
    
    plt.tight_layout()
    plt.savefig('classes/boxplots_normalized.png', dpi=300, bbox_inches='tight')
    plt.show()

# 3. Distribuzione della variabile gear (discreta)
if 'gear' in df_normalized.columns:
    plt.figure(figsize=(10, 6))
    
    plt.subplot(1, 2, 1)
    gear_counts = df_normalized['gear'].value_counts().sort_index()
    plt.bar(gear_counts.index, gear_counts.values)
    plt.title('Distribuzione Marce (gear)')
    plt.xlabel('Numero Marcia')
    plt.ylabel('Frequenza')
    plt.grid(True, alpha=0.3)
    
    # Aggiungi etichette sulle barre
    for i, v in enumerate(gear_counts.values):
        plt.text(gear_counts.index[i], v + 0.5, str(v), ha='center')
    
    plt.subplot(1, 2, 2)
    plt.pie(gear_counts.values, labels=[f'Marcia {i}' for i in gear_counts.index], autopct='%1.1f%%')
    plt.title('Distribuzione Percentuale Marce')
    
    plt.tight_layout()
    plt.savefig('classes/gear_distribution.png', dpi=300, bbox_inches='tight')
    plt.show()
    
    print(f"\nDISTRIBUZIONE MARCE:")
    for gear, count in gear_counts.items():
        print(f"  Marcia {gear}: {count} campioni ({count/len(df_normalized)*100:.1f}%)")

# 4. Heatmap correlazioni
plt.figure(figsize=(12, 10))
correlation_matrix = df_normalized.corr()
mask = np.triu(np.ones_like(correlation_matrix, dtype=bool))
sns.heatmap(correlation_matrix, mask=mask, annot=True, cmap='coolwarm', center=0,
            square=True, fmt='.2f', cbar_kws={"shrink": .8})
plt.title('Matrice di Correlazione - Dataset Normalizzato')
plt.tight_layout()
plt.savefig('classes/correlation_heatmap.png', dpi=300, bbox_inches='tight')
plt.show()

# 5. Distribuzione per ogni variabile normalizzata
fig, axes = plt.subplots(nrows=(len(numeric_cols)+2)//3, ncols=3, figsize=(15, 5*((len(numeric_cols)+2)//3)))
axes = axes.flatten() if len(numeric_cols) > 3 else [axes] if len(numeric_cols) == 1 else axes

for i, col in enumerate(numeric_cols):
    sns.histplot(data=df_normalized, x=col, kde=True, ax=axes[i])
    axes[i].set_title(f'Distribuzione - {col} (normalizzato)')
    axes[i].axvline(df_normalized[col].mean(), color='red', linestyle='--', alpha=0.7, label='Media')
    axes[i].legend()
    axes[i].grid(True, alpha=0.3)

# Nascondi subplot vuoti
for j in range(i+1, len(axes)):
    axes[j].set_visible(False)

plt.tight_layout()
plt.savefig('classes/distributions_normalized.png', dpi=300, bbox_inches='tight')
plt.show()

# 6. Confronto prima/dopo normalizzazione per alcune variabili chiave
key_vars = numeric_cols[:4] if len(numeric_cols) >= 4 else numeric_cols

fig, axes = plt.subplots(nrows=2, ncols=len(key_vars), figsize=(4*len(key_vars), 8))

for i, col in enumerate(key_vars):
    # Prima della normalizzazione
    axes[0, i].hist(df[col].dropna(), bins=30, alpha=0.7, color='skyblue')
    axes[0, i].set_title(f'{col} - Originale')
    axes[0, i].set_ylabel('Frequenza')
    axes[0, i].grid(True, alpha=0.3)
    
    # Dopo la normalizzazione
    axes[1, i].hist(df_normalized[col].dropna(), bins=30, alpha=0.7, color='lightcoral')
    axes[1, i].set_title(f'{col} - Normalizzato')
    axes[1, i].set_xlabel('Valore')
    axes[1, i].set_ylabel('Frequenza')
    axes[1, i].grid(True, alpha=0.3)

plt.tight_layout()
plt.savefig('classes/before_after_normalization.png', dpi=300, bbox_inches='tight')
plt.show()

print(f"\n✓ Grafici salvati in:")
print(f"  - classes/boxplots_normalized.png")
print(f"  - classes/gear_distribution.png") 
print(f"  - classes/correlation_heatmap.png")
print(f"  - classes/distributions_normalized.png")
print(f"  - classes/before_after_normalization.png")

print(f"\n✓ Dataset normalizzato salvato in: classes/dataset_normalized.csv")

'''