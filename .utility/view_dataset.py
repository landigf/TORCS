import pandas as pd
from mpl_toolkits.mplot3d import Axes3D
import numpy as np
import matplotlib.pyplot as plt
import seaborn as sns

# Leggi il file CSV
df = pd.read_csv("classes/dataset_normalized.csv")

# Crea una figura 3D
fig = plt.figure(figsize=(12, 8))
ax = fig.add_subplot(111, projection='3d')

# Visualizza i punti con distanceFromStart, accel, steer come assi x, y, z
# Colore rappresenta steer (limitato tra -1 e 1)
scatter = ax.scatter(df['distanceFromStart'], df['speedX'], df['speedY'],
                    c=df['steer'], s=30,
                    cmap='RdYlBu', alpha=0.6, vmin=-1, vmax=1)

# Etichette degli assi
ax.set_xlabel('distanceFromStart')
ax.set_ylabel('accel')
ax.set_zlabel('steer')

# Colorbar per steer
plt.colorbar(scatter, ax=ax, label='steer (-1 to 1)')

# Titolo
ax.set_title('Visualizzazione Dataset K-NN\n(distanceFromStart, accel, steer)')

plt.show()

# Matrice di correlazione
plt.figure(figsize=(10, 8))
correlation_matrix = df.corr()

# Crea una maschera per la matrice triangolare superiore
mask = np.triu(np.ones_like(correlation_matrix, dtype=bool))

sns.heatmap(correlation_matrix, annot=False, cmap='coolwarm', center=0,
            square=True, linewidths=0.5, mask=mask)
plt.title('Matrice di Correlazione delle Feature')
plt.tight_layout()
plt.show()

# Stampa statistiche di base
print(f"Numero di punti: {len(df)}")
print(f"Range distanceFromStart: {df['distanceFromStart'].min():.2f} - {df['distanceFromStart'].max():.2f}")
print(f"Range accel: {df['accel'].min():.2f} - {df['accel'].max():.2f}")
print(f"Range steer: {df['steer'].min():.2f} - {df['steer'].max():.2f}")

# Stampa correlazioni più significative
print("\nCorrelazioni più significative (>0.5 o <-0.5):")
for i in range(len(correlation_matrix.columns)):
    for j in range(i+1, len(correlation_matrix.columns)):
        corr_val = correlation_matrix.iloc[i, j]
        if abs(corr_val) > 0.5:
            print(f"{correlation_matrix.columns[i]} - {correlation_matrix.columns[j]}: {corr_val:.3f}")
