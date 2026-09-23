# 건강 활동 수집 채널 설계

## 구조
수집 채널이 달라도 저장·멱등 처리·Outbox 적재·일일 목표 갱신은 하나의 유스케이스가 담당한다.

```
[Synthetic 수집기] SyntheticActivityScheduler
        │ 내부 호출
        ▼
HealthActivitySyncUseCase (저장 / 멱등 / Outbox / 진행도)
        ▲
        │ 검증·변환
POST /api/v1/health-activities/sync (HealthActivityController)
        ▲
Gateway (JWT 검증, X-User-Id 재주입)
        ▲
Android 앱 (사용자가 권한을 허용하면 Health Connect에서 읽어 전송)
        ▲
[Health Connect] 기기 내 건강 데이터 보관함 (삼성 헬스 등이 기록)
```

- 인바운드 포트: `HealthActivitySyncUseCase`
- 어댑터: `SyntheticActivityScheduler`(스케줄러), `HealthActivityController`(웹)
- 새 수집 채널이 생겨도 어댑터만 추가하고 유스케이스는 바뀌지 않는다.

## 데이터 출처 (`ActivitySource`)
| 값 | 경로 | 외부 요청 허용 |
|---|---|---|
| `SYNTHETIC` | 서버 내부 수집기 | ❌ (400) |
| `HEALTH_CONNECT` | 앱이 기기의 Health Connect에서 읽어 서버로 전송 | ✅ 기본 외부 채널 |
| `SAMSUNG_HEALTH` | Samsung Health Data SDK 직접 연동 (예약, 미연동) | ❌ (400) |

### Health Connect를 기본 채널로 정한 이유
- 제조사 중립: 삼성 헬스·Fitbit 등 여러 앱의 데이터를 한 곳에서 읽는다. 수집 채널을 추상화한 설계 의도와 맞는다.
- 별도 파트너 승인 없이 연동할 수 있다.
- 걸음 수·활동 시간·칼로리는 Health Connect 표준 데이터 타입으로 충분하다.
- 기존 Samsung Health SDK for Android는 2025-07 지원 중단(2028 종료)되었고, 삼성은 Samsung Health Data SDK로 이전을 안내하고 있다.

## 앱 → 서버 인증
1. 앱은 로그인으로 받은 JWT를 `Authorization: Bearer` 헤더로 보낸다.
2. 게이트웨이는 클라이언트가 보낸 `X-User-Id`/`X-User-Role`을 제거하고, 검증된 JWT의 claim으로 다시 넣는다 (`AuthenticatedUserHeaderFilter`).
3. health-service는 `X-User-Id`만 신뢰한다. 서비스 포트는 외부에 노출하지 않는다는 전제다.

health-service에 앱 전용 인증 코드를 추가할 필요가 없다.

## 요청 규약 (`POST /api/v1/health-activities/sync`)
| 항목 | 규칙 |
|---|---|
| 지표 값 | 증분이 아니라 `measuredAt` 시점까지의 **당일 누적값** |
| 일자 경계 | `measuredAt`을 Asia/Seoul 기준으로 변환한 날짜 |
| `measuredAt` 정밀도 | 초 단위로 절삭해서 저장 (같은 측정이 `01:30:00.789Z`, `01:30:00Z`처럼 다른 정밀도로 와도 같은 행으로 처리) |
| 미래 시점 | 서버 시각 + 5분을 넘으면 400 |
| 재전송 | 같은 `(userId, measuredAt)`이면 값이 같으면 무시, 다르면 갱신 (200) |
| 재시도 | 실패한 요청을 **본문 그대로**(`measuredAt`과 값 모두) 다시 보낸다. 현재 시각으로 `measuredAt`을 새로 만들지 않는다 |
| 재집계 | 값을 다시 집계했다면 **새 `measuredAt`**으로 보낸다. 같은 `measuredAt`에 값만 바꿔 보내지 않는다 |
| 자정 이후 어제 데이터 재집계 | 어제 날짜의 새 `measuredAt`으로 보낸다. 조건은 아래 "자정 이후 어제 데이터 재집계" 참고 |
| 신규 | 201 |

### 재시도와 재집계를 구분하는 이유
같은 `measuredAt`에 다른 값이 오면 서버는 스냅샷을 갱신(UPDATE)하지만 `HealthActivitySynced` 이벤트는 다시 발행하지 않는다.
Outbox `dedup_key`가 `(userId, measuredAt)` 단위이고, Game Service도 `measuredAt <= 마지막 반영 시각`인 이벤트를 무시하기 때문이다.
그래서 값이 바뀐 요청을 같은 `measuredAt`으로 보내면 Game Service는 다음 동기화가 올 때까지 옛 값을 유지하고, 자정 직전이면 그 값이 영구히 남는다.

| 클라이언트 동작 | 서버 처리 | 이벤트 |
|---|---|---|
| 재시도 (본문 그대로) | 동일 스냅샷, 변경 없음 (200) | 없음 (이미 발행됨) |
| 재집계 (새 `measuredAt`) | 새 스냅샷 저장 (201) | 발행 |
| 같은 `measuredAt`에 값만 변경 (규약 위반) | 스냅샷 갱신 (200) | 없음 → Game 반영 지연 |

- 현재는 이 규약을 클라이언트 계약으로만 두고 서버에서 강제하지 않는다. 데이터 출처가 Synthetic뿐이라 규약 위반이 일어나지 않는다.
- 앱을 구현할 때 "같은 `measuredAt`에 다른 값이면 409"로 서버에서 강제하는 방안을 함께 검토한다. 테이블 명세의 "존재하면 UPDATE" 규칙과 클라이언트 재전송 처리가 같이 바뀌어야 한다.
- 값이 줄어드는 정정은 이 규약으로도 이미 완료된 퀘스트와 지급된 XP를 되돌리지 못하므로 별도 논의가 필요하다.

### 자정 이후 어제 데이터 재집계
자정 직전의 정정이 다음 동기화로 흡수되지 않고 어제 값으로 남는 문제는, 앱이 자정 이후 어제 범위를 다시 집계해
**어제 날짜의 새 `measuredAt`**으로 보내면 서버 수정 없이 해소된다.

```
23:50  measuredAt=어제 23:50     9,800  → 이벤트 → 어제 퀘스트 9,800 (미달)
00:10  어제 범위 재집계         10,500
       measuredAt=어제 23:59:00 로 전송
       → Health: activityDate=어제 새 행 저장, 이벤트 발행 (dedup_key가 다름)
       → Game: 어제 퀘스트 조회 → 23:59:00 > 마지막 반영 23:50 → COMPLETED, XP 지급
```

**지켜야 할 조건**
1. `measuredAt`은 어제 `23:59:59 KST`보다 **이른** 시각이어야 한다 (예: `23:59:00`).
   Game의 활성 퀘스트 조회 조건이 `expiredAt > measuredAt`(초과)이고 퀘스트 만료 시각이 `23:59:59`라, 정확히 `23:59:59`면 어제 퀘스트를 찾지 못한다.
2. 어제 마지막으로 보낸 `measuredAt`보다 **늦어야** 한다. 같거나 이르면 `STALE_SNAPSHOT`으로 무시된다.
3. 값이 바뀌었으므로 재집계 규약대로 **새 `measuredAt`**이어야 한다.
4. **Health의 실패 확정 배치(매일 00:05 KST)보다 먼저 도착해야** 일일 목표 달성과 업적에 반영된다. 그 이후 도착하면 Quest는 완료되지만 일일 목표는 실패로 남는다 (아래 주의 참고).

**이 동작이 성립하는 이유 (game-service 기준)**
- `lastAppliedMeasuredAt`은 사용자 단위가 아니라 **퀘스트 단위**다. 오늘 이벤트가 먼저 처리돼도 어제 퀘스트의 기준 시각은 바뀌지 않는다.
- 대상 퀘스트를 현재 시각이 아니라 **`measuredAt`으로** 찾는다 (`findActiveByUserId(userId, measuredAt)`). 어제 날짜 이벤트는 자정 이후 도착해도 어제 퀘스트로 간다.
- 만료된 퀘스트의 `status`를 `EXPIRED`로 바꾸는 배치가 없다. `EXPIRED`는 조회 시 계산값(`displayStatus`)이라 어제 퀘스트가 조회 대상에 남는다.
- Health는 과거 `measuredAt`을 막지 않고(`@NotFutureMeasuredAt`은 미래만 거부) `activityDate`를 `measuredAt`에서 파생하므로 어제 날짜로 저장한다.

**주의**
- 이 동작은 "퀘스트 만료 배치가 없다"는 사실에 기댄다. 자정에 퀘스트를 `EXPIRED`로 바꾸는 배치가 추가되면 늦게 도착한 어제 데이터가 조용히 버려지므로 game-service와 함께 검토해야 한다.
- 늦은 데이터를 받는 기한이 양쪽 서비스 모두 없다. 앱 구현 시 "어제까지만 허용" 같은 과거 쪽 경계를 입력 검증에 두는 것을 검토한다 (`@NotFutureMeasuredAt`과 짝).
- Health의 `DailyGoalFailureScheduler`는 매일 00:05에 어제 일일 목표를 실패로 확정한다(`failedAt`). 이후 도착한 어제 데이터도 `applySync`로 합계·진행도는 갱신된다.
  - `markAllGoalsAchieved`가 `failedAt`도 확인하므로, 00:05 이후에 어제 목표를 채워도 달성 처리와 `DAILY_GOAL_COMPLETED` 발행은 일어나지 않는다. `achievedAt`과 `failedAt`이 동시에 채워지는 모순 상태를 막기 위한 결정이다.
  - 그 결과 00:05 이후 도착한 어제 데이터는 **Game에서는 퀘스트 완료, Health에서는 일일 목표 실패**로 판정이 갈릴 수 있다.
  - 이 차이는 업적에 영향을 준다. game-service의 업적 컨슈머(#132)가 `DAILY_GOAL_COMPLETED`로 `DAILY_GOAL_COUNT`와 `DAILY_GOAL_STREAK`를 세는데, 발행되지 않은 날은 통째로 빠진다. 특히 연속 달성은 하루가 비면 1부터 다시 세므로(`UserAchievement.isConsecutive`) 그동안 쌓인 기록이 사라진다.
  - Health가 늦은 달성을 인정하도록 바꾸더라도 업적에는 반영되지 않는다. `UserAchievement.count`가 `activityDate <= lastCountedDate`면 건너뛰므로, 오늘 이벤트가 먼저 처리된 뒤 도착한 어제 이벤트는 버려진다. 해소하려면 Health Summary와 Achievement 양쪽을 함께 봐야 한다.
  - 판정 기준(실패 확정 시각, 업적의 날짜 가드)은 Health Summary·Achievement 도메인 결정 사항이라 담당자에게 공유했다. Activity 도메인은 결정에 맞춰 위 "지켜야 할 조건 4"의 시각만 갱신한다.
  - ** `publishDeficientGoalEventIfNeeded`가 날짜를 확인하지 않아, 미달인 어제 데이터가 오면 어제 날짜로 퀘스트 제안(Gemini 호출)이 나가고 Game에서 `SNAPSHOT_MISMATCH`로 거부되거나 이미 만료된 퀘스트가 생성된다. `activityDate`가 오늘(KST)이 아니면 제안을 건너뛰도록 했다.

## 범위 밖 (의도적으로 제외)
| 항목 | 제외 이유 |
|---|---|
| Android 앱 (Kotlin, Health Connect 권한·집계 쿼리) | 백엔드 담당 범위 밖, 예상 공수 2~3일 이상 |
| WorkManager 주기 동기화, 배터리 최적화 예외 | 공수 5일 이상. 주기 수집은 Synthetic 수집기로 대체 시연 |
| 백그라운드 읽기 권한, 과거 데이터 백필 | 앱 구현 시 함께 결정 |
| 오프라인 큐·재시도 | 앱 구현 시 함께 결정. 위 재시도·재집계 규약을 지키면 서버는 멱등 처리로 재전송을 받아낼 수 있다 |

## 시연
- Synthetic 수집기: 주기적으로 누적값이 쌓이고 목표 달성 시 퀘스트가 생성되는 흐름
- Postman: 로그인 토큰으로 `HEALTH_CONNECT` 요청을 게이트웨이를 거쳐 보내 같은 흐름으로 이어지는 것(04, 06), `SYNTHETIC` 요청이 400으로 거부되는 것(04-1)
- Swagger UI: 같은 요청을 브라우저에서 직접 보내 확인한다. 게이트웨이를 거치므로 로그인으로 받은 JWT를 Authorize에 입력한 뒤 호출한다
- Postman·Swagger 시연용 사용자는 `rpgym.synthetic.user-ids`에서 제외한다 (누적값이 섞이지 않도록)