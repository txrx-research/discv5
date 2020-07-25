# Discovery v5 simulations

### Current work:

Distributed state simulation with [Adaptive Range Filter](src/main/kotlin/org/ethereum/discv5/util/filter/AdaptiveRangeFilter.kt) following [post in Ethresear.ch](https://ethresear.ch/t/sharding-state-with-discovery-and-adaptive-range-filter-ads/7573)

### Previous work:
[Simulator](src/main/kotlin/org/ethereum/discv5/Simulator.kt) for different advertisement options in Discovery V5 
- ENR attributes
- Topic advertisements

Setup creates a network of p2p nodes (without real network) with knowledge about other nodes according to configured Pareto distribution. Runs simulated advertisement workflow by steps, where step is single leg network message delivery. So 2 steps is round-trip, PING-PONG, for example.

Different configurations are made to exercise a number of metrics. By comparing the number of steps spent for each goal, the simulator measures efficiency of each approach. Traffic measurements give an idea on bandwidth system requirements. Plus, seed randomization helps to configure identical setups to have apple to apple comparison. 

Full write-up [published here](https://hackmd.io/@zilm/BJGorvHzL)