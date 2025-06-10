import pandas as pd
import glob
import os

input_dir = "classes"  # Adjust this path as needed

# Match both drive_log.csv and drive_log%.csv
csv_files = glob.glob(os.path.join(input_dir, 'drive_log*.csv'))

# Remove duplicates in case drive_log.csv is matched twice
csv_files = list(set(csv_files))

for csv_file in csv_files:
    print(f"Processing: {csv_file}")
    
    # Read CSV
    df = pd.read_csv(csv_file)
    print(f"Original rows: {len(df)}")
    if 'accel' not in df.columns:
        print(f"Warning: 'accel' column not found in {csv_file}. Skipping.")
        continue
    print(f"Columns: {df.columns.tolist()}")
    print(f"First few rows:\n{df.head()}")
    
    # Trova il primo indice dove accel != 0
    first_nonzero_accel = df[df['accel'] != 0].index[0] if len(df[df['accel'] != 0]) > 0 else len(df)
    
    # Rimuovi tutte le righe dall'inizio fino a quando accel non diventa != 0
    df_clean = df.iloc[first_nonzero_accel:]
    
    # Remove rows where any track* column has value -1.0
    track_cols = [col for col in df_clean.columns if col.startswith('track') and col != 'trackPos']
    if track_cols:
        mask = (df_clean[track_cols] != -1.0).all(axis=1)
        df_clean = df_clean[mask]
    
    # Remove rows where lastLapTime == x
    if 'lastLapTime' in df_clean.columns:
        df_clean = df_clean[df_clean['lastLapTime'] != 112.276]
    
    print(f"File: {os.path.basename(csv_file)} - Righe totali: {len(df)}, dopo pulizia: {len(df_clean)}")
    
    # Save with _clean suffix
    base_name = os.path.basename(csv_file)
    name_without_ext = os.path.splitext(base_name)[0]
    # Convert from drive_log1 to drive1_clean format
    if name_without_ext.startswith('drive_log'):
        clean_name = name_without_ext.replace('drive_log', 'drive') + '_clean'
    else:
        clean_name = name_without_ext + '_clean'
    output_file = os.path.join(os.path.dirname(csv_file), clean_name + '.csv')
    df_clean.to_csv(output_file, index=False)
    print(f"Saved: {output_file}")
