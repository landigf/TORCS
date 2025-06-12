import glob
import os
import pandas as pd

input_dir = 'classes'
output_file = os.path.join(input_dir, 'dataset_union.csv')

# Match both drive_log.csv and drive_log*_clean.csv
csv_files = glob.glob(os.path.join(input_dir, 'drive*_clean.csv'))

# Remove duplicates in case drive_log.csv is matched twice
csv_files = list(set(csv_files))

total_rows = 0

if csv_files:
    df_list = []
    for f in csv_files:
        df = pd.read_csv(f)
        num_rows = len(df)
        total_rows += num_rows
        print(f"{os.path.basename(f)}: {num_rows} righe")
        df_list.append(df)
    union_df = pd.concat(df_list, ignore_index=True)
    union_df = union_df.sort_values('distanceFromStart').reset_index(drop=True)
    print(f"Totale righe: {len(union_df)}")
    print("Head del dataset unito:")
    print(union_df.head())
    union_df.to_csv(output_file, index=False)
    print(f"Union completed: {output_file}")
else:
    print("No drive_log.csv files found.")
