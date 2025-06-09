import glob
import os
import pandas as pd

input_dir = 'classes'
output_file = os.path.join(input_dir, 'dataset_union.csv')

# Match both drive_log.csv and drive_log*.csv
csv_files = glob.glob(os.path.join(input_dir, 'drive_log*.csv'))
            

# Remove duplicates in case drive_log.csv is matched twice
csv_files = list(set(csv_files))

df_list = [pd.read_csv(f) for f in csv_files]

# Ensure all DataFrames have the same columns in the same order
if df_list:
    common_cols = set.intersection(*(set(df.columns) for df in df_list))
    df_list = [df[list(common_cols)] for df in df_list]
    union_df = pd.concat(df_list, ignore_index=True, sort=False)
    union_df.to_csv(output_file, index=False)
    print(f"Union completed: {output_file}")
else:
    print("No drive_log.csv files found.")