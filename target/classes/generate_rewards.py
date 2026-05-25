import random
import re
import os
import csv

# Get the directory where the script is located
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))

# Define the files relative to the script's directory
INPUT_FILE = os.path.join(SCRIPT_DIR, "usa13509.tsp")
OUTPUT_FILE = os.path.join(SCRIPT_DIR, "city_rewards.csv")

SEED = 42  # Set to None for different results each run

random.seed(SEED)

cities = []

# Read and parse the TSP file
with open(INPUT_FILE, "r") as f:
    in_coords = False
    for line in f:
        line = line.strip()
        if line == "NODE_COORD_SECTION":
            in_coords = True
            continue
        if line == "EOF" or line == "":
            in_coords = False
            continue
        if in_coords:
            parts = line.split()
            if len(parts) >= 3:
                city_id = int(parts[0])
                x = float(parts[1])
                y = float(parts[2])
                cities.append((city_id, x, y))

# Generate random rewards
rewards = {city_id: random.randint(1, 100) for city_id, _, _ in cities}

# Write coordinates and rewards to a CSV file
with open(OUTPUT_FILE, "w", newline="") as f:
    writer = csv.writer(f)
    
    # Write the header row
    writer.writerow(["city_id", "x", "y", "reward"])
    
    # Write the data rows
    for city_id, x, y in cities:
        writer.writerow([city_id, x, y, rewards[city_id]])

print(f"Generated rewards for {len(cities)} cities -> {OUTPUT_FILE}")
print(f"Sample (first 10):")
for city_id, x, y in cities[:10]:
    print(f"City {city_id} ({x}, {y}) -> Reward: {rewards[city_id]}")