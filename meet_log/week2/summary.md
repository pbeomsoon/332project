## Week 2 Summary: Design Phase Completion

### Major Achievements

This week, we completed the comprehensive design phase for our distributed sorting system. All major architectural decisions have been made, and detailed implementation guides have been created.

---

## Architecture Overview

### System Components

```
Master Node (1)
  ├─ Worker Registration & Identity Tracking
  ├─ Sample Collection & Boundary Calculation
  ├─ Phase Coordination & Synchronization
  └─ Fault Detection & Recovery Coordination

Worker Nodes (N)
  ├─ Deterministic Worker ID Generation
  ├─ Phase 1: Sampling
  ├─ Phase 2: Parallel Sort & Partition
  ├─ Phase 3: Concurrent Shuffle
  └─ Phase 4: Parallel K-way Merge
```

---

## Key Design Decisions

### 1. Fault Tolerance: Worker Re-registration

**Problem**: PDF requirement - "Worker crash → same output expected"

**Solution**: Deterministic Worker Identity
```scala
// Worker generates deterministic ID from input/output paths
workerId = hash(inputDirs.sorted + outputDir)

// Master tracks worker identity
WorkerIdentity(inputDirs, outputDir) → WorkerInfo

// On restart: Same dirs → Same worker ID → Same partition
```

**Benefits**:
- ✅ Partial recovery (only failed worker restarts)
- ✅ Other workers continue uninterrupted
- ✅ Worker-partition consistency maintained
- ✅ Deterministic execution guaranteed

---

### 2. Partition Strategy: N→N Mapping

```
numPartitions = numWorkers (1:1 mapping)
shuffleMap: partition.i → Worker i

Example (3 workers):
  Worker 0 → partition.0
  Worker 1 → partition.1
  Worker 2 → partition.2
```

**Rationale**:
- Simple and clear assignment
- Load balancing guaranteed
- Easy to track during fault recovery

---

### 3. Communication: gRPC + Protocol Buffers

**Chosen**: gRPC (recommended by PDF)

**Protocol Structure**:
```protobuf
service MasterService {
  rpc RegisterWorker(WorkerInfo) returns (RegisterResponse);
  rpc SubmitSample(SampleData) returns (Ack);
  rpc ReportCompletion(CompletionInfo) returns (Ack);
}

service WorkerService {
  rpc SetPartitionBoundaries(PartitionConfig) returns (Ack);
  rpc ShuffleData(stream ShuffleDataChunk) returns (ShuffleAck);
}
```

---

### 4. Parallelization Strategy

| Phase | Parallelization | Thread Pool Size |
|-------|----------------|------------------|
| Sampling | File I/O | numCores |
| Sorting | Chunk sorting | numCores |
| Partitioning | Partition writing | numCores |
| Shuffle | Send/Receive | numCores × 2 (I/O-bound) |
| Merge | K-way merge | min(numCores, 4) |

---

## Design Documents Structure

### docs/ - Detailed Technical Specifications

1. **0-implementation-decisions.md**
   - Core architectural decisions
   - Technology stack choices
   - Trade-off analysis

2. **1-phase-coordination.md**
   - Master-Worker synchronization
   - Phase transition protocols
   - Coordination patterns

3. **2-worker-state-machine.md**
   - Worker lifecycle states
   - State transition rules
   - Error state handling

4. **3-grpc-sequences.md**
   - Complete gRPC message definitions
   - Phase-by-phase RPC sequences
   - Example scenarios with timing

5. **4-error-recovery.md**
   - Fault tolerance taxonomy
   - Recovery decision matrix
   - Phase-specific recovery strategies
   - Worker re-registration implementation

6. **5-file-management.md**
   - FileLayout class design
   - Directory structure and naming conventions
   - Atomic file operations
   - Disk space management

7. **6-parallelization.md**
   - Thread pool configurations
   - Parallel sorting/merging algorithms
   - Concurrency control patterns
   - Performance optimization guidelines

### plan/ - Implementation Roadmap

1. **implementation_guide.md**
   - Complete implementation guide (1200+ lines)
   - Module-by-module code skeletons
   - Milestone-based development plan
   - Testing checklists
   - PDF requirements verification

---

## Fault Tolerance Deep Dive

### Deterministic Execution Guarantee

```scala
class WorkerNode(inputDirs, outputDir) {
  // 1. Deterministic Worker ID
  private val workerId = generateDeterministicWorkerId()

  private def generateDeterministicWorkerId(): String = {
    val identity = inputDirs.sorted.mkString("/") + "::" + outputDir
    s"W${identity.hashCode.abs}"
  }

  // 2. Deterministic Sampling (fixed seed)
  private def extractSamples(seed: Int = workerId.hashCode): Seq[Byte] = {
    val random = new Random(seed)
    // ... sampling with fixed seed
  }

  // 3. Deterministic File Processing (sorted order)
  private def scanInputFiles(): Seq[File] = {
    inputDirs.flatMap(listFilesRecursively).sortBy(_.getAbsolutePath)
  }
}
```

**Guarantee**: Same input/output dirs + Same input files → Same worker ID → Same samples → Same partitions → Same output

---

### Worker Crash Recovery Scenarios

**Phase 1-3 (Before Shuffle)**:
```
Worker W1 crashes during sorting
  ↓
W1 restarts
  ↓
Master detects same input/output dirs
  ↓
Assigns same worker ID: W1
  ↓
W1 performs sampling/sorting again (deterministic)
  ↓
W1 generates partition.1 (same as before)
  ↓
Continue to shuffle
```

**Phase 4-5 (During Shuffle/Merge)**:
```
Worker W1 crashes while sending partition.1
  ↓
W1 restarts
  ↓
W1 gets same worker ID from Master
  ↓
W1 regenerates partition.1 from scratch
  ↓
W1 resends partition.1 to recipient
  ↓
Recipient handles duplicate (idempotent)
  ↓
Continue merge
```

**Key**: Other workers (W0, W2) continue uninterrupted!

---

## File System Layout

```
Worker Root/
├── input/ (read-only)
│   ├── dir1/
│   │   └── data files
│   └── dir2/
│       └── data files
│
├── temp/ (temporary, cleaned up)
│   ├── sampling/
│   ├── sorting/
│   └── partitioning/
│
├── received/ (shuffle results)
│   └── partition.X files
│
└── output/ (final results)
    └── partition.N files
```

**Cleanup Strategy**:
- Sampling files: Deleted after sorting starts
- Sorted chunks: Deleted after partitioning complete
- Local partitions: Deleted after shuffle complete
- Received partitions: Deleted after merge complete
- Output: Keep only final partition files

---

## Implementation Milestones

### Milestone 1: Infrastructure (Week 3-4)
- [x] Design complete ✅
- [ ] build.sbt setup
- [ ] gRPC protocol definition
- [ ] Master/Worker skeletons
- [ ] Worker registration flow
- [ ] Dynamic port allocation

### Milestone 2: Core Sorting (Week 4-5)
- [ ] RecordReader (ASCII/Binary detection)
- [ ] Sampling module
- [ ] External sort with parallelization
- [ ] Partitioning logic

### Milestone 3: Distribution (Week 5-6)
- [ ] Shuffle sender/receiver
- [ ] Concurrent transfer management
- [ ] K-way merge implementation
- [ ] Output file generation

### Milestone 4: Fault Tolerance (Week 6)
- [ ] Worker identity tracking in Master
- [ ] Re-registration logic
- [ ] Deterministic execution verification
- [ ] Crash recovery testing

### Milestone 5: Testing & Optimization (Week 7-8)
- [ ] valsort verification
- [ ] Performance benchmarking
- [ ] Thread pool tuning
- [ ] End-to-end integration tests

---

## Technical Specifications Summary

| Aspect | Specification |
|--------|--------------|
| **Records** | 100 bytes (10 key + 90 value) |
| **Formats** | ASCII (102→100) or Binary (100) - auto-detect |
| **Workers** | N workers, dynamically registered |
| **Partitions** | N partitions (1:1 mapping) |
| **Boundaries** | Calculated from all worker samples |
| **Shuffle** | Concurrent with flow control (max 5 transfers) |
| **Merge** | K-way with priority queue |
| **Threads** | Phase-specific pools (numCores to numCores×2) |
| **Fault Tolerance** | Worker re-registration with deterministic ID |
| **Network** | gRPC + streaming for large data |
| **Output Format** | Line 1: Master IP:Port, Lines 2-N+1: Worker IPs |

---

## Next Steps (Week 3)

1. **Environment Setup**
   - Install Scala 2.13.x, sbt 1.9.x
   - Configure gRPC and ScalaPB
   - Set up cluster access

2. **Initial Implementation**
   - Create project structure
   - Define Protocol Buffers
   - Implement Main.scala CLI parser
   - Basic Master/Worker communication test

3. **Data Preparation**
   - Generate test data using gensort
   - Prepare small (1GB), medium (10GB), and large (50GB) datasets
   - Verify format detection

4. **First Milestone**
   - Complete worker registration flow
   - Verify dynamic port allocation
   - Test Master output format
   - Basic health check between Master and Workers

---

**Status**: Design Phase Complete ✅
**Next Phase**: Implementation Kickoff 🚀
**Target**: Milestone 1 completion by end of Week 4
