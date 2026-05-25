import java.io.*;
import java.util.*;

public class TableData {

    //meta variable
    static String fileName = "city_rewards.csv";
    static int TOTAL_DATA = 20;                 // <-- 20 runs per budget
    static int SAMPLE_SIZE = 40;                // cities sampled from the CSV each run
    // Budgets are in the CSV's own coordinate units (Euclidean), not miles.
    static int[] BUDGETS_ARRAY = new int[] {500000, 1000000, 1500000, 2000000};
    static int[] AGENTS_ARRAY = new int[] {1,5,10,15,20};

    // Full city catalog loaded once from the CSV; each run samples SAMPLE_SIZE of these.
    static ArrayList<CityNode> allCities;
    static ArrayList<CityNode> sample;          // the cities chosen for the current run

    //CHANGE CITY AND PRIZEGOAL
    static String begin = "";
    static String end = "";
    static double budget = 0; // budget in CSV coordinate units
    private static double remainingBudget;

    //static variables to be tweaked by user
    static int TRIALS = 2500;
    static int NUM_AGENTS = 5;
    static final double W = 100.0;      // constant value to update the reward table
    static final double alpha = 0.1;  // learning rate
    static final double gamma = 0.4;    // discount factor
    static final double delta = 1;      // power for Q value
    static final double beta = 2;       // power for distance
    static final double q0 = 0.8;       // coefficient for exploration and exploitation

    //flags for graph (do not touch)
    static final int UNVISITED = 0;
    static final int VISITED = 1;
    static final int LAST_VISIT = 2;

    //pre-initialization parameters (do not touch)
    static LinkedList<CityNode> arrCities;
    static Graph sGraph;
    static double[][] Q;
    static double[][] R;
    static int statesCt;
    static int total_prize = 0;
    static double total_wt = 0;
    static ArrayList<Integer> route;

    // variables for extra credits
    static long randomSeed = 12345;     // random seed for inaccessible edge, can change to any long
    static double missingProb = 0.0;    // probability of an inaccessible edge


    public static void main(String[] args) throws IOException
    {
        loadAllCities();   // read city_rewards.csv once
        System.out.printf("Loaded %d cities from %s. Sampling %d per run, %d runs per budget.%n%n",
                allCities.size(), fileName, SAMPLE_SIZE, TOTAL_DATA);

        for (int b : BUDGETS_ARRAY) {
            double[][] greedy1Data = new double[2][TOTAL_DATA];
            double[][] greedy2Data = new double[2][TOTAL_DATA];
            double[][] MARLData     = new double[2][TOTAL_DATA];
            double[][] antQData     = new double[2][TOTAL_DATA];

            for (int i = 0; i < TOTAL_DATA; i++) {
                System.out.println("Budget " + b + "  ROUND " + (i + 1) + "/" + TOTAL_DATA);
                generateRandomCities(b, i);

                PrintStream realOut = System.out;                       // silence detour debug spam
                System.setOut(new PrintStream(OutputStream.nullOutputStream()));

                // ---- BC-PC-TSP MARL ----
                initList();
                initGraph();
                initStatics();
                learnQ();
                traverseQ();
                MARLData[0][i] = total_prize;
                MARLData[1][i] = total_wt;

                // ---- Greedy 1 (max prize) ----  reuses the same graph
                traverseP();
                greedy1Data[0][i] = total_prize;
                greedy1Data[1][i] = total_wt;

                // ---- Greedy 2 (max prize/dist ratio) ----
                traverseR();
                greedy2Data[0][i] = total_prize;
                greedy2Data[1][i] = total_wt;

                // ---- Ant-Q ----
                AntQ.TRIALS = TRIALS;
                AntQ.run(sGraph, budget, sGraph.n());
                antQData[0][i] = AntQ.finalPrize;
                antQData[1][i] = AntQ.finalDist;

                System.setOut(realOut);                                  // restore for summary
            }

            System.out.printf("%n============= For budget %d =============%n", b);
            printAlgo("Greedy 1 (max prize)",       greedy1Data);
            printAlgo("Greedy 2 (max prize/dist)",   greedy2Data);
            printAlgo("MARL (BC-PC-TSP)",            MARLData);
            printAlgo("Ant-Q",                       antQData);
            System.out.println();
        }
    }

    /** Pretty-print per-run prizes/distances plus their averages. */
    static void printAlgo(String label, double[][] data) {
        System.out.println("-- " + label + " --");
        System.out.println("  Prizes:    " + Arrays.toString(data[0]));
        System.out.println("  Distances: " + Arrays.toString(data[1]));
        System.out.printf ("  Avg prize: %.1f   Avg distance: %.1f%n",
                avg(data[0]), avg(data[1]));
    }

    static double avg(double[] a) {
        double s = 0;
        for (double v : a) s += v;
        return a.length == 0 ? 0 : s / a.length;
    }

    /** Read the entire city_rewards.csv once into {@link #allCities}. */
    static void loadAllCities() {
        allCities = new ArrayList<>();
        File f = new File("src/" + fileName);
        try (Scanner scan = new Scanner(f)) {
            String header = scan.hasNextLine() ? scan.nextLine() : ""; // skip "city_id,x,y,reward"
            while (scan.hasNextLine()) {
                String line = scan.nextLine().trim();
                if (line.isEmpty()) continue;
                String[] p = line.split(",");
                String id   = p[0].trim();
                double x    = Double.parseDouble(p[1].trim());
                double y    = Double.parseDouble(p[2].trim());
                int reward  = Integer.parseInt(p[3].trim());
                CityNode c = new CityNode("C" + id, x, y, reward, true);
                c.originalIndex = Integer.parseInt(id);
                allCities.add(c);
            }
        } catch (FileNotFoundException p) {
            System.out.println("FILE NOT FOUND: src/" + fileName);
            System.exit(0);
        }
        if (allCities.size() < SAMPLE_SIZE) {
            System.out.println("CSV has fewer cities than SAMPLE_SIZE.");
            System.exit(0);
        }
    }

    /**
     * Pick SAMPLE_SIZE distinct cities from the full CSV catalog for this run.
     * Seeded by the run index so each of the 20 runs is different but reproducible.
     * The first sampled city becomes the start = end (a round trip).
     */
    static void generateRandomCities(int b, int idx) {
        budget = b;
        Random rnd = new Random(randomSeed + idx);
        ArrayList<CityNode> pool = new ArrayList<>(allCities);
        Collections.shuffle(pool, rnd);
        sample = new ArrayList<>(pool.subList(0, SAMPLE_SIZE));
        begin = sample.get(0).name;
        end   = sample.get(0).name;
    }

    /*
     * Documentation for initList()
     * (1) attempts to read in file (throws error if file not found)
     * (2) converts text file info into cityNode object and places into array for use in creating graph
     * (3) optional scanner functionality for user inputted begin and end points
     * (4) restructure arrayList to make BEGIN city the first node, and END city the last node
     */
    static void initList()
    {
        arrCities = new LinkedList<>();
        ArrayList<String> nameList = new ArrayList<>();

        // (2) build the working list from this run's sampled cities (deep copies)
        for (CityNode c : sample) {
            CityNode copy = new CityNode(c);
            arrCities.add(copy);
            nameList.add(copy.name.toLowerCase());
        }

        //(3)
        String startCity = begin.toLowerCase();
        String endCity   = end.toLowerCase();

        //(4) put START at index 0 and END as the last node
        if (nameList.contains(startCity) && nameList.contains(endCity))
        {
            if (startCity.equalsIgnoreCase(endCity))
            {
                int sIndex = nameList.indexOf(startCity);
                CityNode t1 = new CityNode(arrCities.get(sIndex));
                arrCities.remove(sIndex);
                nameList.remove(sIndex);

                arrCities.add(0, t1);
                arrCities.add(t1);
            }
            else
            {
                int sIndex = nameList.indexOf(startCity);
                CityNode t1 = new CityNode(arrCities.get(sIndex));
                arrCities.remove(sIndex);
                nameList.remove(sIndex);

                int fIndex = nameList.indexOf(endCity);
                CityNode t2 = new CityNode(arrCities.get(fIndex));
                arrCities.remove(fIndex);

                arrCities.add(0, t1);
                arrCities.add(t2);
            }
        }
        else
        {
            System.out.println("Cannot find city.");
            System.exit(0);
        }
    }

    /*
     * Documentation for initGraph()
     * This function simply initializes the graph with requisite flags,
     * prize and weight values for each node,
     * and a name for each node (debug purposes only)
     */
    static void initGraph()
    {
        // we can have different keys for difference cases
        Random rand = new Random(randomSeed);
        sGraph = new Graph();
        int size = arrCities.size();
        sGraph.Init(size);

        for(int i = 0; i < size; i++)
        {
            sGraph.setMark(i, UNVISITED);
            sGraph.setName(i, arrCities.get(i).name);
            sGraph.setPrize(i, arrCities.get(i).pop);
            for(int j = 0; j < i + 1; j++) {
                if (i == j) {
                    sGraph.setEdge(i, j, 0);
                } else {
                    // randomly mark some path as inaccessible
                    if (rand.nextDouble() > missingProb) {
                        double d = CityNode.getPlanarDistance(arrCities.get(i), arrCities.get(j));
                        if (d <= 0) d = 1e-3; // avoid 0-weight edges between coincident cities
                        sGraph.setEdge(i, j, d);
                        sGraph.setEdge(j, i, d);
                    } else {
                        sGraph.setEdge(i, j, Double.MAX_VALUE);
                        sGraph.setEdge(j, i, Double.MAX_VALUE);
                    }
                }
            }
        }
        sGraph.setMark(sGraph.getLastNode(), LAST_VISIT);
        sGraph.constructShortestPath();
    }

    /*
     * Documentation for initStatics()
     * This function simply initializes the 2d arrays used for Q-Learning
     * Reward of edge (i, j), initially -w(i,j)/pv
     */
    static void initStatics()
    {
        statesCt = sGraph.n();
        Q = new double[statesCt][statesCt];
        R = new double[statesCt][statesCt];

        for (int i = 0; i < statesCt; i++)
            for (int j = 0; j < statesCt; j++)
            {
                R[i][j] = sGraph.weight(i, j)/sGraph.getPrize(j) * -1;
                Q[i][j] = (sGraph.getPrize(i) + sGraph.getPrize(j)) / sGraph.weight(i, j);
            }
    }

    /*
     * Documentation for learnQ()
     * learnQ() is the primary function for the program
     *
     * (1) All the m agents are initially located at the starting node s with zero collected prizes.
     *     [line 236-242]
     *
     * (2) Then each independently follows the action rule to move to the next node to collect prizes and
     *     collaboratively updates the Q-value.
     *     [line 252-262]
     *
     * (3) When an agent can no longer find a feasible unvisited node to move to due to its insufficient budget,
     *     it terminates and goes to t.
     *     [line 246]
     *
     * (4) Otherwise, it moves to the next node and collects the prize and continues the prize-collecting process.
     *     [line 248-265]
     *
     * (5) Then, it finds among the m routes the one with the maximum collected prizes and updates the
     *     reward value and Q-value of the edges that belong to this route.
     *     [line 269-281]
     */
    static void learnQ()
    {
        for (int i = 0; i < TRIALS; i++)
        {
            Agent[] aList = new Agent[NUM_AGENTS];
            for (int j = 0; j < NUM_AGENTS; j++)
            {
                Graph newGraph = new Graph(sGraph);
                Agent a = new Agent(statesCt, budget, newGraph);
                aList[j] = a;
            }

            while (!allQuotaMet(aList))
            {
                for (int j = 0; j < NUM_AGENTS; j++)
                {
                    Agent aj = aList[j];

                    if (!aj.isDone) {
                        int nextState = getNextStateFromCurState(aj, aj.curState, 1 - q0 * (TRIALS - i)/ TRIALS);
                        if (nextState == aj.getLastNode()) {
                            aj.isDone = true;
                        }
                        double maxQ = maxQ(aj, nextState);
                        Q[aj.curState][nextState] = (1-alpha) * Q[aj.curState][nextState] + alpha * gamma * maxQ;
                        aj.indexPath.add(nextState);
                        aj.total_wt += aj.shortestPath(aj.curState, nextState);
                        aj.total_prize += aj.getTotalPrize(nextState);
                        aj.setAgentMark(nextState, VISITED);
                        aj.curState = nextState;
                    }
                }
            }

            int mostFitIndex = findHighestPrize(aList);

            Agent jStar = aList[mostFitIndex];
            ArrayList<Integer> path = jStar.indexPath;
            jStar.resetAgentMarks();

            for (int v = 0; v < path.size()-1; v++)
            {
                double q = Q[path.get(v)][path.get(v+1)];
                double maxQ = maxQ(jStar, path.get(v+1));
                R[path.get(v)][path.get(v+1)] += (W/jStar.total_prize);
                Q[path.get(v)][path.get(v+1)] = (1-alpha) * q + alpha * (R[path.get(v)][path.get(v+1)] + gamma * maxQ);
            }
        }
    }

    /*
     * Documentation for allQuotaMet()
     * This function checks if every agent has no extra budget to travel other than goes to the destination
     * if every agent in aList does, return true, and break out of while loop. else, return false, while loop continues
     */
    static boolean allQuotaMet(Agent[] aList)
    {
        int trueCounter = 0;

        for (int i = 0; i < aList.length; i++)
            if (aList[i].isDone) trueCounter++;

        if (trueCounter == aList.length)
            return true;
        else
            return false;
    }

    /*
     * Documentation for findHighestPrize
     * This function find the agent in the list with the highest prize collected
     * it then returns its index, and becomes agent jStar
     */
    static int findHighestPrize(Agent[] aList)
    {
        double runningHigh = Double.NEGATIVE_INFINITY;
        int index = 0;
        for (int j = 0; j < aList.length; j++)
        {
            if (aList[j].total_prize > runningHigh)
            {
                runningHigh = aList[j].total_prize;
                index = j;
            }
        }
        return index;
    }

    /*
     * Documentation for reset()
     * resets pertinent variables and graph flags for use in final traversal (see traverse())
     */
    static void reset()
    {
        total_wt = 0;
        total_prize = 0;
        remainingBudget = 0.0;
        route = new ArrayList<>();
        for (int i = 0; i < statesCt; i++)
            sGraph.setMark(i, UNVISITED);
        sGraph.setMark(sGraph.getLastNode(), LAST_VISIT);
    }

    /*
     * Documentation for getNextStateFromCurState()
     * This function returns the next state for a specific agent according to action rule in BC-PC-TSP
     * The q0 passed to this function will change according to different trails, from small to large
     * At the very end, we won't do exploration at all
     */
    static int getNextStateFromCurState(Agent aj, int s, double q0)
    {
        ArrayList<Integer> feasible = new ArrayList<>();
        for (int i = 1; i < aj.getLastNode(); i++) {
            if (aj.getMark(i) == UNVISITED && aj.shortestPath(s, i) + aj.shortestPath(i, aj.getLastNode()) < aj.budget - aj.total_wt) {
                feasible.add(i);
            }
        }

        if (feasible.size() == 0) {
            return aj.getLastNode();
        } else {
            Random rand = new Random();
            if (rand.nextDouble() > q0) {
                // Exploration
                double[] prob = new double[feasible.size()];
                double total = 0;
                for (int i = 0; i < feasible.size(); i++) {
                    int u = feasible.get(i);
                    prob[i] = Math.pow(Q[s][u], delta) * aj.getPrize(u) / Math.pow(aj.weight(s,u), beta);
                    total += prob[i];
                }
                // uniform the distribution
                for (int i = 0; i < feasible.size(); i++) {
                    prob[i] /= total;
                }

                double target = rand.nextDouble();
                int idx = -1;
                while (target > 0) {
                    idx++;
                    target -= prob[idx];
                }
                return feasible.get(idx);
            } else {
                // Exploitation
                int maxIdx = -1;
                double curMax = Double.NEGATIVE_INFINITY;
                for (int i = 0; i < feasible.size(); i++) {
                    int u = feasible.get(i);
                    double val = Math.pow(Q[s][u], delta) * aj.getPrize(u) / Math.pow(aj.weight(s,u), beta);
                    if (val > curMax) {
                        maxIdx = i;
                        curMax = val;
                    }
                }
                return feasible.get(maxIdx);
            }
        }
    }

    /*
     * Documentation for maxQ
     * This function will find the max Q value among all the unvisited states within the feasible set
     */
    static double maxQ(Agent aj, int nextState)
    {
        ArrayList<Integer> result = new ArrayList<>();
        for (int i = 0; i < statesCt; i++)
            if (aj.shortestPath(nextState, i) != 0 && aj.getMark(i) == UNVISITED)
                result.add(i);
        int[] possibleActions = result.stream().mapToInt(i -> i).toArray();

        //the learning rate and eagerness will keep the W value above the lowest reward
        double maxValue = 0;
        for (int nextAction : possibleActions)
        {
            double value = Q[nextState][nextAction];

            if (value > maxValue)
                maxValue = value;
        }
        return maxValue;
    }

    /*
     * Documentation for getFeasibleSet
     * Given the current node s wherein the traveling salesman is located and his current available budget B,
     * the set of s’s neighbor nodes that the salesman can travel to while still having enough budgets to go to
     * destination node t is called node s's feasible set.
     * This function will be used across all algorithms
     */
    private static ArrayList<Integer> getFeasibleSet(int s) {
        ArrayList<Integer> feasible = new ArrayList<>();
        for (int i = 1; i < sGraph.getLastNode(); i++) {
            if (sGraph.getMark(i) == UNVISITED &&
                sGraph.shortestPath(s, i) + sGraph.shortestPath(i, sGraph.getLastNode()) < budget - total_wt
            ) {
                feasible.add(i);
            }
        }
        return feasible;
    }

    /*
     * Documentation for traverse() and DFSGreed_P()
     * This function handles the final traversal using the Q-Table as reference
     * DFSGreed_P finds the index of the largest Q-Value in the table for the current node
     * then visits it, and repeats until the prizeGoal is met.
     * Includes printouts for debugging
     */
    private static void traverseQ() {
        reset();
        DFSGreed_Q();
        remainingBudget = budget - total_wt; // updates remaining budget
    }

    static void DFSGreed_Q() {
        int curState = 0;
        sGraph.setMark(curState, VISITED);
        route.add(curState);
        while (curState != sGraph.getLastNode()) {
            int nexState = getHighestQ(curState);
            if (nexState == -1) {
                nexState = sGraph.getLastNode();
                if (total_wt + sGraph.weight(curState, nexState) > budget) {
                    // early return if we don't have enough budget to go to the end
                    break;
                }
            }
            sGraph.setMark(nexState, VISITED);
            sGraph.printExtraPathIfNeeded(curState, nexState, route);
            total_wt += sGraph.shortestPath(curState, nexState);
            curState = nexState;
        }
        total_prize = 0;
        for (int city : route) {
            if (sGraph.getMark(city) != 42) {
                sGraph.setMark(city, 42);
                total_prize += sGraph.getPrize(city);
            }
        }
    }

    private static void traverseR()
    {
        reset();
        int r = 0; // current city
        boolean flag = true;
        ArrayList<Integer> sortedNodes = new ArrayList<>();
        for (int i = 1; i < sGraph.getLastNode(); i++) {
            // ignoring first and last cities since they are beg/end
            // we will sort it later inside the while loop
            sortedNodes.add(i);
        }

        route.add(r);
        while (budget > total_wt && flag) {
            final int rFinal = r;
            // sort all nodes in descending order of their prizes cost ratio
            sortedNodes.sort(
                (o1, o2) ->
                    Double.compare(
                        sGraph.getPrize(o2) / sGraph.shortestPath(o2, rFinal),
                        sGraph.getPrize(o1) / sGraph.shortestPath(o1, rFinal)
                    )
            );
            int k;
            for (k = 0; k < sortedNodes.size(); k++) {
                int maxPrizeRatioCity = sortedNodes.get(k);

                if (getFeasibleSet(r).contains(maxPrizeRatioCity)) {
                    sGraph.printExtraPathIfNeeded(r, maxPrizeRatioCity, route);
                    total_wt += sGraph.shortestPath(r, maxPrizeRatioCity);
                    r = maxPrizeRatioCity;
                    sGraph.setMark(maxPrizeRatioCity, VISITED);
                    break;
                }
            }
            if (k == sortedNodes.size()) flag = false;
        }
        if (budget >= total_wt + sGraph.shortestPath(r, sGraph.getLastNode())) {
            sGraph.printExtraPathIfNeeded(r, sGraph.getLastNode(), route);
            total_wt += sGraph.shortestPath(r, sGraph.getLastNode());
        }
        remainingBudget = budget - total_wt; //updates remaining budget
        total_prize = 0;
        for (int city : route) {
            if (sGraph.getMark(city) != 42) {
                sGraph.setMark(city, 42);
                total_prize += sGraph.getPrize(city);
            }
        }
    }


    private static void traverseP()
    {
        reset();
        ArrayList<Integer> sortedNodes = new ArrayList<>();
        for (int i = 1; i < sGraph.getLastNode(); i++) {
            // ignoring first and last cities since they are beg/end
            sortedNodes.add(i);
        }

        // sort all nodes in descending order of their prizes
        sortedNodes.sort(
            Comparator.comparingInt(o -> -sGraph.getPrize(o))
        );
        int k = 0; // iterator for sortedNode
        int r = 0; // current city
        route.add(r);
        while (total_wt < budget && k < sortedNodes.size()) {
            int maxPrizeCity = sortedNodes.get(k);
            if (getFeasibleSet(r).contains(maxPrizeCity)) {
                sGraph.printExtraPathIfNeeded(r, maxPrizeCity, route);
                total_wt += sGraph.shortestPath(r, maxPrizeCity);
                r = maxPrizeCity;
                sGraph.setMark(maxPrizeCity, VISITED);
            }
            k++;
        }
        if (budget >= total_wt + sGraph.shortestPath(r, sGraph.getLastNode())) {
            sGraph.printExtraPathIfNeeded(r, sGraph.getLastNode(), route);
            total_wt += sGraph.shortestPath(r, sGraph.getLastNode());
        }
        remainingBudget = budget - total_wt; //updates remaining budget
        total_prize = 0;
        for (int city : route) {
            if (sGraph.getMark(city) != 42) {
                sGraph.setMark(city, 42);
                total_prize += sGraph.getPrize(city);
            }
        }
    }

    /*
     * Documentation for getHighestQ()
     * This function finds the index of the largest Q-Table value for the current node (v) and returns it.
     * It makes sure to exclude VISITED nodes, and its own node.
     */
    private static int getHighestQ(int v)
    {
        double runningHigh = Double.NEGATIVE_INFINITY;
        int index = -1;
        for (int i = 1; i < sGraph.getLastNode(); i++)
            if (Q[v][i] > runningHigh && sGraph.getMark(i) == UNVISITED && sGraph.shortestPath(v, i) + sGraph.shortestPath(i, sGraph.getLastNode()) < budget - total_wt)
            {
                runningHigh = Q[v][i];
                index = i;
            }

        return index;
    }
}
