import glob
import os
import pandas as pd

input_dir = 'classes'
output_file = os.path.join(input_dir, 'dataset_union.csv')

# Match both drive_log.csv and drive_log*.csv
csv_files = glob.glob(os.path.join(input_dir, 'drive_log*_clean.csv'))

# Remove duplicates in case drive_log.csv is matched twice
csv_files = list(set(csv_files))

if csv_files:
    df_list = [pd.read_csv(f) for f in csv_files]
    union_df = pd.concat(df_list, ignore_index=True)
    union_df = union_df.sort_values('curLapTime').reset_index(drop=True)
    union_df.to_csv(output_file, index=False)
    print(f"Union completed: {output_file}")
else:
    print("No drive_log.csv files found.")
