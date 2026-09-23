-- ============================================================
-- V8 -- 파티 업적 (#132)
--
-- 입력은 파티 종료(PartyEnded, Spring 이벤트) 하나다. 파티 퀘스트와 무관하다.
-- "파티가 7일을 채우고 ENDED 된 순간 그 파티에 있던 멤버" 가 완주자다.
--
-- 주인은 파티가 아니라 유저다 (owner_type = USER). 파티는 7일이면 끝나고 재사용되지 않아서
-- "이 파티가 완주했다" 는 업적은 모든 파티가 한 번씩 따고 끝이다. 의미 있는 건
-- "나는 파티를 3번 완주했다" 이므로 scope = PARTY(파티 활동으로 따는 업적) + owner_type = USER 다.
--
-- 중복 방지 테이블을 두지 않는다. 완주 기록의 원본은 party_members 다
-- (파티 종료 시 left_at = parties.ends_at 이 박힌다). 이벤트가 오면 그 유저의 완주 수를
-- 원본에서 다시 세어 current_value 에 절대값으로 덮어쓴다 -- 랭킹이 wallets 를 다시 읽는 것과 같다.
-- 두 번 와도, 순서가 바뀌어도 결과가 같다.
-- ============================================================

-- (1) 조건 종류 추가. V6 CHECK 가 값 목록을 통째로 박아놓은 형태라 다시 쓴다.
ALTER TABLE game_service.achievements
    DROP CONSTRAINT ck_achievements_condition_type;
ALTER TABLE game_service.achievements
    ADD CONSTRAINT ck_achievements_condition_type CHECK (condition_type IN (
        'DAILY_GOAL_COUNT',        -- 일일 목표 누적 달성 횟수
        'DAILY_GOAL_STREAK',       -- 일일 목표 연속 달성 일수
        'PARTY_COMPLETED_COUNT'    -- 파티 완주 누적 횟수 (party_members 에서 다시 센 절대값)
    ));

-- (2) 시드 -- XP 값은 기획 확정 전 임시. 지급 코드는 없다
INSERT INTO game_service.achievements
(achievement_id, code, name, description, scope, condition_type, condition_value, reward_xp)
VALUES
    (gen_random_uuid(), 'PARTY_COMPLETED_FIRST', '첫 파티 완주', '파티를 처음으로 끝까지 함께함', 'PARTY', 'PARTY_COMPLETED_COUNT', 1, 100),
    (gen_random_uuid(), 'PARTY_COMPLETED_3',     '파티 3회 완주', '파티를 3번 끝까지 함께함',       'PARTY', 'PARTY_COMPLETED_COUNT', 3, 300);
