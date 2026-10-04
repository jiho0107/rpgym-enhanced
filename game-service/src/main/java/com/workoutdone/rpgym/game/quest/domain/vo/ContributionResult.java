package com.workoutdone.rpgym.game.quest.domain.vo;

// 파티 멤버 한 사람의 기여분을 갱신한 결과다.
// 개인 퀘스트의 판정 결과와 다른 점은, 여기서는 "공유 카운터에 얼마를 더해야 하는가" 를
// 함께 돌려준다는 것이다.
// 멤버 행은 대입으로 갱신하지만 공유 카운터는 증분으로 갱신해야 하기 때문이다.
// 두 값이 다른 방식으로 움직이는 이유는 아래 applied에서 계속.
public sealed interface ContributionResult {
    // 기여분이 반영됐다.
    // counterDelta 는 공유 카운터에 더해야 할 값이다. 이 멤버의 새 기여분에서 이전 기여분을 뺀 것이다.
    // 멤버 행은 대입으로 갱신한다. 건강 데이터가 누적값이라 이벤트가 하나 유실돼도
    // 다음 스냅샷이 알아서 메워주기 때문이다. 증분으로 더해 나가면 유실된 만큼이 영구히 사라진다.
    // 반대로 공유 카운터는 여러 멤버가 동시에 건드리므로 대입으로 쓸 수 없다.
    // 읽어서 더한 값을 쓰는 순간 다른 트랜잭션의 갱신을 덮어쓴다.
    // 그래서 한 문장짜리 증분 UPDATE 로만 갱신하고, 그 문장에 넣을 값이 이것이다.
    // baseline 이 방금 확정된 경우에는 0 이다. 기준만 정하고 기여는 잡지 않는다.
    record Applied(int counterDelta) implements ContributionResult {}

    record Ignored(Reason reason) implements ContributionResult {}

    enum Reason {
        // 이미 반영한 시각 이하의 스냅샷이다. 중복 도착이거나 순서가 뒤집힌 것이다.
        STALE_SNAPSHOT,
        // 누적값이 baseline 보다 작다. 자정 리셋이거나 상류의 데이터 정정이다.
        // 기여분을 되돌리지 않고 무시한다. 다음 스냅샷이 올바른 누적값을 다시 실어온다.
        NEGATIVE_DELTA,
        // 퀘스트 날짜가 아닌 날의 스냅샷이다. 생성 뒤에 늦게 도착한 어제 이벤트가 여기 걸린다.
        // 누적값은 날마다 0 부터 다시 쌓이므로 다른 날 값은 이 퀘스트의 기준과 뺄 수 없다.
        OTHER_DAY
    }
}
