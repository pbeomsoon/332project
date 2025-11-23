# 깔끔한 출력 형식 (Slides 예시와 동일)

## 변경 사항

슬라이드 예시처럼 **stdout에는 필수 정보만** 출력하고, 모든 로그는 파일로 리다이렉트했습니다.

### 변경된 파일

1. **`src/main/resources/logback.xml`**
   - STDOUT appender → FILE appender로 변경
   - 로그 파일: `/tmp/distsort.log`
   - 모든 INFO/DEBUG 로그가 파일로만 저장됨

2. **`src/main/scala/distsort/master/Master.scala`**
   - `outputFinalResult()` 함수에서 println 제거
   - stdout 출력은 main()에서만 수행

## 출력 형식

### Master 출력 (Slides와 동일)

```bash
$ master 3
141.223.91.80:30040
141.223.91.81, 141.223.91.82, 141.223.91.83
$
```

**단 2줄만 출력:**
1. Master IP:port
2. Worker IPs (쉼표로 구분)

### Worker 출력 (Slides와 동일)

```bash
$ worker 141.223.91.80:30040 -I /data1/input /data2/input -O /home/user/data
$
```

**아무것도 출력 안 됨** (에러가 없으면)

### 로그 확인 방법

모든 로그는 `/tmp/distsort.log`에 저장됩니다:

```bash
# 실시간 로그 확인
tail -f /tmp/distsort.log

# 최근 50줄 확인
tail -50 /tmp/distsort.log

# 특정 패턴 검색
grep "Worker.*completed" /tmp/distsort.log
```

## Before vs After

### Before (❌ 로그가 너무 많음)

```bash
$ master 3
[INFO] Starting Master node with 3 workers...
[INFO] Master gRPC server started on port 30040
[INFO] Master registered with graceful shutdown manager
141.223.91.80:30040
[INFO] Starting distributed sorting workflow
[INFO] Phase 1: Waiting for 3 workers to register...
[INFO] Worker worker-1 registered successfully
[INFO] Worker worker-2 registered successfully
[INFO] Worker worker-3 registered successfully
[INFO] All 3 workers registered successfully
[INFO] Phase 2: Workers performing sampling...
[INFO] Worker worker-1 completed PHASE_SAMPLING (1/3)
...
(수십 줄의 로그가 계속 출력됨)
```

### After (✅ 깔끔함 - Slides와 동일)

```bash
$ master 3
141.223.91.80:30040
141.223.91.81, 141.223.91.82, 141.223.91.83
$
```

## 테스트

### 1. 깔끔한 출력 확인

```bash
# Terminal 1: Master
./master 3

# 출력 (2줄만):
# 10.1.25.21:40709
# localhost, localhost, localhost
```

```bash
# Terminal 2-4: Workers
./worker <master-ip:port> -I input1 -O output

# 출력: 없음 (에러가 없으면 조용히 실행)
```

### 2. 로그 모니터링 (별도 터미널)

```bash
# 로그 실시간 확인
tail -f /tmp/distsort.log
```

## 장점

✅ **stdout 깔끔**: 필수 정보만 출력 (Master IP:port, Worker IPs)
✅ **로그 분리**: 모든 디버그 정보는 파일에 저장
✅ **Slides 예시와 동일**: 사용법 예시와 정확히 일치
✅ **파이프라인 친화적**: stdout이 깔끔해서 스크립트 사용 가능
✅ **디버깅 가능**: 로그 파일에서 상세 정보 확인 가능

## 로그 파일 위치

- **위치**: `/tmp/distsort.log`
- **형식**: `HH:mm:ss.SSS [thread] LEVEL logger - message`
- **예시**:
  ```
  15:30:45.123 [main] INFO  distsort.master.Master - Starting Master node with 3 workers
  15:30:45.456 [grpc-default-executor-0] INFO  distsort.master.MasterService - Worker worker-1 registered
  15:30:50.789 [worker-thread-1] INFO  distsort.worker.Worker - Worker completed PHASE_SAMPLING
  ```

## 스크립트 예시

이제 stdout이 깔끔해서 스크립트에서 파싱하기 쉽습니다:

```bash
#!/bin/bash

# Master 시작하고 주소 받기
MASTER_ADDR=$(./master 3 | head -1)
echo "Master address: $MASTER_ADDR"

# Workers 시작
for i in {1..3}; do
    ./worker $MASTER_ADDR -I input${i} -O output &
done

# Workers 완료 대기
wait

# Worker IPs 확인
WORKER_IPS=$(./master 3 | tail -1)
echo "Workers: $WORKER_IPS"
```

---

**결론**: 이제 출력이 슬라이드 예시처럼 깔끔합니다! 📺✨
