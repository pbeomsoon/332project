## Requirements

### 입력 사양

- 레코드 형식: 100바이트 (키 10바이트 + 값 90바이트)
- 입력 데이터: 각 워커에 32MB 블록들로 분산 저장
- 데이터 생성: gensort 도구 사용
- 입력 타입: ASCII 또는 바이너리 (옵션 없이 자동 감지)

### 시스템 구성

- 마스터: 1개 (코디네이터 역할)
- 워커: 20개 (실제 데이터 처리)
- 네트워크: IP 주소로 식별, 동적 포트 할당

### 출력 사양

- 워커 순서: 마스터가 정의한 워커 순서
- 파일 순서: 각 워커 내에서 partition.n, partition.n+1, ... 형식
- 파일 내용: 정렬된 레코드 (크기는 가변)
- 전역 정렬: 모든 워커의 모든 파일을 순서대로 읽으면 전체가 정렬됨

### 기능 요구사항

- 멀티스레딩: 정렬/파티션 및 병합 단계에서 병렬 처리
- Fault Tolerance: 워커 크래시 시 재시작하여 복구 가능
- 디스크 관리:
    - 입력 디렉토리는 읽기 전용 (수정 금지)
    - 출력 디렉토리에는 최종 결과만 저장 (임시 파일 정리 필수)

- 포트 관리: 하드코딩 금지, 동적 할당

### 비기능 요구사항

- 정확성: 모든 레코드가 올바르게 정렬되어야 함
- 성능: 단일 머신보다 빠르게 처리 (합리적인 속도)
- 확장성: 워커 수 증가 시 성능 향상
- 견고성: 네트워크 지연, 일시적 오류 처리

### 제약 사항

- 사용 금지 라이브러리: Akka, 분산 정렬 전용 시스템
- 권장 라이브러리: gRPC + Protobuf, Netty, java.net.*
- 메모리 제약: 전체 데이터를 메모리에 올릴 수 없음
- 디스크 제약: 전체 데이터를 단일 디스크에 저장할 수 없음

---

## Algorithm Outline

```
Phase 0: Initialization
Phase 1: Sampling
Phase 2: Sort & Partition
Phase 3: Shuffle
Phase 4: Merge
```

---

## Phase 0: Initialization

### Master
```
Algorithm: MasterInit(numWorkers)
Input: numWorkers - 워커 수
Output: Master IP:Port, Worker ordering

masterSocket ← bind random available port
print "Master IP:Port"
workerList ← empty list

// 워커 등록 대기
while workerList.size < numWorkers do
    connection ← accept incoming connection
    workerList.add(connection)
end while

// 워커 순서 출력
for each worker in workerList do
    print worker.IP
end for

return workerList
```

### Worker
```
Algorithm: WorkerInit(masterAddress, inputDirs, outputDir)
Input: masterAddress - 마스터 주소
       inputDirs - 입력 디렉토리 리스트
       outputDir - 출력 디렉토리
Output: Connection to master

inputBlocks ← scan all files in inputDirs
connection ← connect to masterAddress
send worker registration to master

return (connection, inputBlocks, outputDir)
```

---

## Phase 1: Sampling

### Master
```
Algorithm: MasterSampling(workerList, numPartitions)
Input: workerList - 워커 연결 리스트
       numPartitions - 파티션 개수 (일반적으로 워커 수와 동일 또는 배수)
Output: partitionBoundaries - 파티션 경계 키 배열

allSamples ← empty list

// 각 워커로부터 샘플 수집
for each worker in workerList do
    request samples from worker
end for

// 모든 샘플 수집
for each worker in workerList do
    samples ← receive samples from worker
    allSamples.addAll(samples)
end for

// 샘플 정렬
sort(allSamples)  // 키 기준으로 정렬

// 파티션 경계 결정
partitionBoundaries ← empty array[numPartitions - 1]
step ← allSamples.size / numPartitions

for i = 1 to numPartitions - 1 do
    partitionBoundaries[i-1] ← allSamples[i * step]
end for

// 각 워커에 파티션 경계 전송
for each worker in workerList do
    send partitionBoundaries to worker
end for

return partitionBoundaries
```

### Worker
```
Algorithm: WorkerSampling(inputBlocks, sampleRate)
Input: inputBlocks - 입력 블록 리스트
       sampleRate - 샘플링 비율 (예: 0.001 = 0.1%)
Output: samples - 샘플 키 배열

samples ← empty list
totalRecords ← 0

// 각 입력 블록에서 샘플 추출
for each block in inputBlocks do
    file ← open(block)
    recordCount ← file.size / 100  // 레코드당 100바이트
    numSamples ← max(1, recordCount * sampleRate)
    
    for i = 1 to numSamples do
        randomOffset ← random(0, recordCount - 1) * 100
        file.seek(randomOffset)
        record ← file.read(100)
        key ← record[0:10]  // 처음 10바이트
        samples.add(key)
    end for
    
    file.close()
end for

// 마스터에 샘플 전송
send samples to master

// 파티션 경계 수신
partitionBoundaries ← receive from master

return partitionBoundaries
```

---

## Phase 2: Sort & Partition

### Worker (멀티스레드)
```
Algorithm: WorkerSortAndPartition(inputBlocks, partitionBoundaries, numThreads)
Input: inputBlocks - 입력 블록 리스트
       partitionBoundaries - 파티션 경계 배열
       numThreads - 정렬 스레드 수
Output: partitionedFiles - 파티션별 파일 맵

taskQueue ← thread-safe queue
partitionedFiles ← concurrent map[partitionID → file handles]

// 태스크 큐에 모든 입력 블록 추가
for each block in inputBlocks do
    taskQueue.add(block)
end for

// 워커 스레드 생성
threads ← empty list
for i = 1 to numThreads do
    thread ← new Thread(SortPartitionWorker(taskQueue, partitionBoundaries, partitionedFiles))
    threads.add(thread)
    thread.start()
end for

// 모든 스레드 완료 대기
for each thread in threads do
    thread.join()
end for

// 각 파티션 파일 닫기
for each file in partitionedFiles.values() do
    file.close()
end for

return partitionedFiles
```

### Sort-Partition Worker Thread
```
Algorithm: SortPartitionWorker(taskQueue, partitionBoundaries, partitionedFiles)
Input: taskQueue - 처리할 블록 큐
       partitionBoundaries - 파티션 경계
       partitionedFiles - 출력 파일 맵

while not taskQueue.isEmpty() do
    block ← taskQueue.poll()
    if block is null then
        break
    end if
    
    // 블록 읽기
    records ← readAllRecords(block)
    
    // 정렬
    sort(records)  // 키(0-9바이트) 기준 정렬
    
    // 파티셔닝
    for each record in records do
        partitionID ← getPartitionID(record.key, partitionBoundaries)
        
        // 파티션 파일에 쓰기 (thread-safe)
        lock(partitionedFiles[partitionID])
        partitionedFiles[partitionID].write(record)
        unlock(partitionedFiles[partitionID])
    end for
end while
```

### Helper: Get Partition ID
```
Algorithm: getPartitionID(key, partitionBoundaries)
Input: key - 레코드 키 (10바이트)
       partitionBoundaries - 파티션 경계 배열
Output: partitionID - 파티션 번호

// 이진 탐색으로 파티션 찾기
left ← 0
right ← partitionBoundaries.length

while left < right do
    mid ← (left + right) / 2
    if key < partitionBoundaries[mid] then
        right ← mid
    else
        left ← mid + 1
    end if
end while

return left
```

---

## Phase 3: Shuffle

### Master
```
Algorithm: MasterCoordinateShuffle(workerList, numPartitions)
Input: workerList - 워커 리스트
       numPartitions - 파티션 총 개수
Output: shuffleMap - 파티션 할당 맵

// 파티션을 워커에 할당
shuffleMap ← map[partitionID → workerID]
partitionsPerWorker ← numPartitions / workerList.size

for partitionID = 0 to numPartitions - 1 do
    workerID ← partitionID / partitionsPerWorker
    if workerID >= workerList.size then
        workerID ← workerList.size - 1
    end if
    shuffleMap[partitionID] ← workerID
end for

// 각 워커에 셔플 맵 전송
for each worker in workerList do
    send shuffleMap to worker
end for

return shuffleMap
```

### Worker
```
Algorithm: WorkerShuffle(partitionedFiles, shuffleMap, workerList)
Input: partitionedFiles - 로컬 파티션 파일 맵
       shuffleMap - 파티션 할당 맵
       workerList - 모든 워커 정보
Output: receivedPartitions - 수신한 파티션 파일 리스트

receivedPartitions ← empty list
sendTasks ← empty list
receiveTasks ← empty list

// 각 파티션을 적절한 워커로 전송
for each (partitionID, file) in partitionedFiles do
    targetWorkerID ← shuffleMap[partitionID]
    
    if targetWorkerID == myWorkerID then
        // 자신이 담당하는 파티션
        receivedPartitions.add(file)
    else
        // 다른 워커로 전송
        task ← createSendTask(file, workerList[targetWorkerID])
        sendTasks.add(task)
    end if
end for

// 병렬로 파티션 전송 및 수신
sendThreadPool ← create thread pool
receiveThreadPool ← create thread pool

// 수신 대기 시작
for each partitionID where shuffleMap[partitionID] == myWorkerID do
    if 해당 파티션이 다른 워커에 있다면
        task ← createReceiveTask(partitionID)
        receiveTasks.add(task)
        receiveThreadPool.submit(task)
    end if
end for

// 전송 시작
for each task in sendTasks do
    sendThreadPool.submit(task)
end for

// 모든 전송/수신 완료 대기
sendThreadPool.awaitCompletion()
receiveThreadPool.awaitCompletion()

return receivedPartitions
```

### Send Task
```
Algorithm: SendPartitionTask(partitionFile, targetWorker)
Input: partitionFile - 전송할 파티션 파일
       targetWorker - 목적지 워커 정보

connection ← connect to targetWorker
send partitionID
send partitionFile.size

// 파일 전송 (스트리밍)
buffer ← new byte[BUFFER_SIZE]
file ← open(partitionFile)

while not file.eof() do
    bytesRead ← file.read(buffer)
    connection.send(buffer, bytesRead)
end while

file.close()
connection.close()

// 전송 완료 후 임시 파일 삭제
delete(partitionFile)
```

### Receive Task
```
Algorithm: ReceivePartitionTask(partitionID)
Input: partitionID - 수신할 파티션 ID
Output: receivedFile - 수신한 파일 경로

connection ← accept connection for partitionID
fileSize ← receive file size

// 임시 파일 생성
tempFile ← createTempFile("partition-" + partitionID)
file ← open(tempFile)

// 파일 수신 (스트리밍)
buffer ← new byte[BUFFER_SIZE]
bytesReceived ← 0

while bytesReceived < fileSize do
    bytesRead ← connection.receive(buffer)
    file.write(buffer, bytesRead)
    bytesReceived ← bytesReceived + bytesRead
end while

file.close()
connection.close()

return tempFile
```

---

## Phase 4: Merge (병합)

### Worker (멀티스레드)
```
Algorithm: WorkerMerge(receivedPartitions, outputDir, numThreads)
Input: receivedPartitions - 수신한 파티션 파일 리스트
       outputDir - 출력 디렉토리
       numThreads - 병합 스레드 수
Output: outputFiles - 최종 정렬된 출력 파일 리스트

// 파티션을 파티션 ID 순으로 정렬
sort(receivedPartitions by partitionID)

// 연속된 파티션들을 그룹으로 묶기
partitionGroups ← groupConsecutivePartitions(receivedPartitions, numThreads)

outputFiles ← empty list
mergeThreads ← empty list

// 각 그룹을 병렬로 병합
for each group in partitionGroups do
    outputFile ← outputDir + "/partition." + group.startID
    thread ← new Thread(MergePartitionGroup(group, outputFile))
    mergeThreads.add(thread)
    thread.start()
end for

// 모든 병합 완료 대기
for each thread in mergeThreads do
    thread.join()
    outputFiles.add(thread.outputFile)
end for

// 임시 파일 정리
for each partition in receivedPartitions do
    delete(partition)
end for

return outputFiles
```

### Merge Partition Group Thread
```
Algorithm: MergePartitionGroup(partitions, outputFile)
Input: partitions - 병합할 파티션 파일 리스트
       outputFile - 출력 파일 경로

// 각 파일의 입력 스트림 생성
inputStreams ← empty list
for each partition in partitions do
    stream ← openInputStream(partition)
    inputStreams.add(stream)
end for

// K-way 병합을 위한 우선순위 큐
minHeap ← new PriorityQueue(compare by key)

// 각 스트림에서 첫 레코드 읽기
for i = 0 to inputStreams.size - 1 do
    record ← inputStreams[i].readRecord()
    if record is not null then
        minHeap.add((record, i))  // (레코드, 스트림 인덱스)
    end if
end for

// 출력 파일 열기
output ← open(outputFile)

// K-way 병합
while not minHeap.isEmpty() do
    (minRecord, streamIndex) ← minHeap.poll()
    output.write(minRecord)
    
    // 해당 스트림에서 다음 레코드 읽기
    nextRecord ← inputStreams[streamIndex].readRecord()
    if nextRecord is not null then
        minHeap.add((nextRecord, streamIndex))
    end if
end while

// 파일 닫기
output.close()
for each stream in inputStreams do
    stream.close()
end for
```

---

## Fault Tolerance (장애 복구)

### Worker Checkpoint
```
Algorithm: WorkerWithCheckpoint(phases)
Input: phases - 실행할 단계 리스트

checkpointFile ← "checkpoint.state"

// 체크포인트 복구 시도
if exists(checkpointFile) then
    state ← load(checkpointFile)
    currentPhase ← state.lastCompletedPhase + 1
    restoredData ← state.data
else
    currentPhase ← 0
    restoredData ← null
end if

// 각 단계 실행
for phase = currentPhase to phases.length - 1 do
    try
        result ← executePhase(phases[phase], restoredData)
        
        // 체크포인트 저장
        state ← new State(phase, result)
        save(checkpointFile, state)
        
        restoredData ← result
    catch Exception e
        log("Phase " + phase + " failed: " + e)
        exit with error
    end try
end for

// 완료 후 체크포인트 삭제
delete(checkpointFile)
```

---

## 레코드 비교 함수

```
Algorithm: CompareRecords(recordA, recordB)
Input: recordA, recordB - 100바이트 레코드
Output: -1 (A < B), 0 (A == B), 1 (A > B)

keyA ← recordA[0:10]
keyB ← recordB[0:10]

for i = 0 to 9 do
    if keyA[i] < keyB[i] then
        return -1
    else if keyA[i] > keyB[i] then
        return 1
    end if
end for

return 0  // 모든 바이트가 같음
```
