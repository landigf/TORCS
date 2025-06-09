import pandas as pd

# Load the dataset
df = pd.read_csv('../classes/drive_log4.csv')

# Filter rows where velocity == 0
start_rows = df[df['speedX'] == 0]

# Save the filtered rows to a new CSV file
start_rows.to_csv('../classes/drive_log4_start.csv', index=False)