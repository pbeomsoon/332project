## Progress in the week

### Design Phase Completion
- Completed comprehensive system design documentation
  - **docs/** folder: 7 detailed design documents covering all aspects
  - **plan/** folder: Implementation guide and requirements verification
- Finalized architecture decisions and implementation strategy

### Key Design Achievements

#### 1. Fault Tolerance Strategy
- **Decision**: Worker Re-registration (Interpretation B)
- **Approach**: Deterministic worker ID based on input/output directories
- **Benefits**:
  - Partial recovery when a worker crashes
  - Other workers continue without interruption
  - Worker-partition consistency maintained

#### 2. System Architecture Documents
- `0-implementation-decisions.md`: Core architectural decisions
- `1-phase-coordination.md`: Master-Worker phase synchronization
- `2-worker-state-machine.md`: Worker state transitions
- `3-grpc-sequences.md`: Detailed gRPC protocol sequences
- `4-error-recovery.md`: Comprehensive fault tolerance strategies
- `5-file-management.md`: File system layout and disk management
- `6-parallelization.md`: Multi-core parallelization strategy

#### 3. Implementation Guide
- `implementation_guide.md`: Complete implementation roadmap
  - Module-by-module implementation details
  - Code skeletons for all major components
  - Milestone-based development plan (Week 1-8)
  - Testing checklist

#### 4. Technical Specifications
- **Communication**: gRPC + Protocol Buffers (recommended)
- **Partition Strategy**: N→N mapping (numPartitions = numWorkers)
- **Worker ID**: Deterministic based on input/output directories
- **Sampling**: Deterministic with fixed seed (workerID.hashCode)
- **Parallelization**: Phase-specific thread pool configurations

### Individual Member Progress
Following Week 1 research assignments:

**권동연 (yeon903)** - Sampling, Sort/Partition
- Researched sampling strategies and boundary calculation
- Designed external sort with parallel chunk processing
- Defined partitioning logic with binary search

**박범순 (pbs7818)** - Shuffle
- Designed concurrent shuffle mechanism with semaphore-based flow control
- Planned idempotent partition transfer protocol
- Defined shuffle recovery strategies

**임지훈 (Jih00nLim)** - Merge, Fault-Tolerant
- Designed K-way merge with priority queue
- Finalized Worker Re-registration fault tolerance strategy
- Defined deterministic execution guarantees

## Goal of the next week

### Implementation Phase Kickoff
1. **Project Setup**
   - Initialize Scala/sbt project structure
   - Configure build.sbt with dependencies (gRPC, ScalaPB)
   - Set up logging framework

2. **Network Layer (Milestone 1)**
   - Define Protocol Buffers messages (distsort.proto)
   - Generate gRPC stubs
   - Implement basic Master/Worker skeleton
   - Test Master-Worker connection with dynamic port allocation

3. **Data Generation**
   - Generate test input data using gensort
   - Verify ASCII and binary format auto-detection
   - Prepare test datasets of various sizes (1GB, 10GB)

4. **Basic Workflow**
   - Implement Worker registration flow
   - Test Master output format (IP:Port, Worker IPs)
   - Verify N worker registration with timeout

## Goal of the next week for each individual member

### 권동연 (yeon903)
- Implement RecordReader (ASCII/Binary auto-detection)
- Develop sampling module with deterministic seed
- Begin external sort implementation with thread pool

### 박범순 (pbs7818)
- Implement gRPC protocol definitions
- Develop shuffle sender/receiver with streaming
- Create partition transfer test cases

### 임지훈 (Jih00nLim)
- Implement Worker state machine
- Develop file layout management (FileLayout class)
- Create Worker re-registration logic in Master

## Technical Decisions Made

### Architecture
- ✅ Worker Re-registration for Fault Tolerance
- ✅ gRPC for network communication
- ✅ N→N partition mapping (1:1)
- ✅ Deterministic execution with fixed seeds

### File Structure
```
project_2025/
├── build.sbt
├── src/main/
│   ├── scala/distsort/
│   │   ├── Main.scala
│   │   ├── master/
│   │   ├── worker/
│   │   └── common/
│   └── protobuf/
│       └── distsort.proto
```

### Phase Implementation Order
1. Week 3-4: Infrastructure + Sampling
2. Week 4-5: Sorting + Partitioning
3. Week 5-6: Shuffle + Merge
4. Week 6: Fault Tolerance
5. Week 7-8: Testing + Optimization

## Next Meeting Agenda
1. Review design documents and finalize any open questions
2. Divide implementation tasks for Milestone 1
3. Set up development environment and cluster access
4. Create initial gRPC protocol and test connection
5. Plan Week 3 deliverables

---

**Design Documentation**: See `docs/` and `plan/` folders for complete technical specifications.
