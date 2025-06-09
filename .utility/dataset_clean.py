import pandas as pd

df = pd.read_csv("classes/dataset_union.csv")
# Rimuovi ogni riga che abbia almeno un valore < 0 (sensori invalidi)
df_clean = df[(df.iloc[:,1:-3] >= 0).all(axis=1)]
print(f"Righe totali: {len(df)}, dopo pulizia: {len(df_clean)}")
df_clean.to_csv("classes/drive_log_clean.csv", index=False)
