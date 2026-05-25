# BC-TSP: Budget-Constrained Traveling Salesman Problem

Implementation of the algorithms from the paper:
**"Budget-Constrained Traveling Salesman Problem: a Cooperative Multi-Agent Reinforcement Learning Approach"**
https://csc.csudh.edu/btang/secon_2024_journal.pdf

---

## State of the Code

The core algorithms (Greedy 1, Greedy 2, P-MARL) are all implemented and reflect the logic described in the paper. However, this codebase has not been actively maintained for some time, so **some parts may need debugging or minor fixes before they run cleanly end-to-end**. In particular:

- The batch loop in `main.java` (lines ~80–117) is commented out and may need adjustments — it was an earlier one-off data collection script and is not the primary way to generate figures.
- File paths for the data files (`src/Capital_Cities.txt`) assume a specific working directory — if the project is opened in a different environment, these may need to be updated.
- The ILP code is fully commented out and non-functional in Java; it depends on IBM CPLEX and is only present for reference.
- `TableData.java` is the intended entry point for generating figure data and should work as-is, but its hyperparameter values (alpha, gamma, W, etc.) are set independently from `main.java` — double-check the constants at the top of each file are consistent before comparing output across them.

If something breaks, the algorithms themselves are sound — the issue is likely a path, a hardcoded value, or a commented-out block that needs to be restored.

---

## File Overview

| File | Purpose |
|------|---------|
| `main.java` | Single-run entry point. Runs Greedy 1, Greedy 2, and P-MARL for one city/budget combination. Also prints the cost/prize arrays needed as input for ILP in IBM CPLEX (Fig 4). |
| `TableData.java` | Batch runner for generating figure data. Loops over multiple cities, budgets, and agent counts — use this to generate Figs 3 and 5. |
| `Exploration.java` | Hyperparameter grid search over trials, agents, alpha, gamma, and q0. Used to find the best P-MARL settings. |
| `Agent.java` | Agent class used by P-MARL during the learning stage. Each agent holds its own graph copy, budget, current path, and prize total. |
| `Graph.java` | Adjacency matrix graph. Runs Floyd-Warshall on initialization so all shortest paths (including detours through intermediate cities) are precomputed. |
| `CityNode.java` | Represents a city with latitude, longitude, and prize. Computes pairwise distances using the Haversine formula. |
| `src/Capital_Cities.txt` | 48 continental US state capital cities — used for large network experiments (Fig 3, 5). |
| `src/Capital_Cities10.txt` | First 10 cities from the above file — used for small network experiments with ILP (Fig 4). |

---

## Algorithms Implemented

### Greedy Algorithm 1 — `traverseP()` in `main.java` / `TableData.java`
At each step, visits the budget-feasible unvisited node with the **largest prize**. Nodes are sorted once by prize at the start.

### Greedy Algorithm 2 — `traverseR()` in `main.java` / `TableData.java`
At each step, visits the budget-feasible unvisited node with the **best prize-to-distance ratio** (`prize / distance`), re-evaluated from the current position each step.

### P-MARL — `learnQ()` + `traverseQ()` in `main.java` / `TableData.java`
Prize-driven Multi-Agent Reinforcement Learning (Algorithm 3 from the paper).
- **Learning stage:** Multiple agents independently explore prize-collecting paths using a prize-weighted action mechanism (`Q^δ × prize / distance^β`). After each episode, agents share results and cooperatively reinforce the highest-prize route found.
- **Execution stage:** The salesman follows the learned Q-table greedily from source to destination.
- Key hyperparameters (top of `main.java`): `NUM_AGENTS`, `TRIALS`, `alpha`, `gamma`, `q0`, `W`.

### ILP — IBM CPLEX (external)
The ILP is not executed in Java. Instead, `printIlpArrays()` in `main.java` prints the **cost matrix** and **prize array** formatted for direct paste into IBM CPLEX Optimization Studio. Run `main.java` on the 10-city dataset and copy the printed arrays into CPLEX to get the optimal solution for Fig 4.

---

## Switching Between Network Sizes

Two things must be changed together:

**1. Change the filename** at the top of `main.java` (and `TableData.java`):
```java
// 48 cities (large network — Fig 3, 5)
static String fileName = "Capital_Cities.txt";

// 10 cities (small network — Fig 4, ILP comparison)
static String fileName = "Capital_Cities10.txt";
```

**2. Update the `cityList` array** in `main.java` to match the cities in the file. This array is used by the batch loop (lines ~80–117) as the set of source/destination cities to iterate over. Currently it has 10 cities active with the rest commented out:

```java
// 10-city version (current)
String[] cityList = { "Albany,NY", "Annapolis,MD", "Atlanta,GA", "Augusta,ME", "Austin,TX",
    "BatonRouge,LA", "Bismarck,ND", "Boise,ID", "Boston,MA", "CarsonCity,NV" };

// 48-city version — uncomment the remaining cities in the array
```

Also update the inner loop bound to match the number of cities being used:
```java
for (int i = 0; i < 10; i++) {  // change 10 to 48 for the full dataset
```

---

## Generating Figures 3, 4, and 5

### Fig 3 — Large network (48 cities), Greedy 1 vs Greedy 2 vs P-MARL vs Ant-Q, varying budget
Run `TableData.java` with `fileName = "Capital_Cities.txt"`. It loops over the budgets in `BUDGETS_ARRAY` and cities in `generateRandomCities()`, printing prize and distance arrays for each algorithm. Collect the output and plot.

> **Note:** Ant-Q is not yet implemented — see section below.

### Fig 4 — Small network (10 cities), P-MARL vs ILP vs Ant-Q, varying budget
1. Run `TableData.java` with `fileName = "Capital_Cities10.txt"` to get P-MARL and greedy results across budgets.
2. Run `main.java` with `fileName = "Capital_Cities10.txt"` — this prints the `Cost` and `p` arrays via `printIlpArrays()`. Copy those into IBM CPLEX Optimization Studio to get the ILP optimal solution.

### Fig 5 — P-MARL with varying number of agents
Run `TableData.java`. The second loop in `main()` already iterates over `AGENTS_ARRAY = {1, 5, 10, 15, 20}` for each budget, printing prize, distance, and execution time for P-MARL at each agent count.

---

## Generating Plots with Gnuplot

The `gnuplot/` folder contains everything needed to reproduce the paper's plots:

| File type | Purpose |
|-----------|---------|
| `.dat` | Raw data (budget, algorithm values, error bars) — replace this with new output from `TableData.java` |
| `.txt` | Gnuplot script that reads the `.dat` and produces the plot |
| `.eps` | Output plot generated by the script — included as reference |

Each figure and metric has its own set of three files, e.g. `Fig3Prize.dat` / `Fig3Prize.txt` / `Fig3Prize.eps`.

**To regenerate a plot:**
1. Run `TableData.java` and copy the output into the corresponding `.dat` file, matching the existing column format (budget, value, error, value, error, ...).
2. Run the gnuplot script in gnuplot: `gnuplot Fig3Prize.txt` — this overwrites the `.eps` output.

The existing `.dat` files and `.eps` plots from the original paper are kept in the folder as reference so you can see exactly what format the data should be in and what the expected output looks like.

---

## Note on Ant-Q

The paper compares P-MARL against Ant-Q, but **only P-MARL is implemented here**. Ant-Q is prize-oblivious — it was originally designed to minimize travel distance across all nodes, not maximize prizes. The behavioral difference from P-MARL comes down to three small changes:

**1. Remove prize from action selection** (`getNextStateFromCurState`):
```java
// P-MARL
Math.pow(Q[s][u], delta) * aj.getPrize(u) / Math.pow(aj.weight(s, u), beta)

// Ant-Q
Math.pow(Q[s][u], delta) / Math.pow(aj.weight(s, u), beta)
```

**2. Select the shortest-distance agent instead of the highest-prize agent** (`learnQ`):
```java
// P-MARL
int mostFitIndex = findHighestPrize(aList);

// Ant-Q — add a findShortestDistance() method and use it here
int mostFitIndex = findShortestDistance(aList);
```

**3. Reinforce by distance instead of prize** (`learnQ` cooperative update):
```java
// P-MARL
R[path.get(v)][path.get(v + 1)] += (W / jStar.total_prize);

// Ant-Q
R[path.get(v)][path.get(v + 1)] += (W / jStar.total_wt);
```

These three changes plus a new `findShortestDistance()` helper are all that is needed to implement Ant-Q alongside P-MARL.

---

## How to Run

1. Ensure `src/Capital_Cities.txt` (or `Capital_Cities10.txt`) is present relative to the working directory.
2. Compile all `.java` files.
3. Run `main.java` for a single city/budget run, or `TableData.java` for batch figure data.
4. Start city and budget are set at the top of `main.java` via `begin`, `end`, and `budget` (currently hardcoded in `askForUserInputs()` — uncomment the scanner lines to enable interactive input).
