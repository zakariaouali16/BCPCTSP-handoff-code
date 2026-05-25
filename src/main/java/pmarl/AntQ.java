import java.util.*;

/**
 * AntQ - Ant-Q Algorithm for BC-TSP
 *
 * Ant-Q is a prize-oblivious MARL algorithm. Unlike P-MARL which integrates
 * node prizes into its action mechanism, Ant-Q uses pheromone trails (tau) and
 * heuristic edge desirability (1/distance) to guide agents toward the shortest
 * prize-collecting path within the budget. Node prizes are NOT considered during
 * learning — agents simply try to find short, budget-feasible routes.
 *
 * Key differences from P-MARL:
 *   - Action rule uses pheromone (tau) and edge weight heuristic only (no prize term)
 *   - Pheromone is updated globally after each episode (evaporation + deposit)
 *   - No separate Reward table R; pheromone deposit is based on route length
 *   - Prize is still used at the END to score routes (execution stage)
 *
 * References: Paper Section IV-B; classic Ant-Q / ACS formulation.
 */
public class AntQ {

    // ── Tuneable hyperparameters ──────────────────────────────────────────────
    /** Number of episodes (training iterations). */
    static int    TRIALS     = 4000;
    /** Number of ants per episode. */
    static int    NUM_ANTS   = 5;
    /** Pheromone evaporation rate (0 < rho < 1). */
    static double RHO        = 0.1;
    /** Weight of pheromone in action rule (alpha in classic ACO notation). */
    static double PHI        = 1.0;
    /** Weight of heuristic (1/dist) in action rule (beta in classic ACO). */
    static double ETA_POWER  = 2.0;
    /** Exploitation probability threshold (q0 in ACS). */
    static double Q0         = 0.9;
    /** Initial pheromone value on every edge. */
    static double TAU_INIT   = 1.0;

    // ── Shared state (mirrors main.java style) ────────────────────────────────
    static double[][]         tau;       // pheromone matrix  [n][n]
    static int                statesCt;
    static double             budget;
    static Graph              sGraph;

    // Best route found during learning
    static ArrayList<Integer> bestRoute      = new ArrayList<>();
    static int                bestRoutePrize = Integer.MIN_VALUE;
    static int                bestRouteIter  = -1;

    // Final traversal outputs
    static ArrayList<Integer> finalRoute = new ArrayList<>();
    static double             finalDist  = 0;
    static int                finalPrize = 0;
    static double             remainingBudget = 0;

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * Run the full Ant-Q pipeline.
     *
     * @param graph     the pre-built Graph (with shortestPath already constructed)
     * @param budget    travel budget in miles
     * @param statesCt  number of nodes in the graph
     */
    public static void run(Graph graph, double budget, int statesCt) {
        AntQ.sGraph    = graph;
        AntQ.budget    = budget;
        AntQ.statesCt  = statesCt;

        initPheromone();
        learnAntQ();
        traverseAntQ();
    }

    // ── Initialization ────────────────────────────────────────────────────────

    /** Fill the pheromone matrix with TAU_INIT on all valid edges. */
    static void initPheromone() {
        tau = new double[statesCt][statesCt];
        for (int i = 0; i < statesCt; i++)
            for (int j = 0; j < statesCt; j++)
                tau[i][j] = (sGraph.weight(i, j) > 0) ? TAU_INIT : 0.0;
    }

    // ── Learning stage ────────────────────────────────────────────────────────

    /**
     * Main learning loop.
     *
     * Each episode:
     *   1. Send NUM_ANTS ants from node 0, each with its own copy of the graph.
     *   2. Each ant independently builds a route using the Ant-Q action rule.
     *   3. After all ants finish, apply global pheromone update using the best
     *      ant's route (the one with the highest collected prize).
     */
    static void learnAntQ() {
        for (int episode = 0; episode < TRIALS; episode++) {

            // -- Construct ant routes --
            @SuppressWarnings("unchecked")
            ArrayList<Integer>[] antPaths  = new ArrayList[NUM_ANTS];
            double[]             antDist   = new double[NUM_ANTS];
            int[]                antPrize  = new int[NUM_ANTS];

            for (int a = 0; a < NUM_ANTS; a++) {
                Graph copy = new Graph(sGraph);   // private copy so marks don't interfere
                antPaths[a] = buildAntRoute(copy, antDist, antPrize, a);
            }

            // -- Find the best ant this episode --
            int bestAnt = 0;
            for (int a = 1; a < NUM_ANTS; a++)
                if (antPrize[a] > antPrize[bestAnt]) bestAnt = a;

            // -- Global pheromone update --
            globalPheromoneUpdate(antPaths[bestAnt], antDist[bestAnt]);

            // -- Track overall best route --
            if (antPrize[bestAnt] > bestRoutePrize) {
                bestRoutePrize = antPrize[bestAnt];
                bestRoute      = new ArrayList<>(antPaths[bestAnt]);
                bestRouteIter  = episode;
            }
        }
    }

    /**
     * Build one ant's route using the Ant-Q (ACS-style) action rule.
     *
     * Action rule (prize-oblivious):
     *   Exploitation (rand <= Q0):  t = argmax_{u in feasible} [ tau(r,u)^PHI * eta(r,u)^ETA_POWER ]
     *   Exploration  (rand >  Q0):  select u with probability proportional to the same formula
     *
     * Note: no prize term — this is what makes Ant-Q prize-oblivious.
     *
     * The route is stored in antPaths[antIdx], distance in antDist[antIdx],
     * and prize in antPrize[antIdx].
     */
    static ArrayList<Integer> buildAntRoute(Graph g, double[] antDist, int[] antPrize, int antIdx) {
        ArrayList<Integer> path  = new ArrayList<>();
        double distSoFar  = 0;
        int    prizeTotal = 0;
        int    cur        = 0;

        g.setMark(0, Main.VISITED);
        path.add(0);

        while (true) {
            // Build feasible set: unvisited, non-destination nodes reachable within budget
            ArrayList<Integer> feasible = new ArrayList<>();
            int last = g.getLastNode();
            for (int i = 1; i < last; i++) {
                if (g.getMark(i) == Main.UNVISITED
                        && g.shortestPath(cur, i) + g.shortestPath(i, last) <= budget - distSoFar) {
                    feasible.add(i);
                }
            }

            if (feasible.isEmpty()) {
                // No feasible intermediate node — go directly to destination
                distSoFar += g.shortestPath(cur, last);
                g.printExtraPathIfNeeded(cur, last, path);
                // Ensure destination is in path (printExtraPathIfNeeded adds intermediate nodes)
                if (path.isEmpty() || path.get(path.size() - 1) != last)
                    path.add(last);
                break;
            }

            // -- Ant-Q action rule (prize-oblivious) --
            int    next;
            Random rand = new Random();
            if (rand.nextDouble() <= Q0) {
                // Exploitation: pick the node with highest pheromone*heuristic
                next = exploitation(cur, feasible, g);
            } else {
                // Exploration: probabilistic selection
                next = exploration(cur, feasible, g, rand);
            }

            // Collect prize (only if unvisited — handles detour case)
            if (g.getMark(next) == Main.UNVISITED) {
                prizeTotal += g.getPrize(next);
            }
            distSoFar += g.shortestPath(cur, next);

            // Handle detour nodes inserted by printExtraPathIfNeeded
            g.printExtraPathIfNeeded(cur, next, path);
            g.setMark(next, Main.VISITED);
            cur = next;
        }

        // Collect prize of destination node
        prizeTotal += g.getPrize(g.getLastNode());

        antDist[antIdx]  = distSoFar;
        antPrize[antIdx] = prizeTotal;
        return path;
    }

    /**
     * Exploitation: return argmax_{u in feasible} [ tau(r,u)^PHI * eta(r,u)^ETA_POWER ]
     * eta(r,u) = 1 / dist(r,u)  (heuristic desirability)
     */
    static int exploitation(int r, ArrayList<Integer> feasible, Graph g) {
        int    best    = feasible.get(0);
        double bestVal = Double.NEGATIVE_INFINITY;
        for (int u : feasible) {
            double dist = g.shortestPath(r, u);
            if (dist <= 0) continue;
            double eta = 1.0 / dist;
            double val = Math.pow(tau[r][u], PHI) * Math.pow(eta, ETA_POWER);
            if (val > bestVal) { bestVal = val; best = u; }
        }
        return best;
    }

    /**
     * Exploration: select u with probability p(r,u) = score(r,u) / sum(scores).
     * score(r,u) = tau(r,u)^PHI * eta(r,u)^ETA_POWER
     */
    static int exploration(int r, ArrayList<Integer> feasible, Graph g, Random rand) {
        double[] scores = new double[feasible.size()];
        double   total  = 0;
        for (int i = 0; i < feasible.size(); i++) {
            int u = feasible.get(i);
            double dist = g.shortestPath(r, u);
            if (dist <= 0) { scores[i] = 0; continue; }
            double eta = 1.0 / dist;
            scores[i] = Math.pow(tau[r][u], PHI) * Math.pow(eta, ETA_POWER);
            total += scores[i];
        }
        if (total == 0) return feasible.get(rand.nextInt(feasible.size()));

        // Normalize and roulette-wheel select
        double target = rand.nextDouble() * total;
        for (int i = 0; i < feasible.size(); i++) {
            target -= scores[i];
            if (target <= 0) return feasible.get(i);
        }
        return feasible.get(feasible.size() - 1);
    }

    /**
     * Global pheromone update (ACS-style):
     *   tau(i,j) <- (1 - RHO) * tau(i,j)  [evaporation on ALL edges]
     *   tau(i,j) <- tau(i,j) + RHO * delta_tau  [deposit on best-ant edges only]
     *   delta_tau = 1 / routeLength  (shorter route = more pheromone)
     */
    static void globalPheromoneUpdate(ArrayList<Integer> path, double routeLength) {
        // Evaporate all edges
        for (int i = 0; i < statesCt; i++)
            for (int j = 0; j < statesCt; j++)
                tau[i][j] *= (1.0 - RHO);

        // Deposit on best-ant path edges
        if (routeLength <= 0) return;
        double deposit = 1.0 / routeLength;
        for (int k = 0; k < path.size() - 1; k++) {
            int from = path.get(k);
            int to   = path.get(k + 1);
            tau[from][to] += RHO * deposit;
        }
    }

    // ── Execution stage ───────────────────────────────────────────────────────

    /**
     * Execution stage: the traveling salesman greedily follows the pheromone trail
     * (exploitation only, prize-aware tie-breaking) to collect prizes.
     *
     * Specifically, at each node we move to the feasible unvisited neighbor with
     * the highest pheromone*heuristic value. This mirrors the paper's description
     * that Ant-Q "finds the shortest prize-collecting path" within the budget.
     */
    static void traverseAntQ() {
        finalRoute  = new ArrayList<>();
        finalDist   = 0;
        finalPrize  = 0;

        // Reset marks
        for (int i = 0; i < statesCt; i++)
            sGraph.setMark(i, Main.UNVISITED);
        sGraph.setMark(sGraph.getLastNode(), Main.LAST_VISIT);

        int cur = 0;
        sGraph.setMark(0, Main.VISITED);
        finalRoute.add(0);

        while (cur != sGraph.getLastNode()) {
            ArrayList<Integer> feasible = new ArrayList<>();
            int last = sGraph.getLastNode();
            for (int i = 1; i < last; i++) {
                if (sGraph.getMark(i) == Main.UNVISITED
                        && sGraph.shortestPath(cur, i) + sGraph.shortestPath(i, last) <= budget - finalDist) {
                    feasible.add(i);
                }
            }

            int next;
            if (feasible.isEmpty()) {
                next = last;
            } else {
                next = exploitation(cur, feasible, sGraph);
            }

            finalDist += sGraph.shortestPath(cur, next);
            sGraph.printExtraPathIfNeeded(cur, next, finalRoute);
            sGraph.setMark(next, Main.VISITED);
            cur = next;
        }

        // Calculate prize from route (count each unique node once)
        int[] counted = new int[statesCt];
        for (int city : finalRoute) {
            if (counted[city] == 0) {
                counted[city] = 1;
                finalPrize   += sGraph.getPrize(city);
            }
        }

        remainingBudget = budget - finalDist;
    }
}