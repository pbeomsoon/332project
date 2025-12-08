# Distributed Sorting System

2025 Fall CSED332 Software Design Methods - Team Project

## Members
| 이름 | GitHub |
|------|--------|
| 권동연 | yeon903 |
| 박범순 | pbs7818 |
| 임지훈 | Jih00nLim |

---

## Project Overview

### 목표
여러 대의 컴퓨터가 협력하여 대용량 데이터를 정렬하는 **분산 정렬 시스템** 구현

### 핵심 기능
- **Master-Worker 아키텍처**: 중앙 조율자(Master)와 실행자(Worker)들의 협업
- **6단계 워크플로우**: Sampling → Partitioning → Sorting → Shuffling → Merging → Complete
- **Fault Tolerance**: Worker 장애 감지 및 자동 복구
- **동적 파티셔닝**: 데이터 크기에 따른 최적 파티션 수 자동 계산

### 시스템 구조

```
                    ┌─────────────────┐
                    │     Master      │
                    │  (Coordinator)  │
                    └────────┬────────┘
                             │
           ┌─────────────────┼─────────────────┐
           │                 │                 │
           ▼                 ▼                 ▼
    ┌────────────┐    ┌────────────┐    ┌────────────┐
    │  Worker 0  │    │  Worker 1  │    │  Worker 2  │
    └────────────┘    └────────────┘    └────────────┘
```

---

## Quick Start

### 1. 빌드

```bash
cd main/
sbt assembly
# 결과: target/scala-2.13/distsort.jar
```

### 2. 실행

#### Master 실행
```bash
# 방법 1: JAR 직접 실행
java -jar target/scala-2.13/distsort.jar master 3

# 방법 2: 스크립트 사용 (PATH 설정 후)
master 3

# 방법 3: sbt 직접 실행
sbt "runMain distsort.master.Master 3"
```

#### Worker 실행
```bash
# 방법 1: JAR 직접 실행
java -jar target/scala-2.13/distsort.jar worker <Master IP:Port> -I <입력> -O <출력>

# 방법 2: 스크립트 사용
worker 192.168.1.100:50051 -I /dataset/small -O ~/out/small

# 방법 3: sbt 직접 실행
sbt "runMain distsort.worker.Worker 192.168.1.100:50051 -I input1 -O output"
```

### 3. 실행 예시 (3대 서버)

```bash
# 서버 A - Master
java -jar distsort.jar master 3
# 출력: 192.168.1.100:50051

# 서버 B - Worker 0
java -jar distsort.jar worker 192.168.1.100:50051 -I /dataset/small -O ~/out

# 서버 C - Worker 1
java -jar distsort.jar worker 192.168.1.100:50051 -I /dataset/small -O ~/out

# 서버 D - Worker 2
java -jar distsort.jar worker 192.168.1.100:50051 -I /dataset/small -O ~/out
```

---

## Project Structure

```
project_2025/
├── main/                    # 메인 소스 코드
│   ├── src/
│   │   ├── main/scala/      # Scala 소스
│   │   └── test/scala/      # 테스트 코드
│   ├── master               # Master 실행 스크립트
│   ├── worker               # Worker 실행 스크립트
│   └── build.sbt            # 빌드 설정
│
├── docs/                    # 설계 문서
│   ├── 0-implementation-decisions.md
│   ├── 1-phase-coordination.md
│   └── ...
│
├── plan/                    # 계획 문서
├── worklog/                 # 작업 기록
├── report/                  # 발표 자료
└── git_project_forder/      # Git 제출용
```

---

## Output Specification

### Master 출력 (STDOUT)
```
<Master IP:Port>
<Worker IPs (comma-separated)>
```

예시:
```
192.168.1.100:50051
192.168.1.101, 192.168.1.102, 192.168.1.103
```

### Worker 출력 파일
```
output/
├── partition.0    # 정렬됨
├── partition.1    # 정렬됨
├── partition.2    # 정렬됨
└── ...
```

---

## Verification Checklist

| 항목 | 상태 |
|------|------|
| 1. Master가 Worker 순서를 출력하는가? | Yes |
| 2. 각 Worker의 출력이 정렬되어 있는가? | Yes |
| 3. 입력 레코드 수 == 출력 레코드 수? | Yes |

---

## Key Features

### Fault Tolerance (장애 허용)
- Heartbeat 기반 Worker 상태 모니터링 (2초 주기)
- 4초 이내 Worker 장애 감지
- 같은 호스트에서 복구 Worker 자동 인식
- Checkpoint 기반 작업 복구

### Dynamic Partitioning (동적 파티셔닝)
- 목표 파티션 크기: 32MB
- Worker 수의 배수로 파티션 수 자동 계산
- Worker당 최대 500개 파티션 지원

### Phase Timeouts
| Phase | Timeout |
|-------|---------|
| Sampling | 3분 |
| Sorting | 15분 |
| Shuffling | 30분 |
| Merging | 15분 |

---

## Technology Stack

- **Language**: Scala 2.13
- **Build Tool**: sbt
- **RPC Framework**: gRPC 1.54
- **Protocol**: Protocol Buffers (ScalaPB)
- **Testing**: ScalaTest

---

## Timeline

| Week | 내용 |
|------|------|
| Week 1 (10/14~) | 프로젝트 시작, 환경 설정 |
| Week 2 | 설계 문서 작성 |
| Week 3 | 기본 구조 구현 |
| Week 4 | Sampling, Sorting 구현 |
| Week 5 | Shuffling, Merging 구현 |
| Week 6 | Progress 발표 |
| Week 7 | Fault Tolerance 구현 |
| Week 8 | 최종 테스트 및 마무리 |
| Week 9 | Final 발표 |

---

## Weekly Documents

| Week | Document |
|------|----------|
| Week 1 | [week1_document.md](./meet_log/week1/week1_document.md) |
| Week 2 | [week2_document.md](./meet_log/week2/week2_document.md) |
| Week 3 | [week3_document.md](./meet_log/week3/week3_document.md) |
| Week 4 | [week4_document.md](./meet_log/week4/week4_document.md) |
| Week 5 | [week5_document.md](./meet_log/week5/week5_document.md) |
| Week 6 | [week6_document.md](./meet_log/week6/week6_document.md) |
| Week 7 | [week7_document.md](./meet_log/week7/week7_document.md) |

---

## References

- [gRPC Documentation](https://grpc.io/docs/)
- [ScalaPB Guide](https://scalapb.github.io/)
- [External Sorting Algorithm](https://en.wikipedia.org/wiki/External_sorting)
