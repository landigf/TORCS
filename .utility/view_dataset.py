import pandas as pd
from mpl_toolkits.mplot3d import Axes3D
import numpy as np

import matplotlib.pyplot as plt

# Leggi il file CSV
df = pd.read_csv("classes/dataset.csv")  # Sostituisci con il percorso del tuo file

# Crea una figura 3D
fig = plt.figure(figsize=(12, 8))
ax = fig.add_subplot(111, projection='3d')

# Visualizza i punti con speedX e track9 come assi x,y e steer come asse z
scatter = ax.scatter(df['speedX'], df['track9'], df['steer'], 
                    c=df['accel'], s=df['brake']*50+10, 
                    cmap='viridis', alpha=0.6)

# Etichette degli assi
ax.set_xlabel('speedX')
ax.set_ylabel('track9')
ax.set_zlabel('steer')

# Colorbar per accel
plt.colorbar(scatter, ax=ax, label='accel')

# Titolo
ax.set_title('Visualizzazione Dataset K-NN\n(colore=accel, dimensione=brake)')

# Mostra il grafico
plt.show()

# Stampa statistiche di base
print(f"Numero di punti: {len(df)}")
print(f"Range speedX: {df['speedX'].min():.2f} - {df['speedX'].max():.2f}")
print(f"Range track9: {df['track9'].min():.2f} - {df['track9'].max():.2f}")
print(f"Range steer: {df['steer'].min():.2f} - {df['steer'].max():.2f}")