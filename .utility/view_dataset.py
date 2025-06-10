import pandas as pd
from mpl_toolkits.mplot3d import Axes3D
import numpy as np
import matplotlib.pyplot as plt

# Leggi il file CSV
df = pd.read_csv("classes/dataset_normalized.csv")

# Crea una figura 3D
fig = plt.figure(figsize=(12, 8))
ax = fig.add_subplot(111, projection='3d')

# Visualizza i punti con speedX, track9, speedY come assi x, y, z
# Colore e dimensione rappresentano l'output (ad esempio accel)
scatter = ax.scatter(df['speedX'], df['track9'], df['speedY'],
                    c=df['accel'], s=df['accel']*50+10,
                    cmap='viridis', alpha=0.6)

# Etichette degli assi
ax.set_xlabel('speedX')
ax.set_ylabel('track9')
ax.set_zlabel('speedY')

# Colorbar per steer
plt.colorbar(scatter, ax=ax, label='accel')

# Titolo
ax.set_title('Visualizzazione Dataset K-NN\n(colore=accel, dimensione=accel)')

plt.show()

# Stampa statistiche di base
print(f"Numero di punti: {len(df)}")
print(f"Range speedX: {df['speedX'].min():.2f} - {df['speedX'].max():.2f}")
print(f"Range track9: {df['track9'].min():.2f} - {df['track9'].max():.2f}")
print(f"Range speedY: {df['speedY'].min():.2f} - {df['speedY'].max():.2f}")
