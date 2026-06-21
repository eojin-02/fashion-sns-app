-- Fashion-Radar DB Schema v2.0 (설계서 3.2)
CREATE EXTENSION IF NOT EXISTS postgis;

-- 유저 (v1.0 + 인증/설정 컬럼 추가)
CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(100) UNIQUE NOT NULL,
    nickname      VARCHAR(30) NOT NULL,
    avatar_url    VARCHAR(255),
    radar_visible BOOLEAN NOT NULL DEFAULT FALSE,  -- 고스트 모드: 기본 비노출(opt-in)
    birth_date    DATE NOT NULL,                   -- 연령 제한 검증 (만 14세 이상)
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 의류 아이템 (v1.0 유지 + 분석 상태 추가)
CREATE TABLE clothes_item (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    category    VARCHAR(30),
    meta_data   JSONB,
    brand_info  VARCHAR(100),
    image_key   VARCHAR(255) NOT NULL,             -- S3 object key
    scan_status VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING/DONE/FAILED
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_clothes_item_user ON clothes_item(user_id);

-- 데일리 코디 (배열 제거 — 유저당 1개, 매일 갱신)
CREATE TABLE daily_codi (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 코디-아이템 조인 테이블 (item_ids BIGINT[] 폐기 → N:M 정규화)
CREATE TABLE daily_codi_item (
    codi_id    BIGINT NOT NULL REFERENCES daily_codi(id) ON DELETE CASCADE,
    item_id    BIGINT NOT NULL REFERENCES clothes_item(id) ON DELETE CASCADE,
    PRIMARY KEY (codi_id, item_id)
);
CREATE INDEX idx_daily_codi_item_item ON daily_codi_item(item_id); -- "이 자켓이 포함된 코디" 역방향 조회

-- 찜하기
CREATE TABLE item_like (
    user_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    item_id    BIGINT NOT NULL REFERENCES clothes_item(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, item_id)
);
CREATE INDEX idx_item_like_item ON item_like(item_id);

-- 차단 (양방향 비노출은 조회 시 blocker/blocked 양쪽 검사)
CREATE TABLE user_block (
    blocker_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    blocked_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (blocker_id, blocked_id)
);
CREATE INDEX idx_user_block_blocked ON user_block(blocked_id);

-- 신고 (설계서 1.2 신고/차단 MVP — DDL 보강)
CREATE TABLE user_report (
    id          BIGSERIAL PRIMARY KEY,
    reporter_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reported_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reason      VARCHAR(500),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 콘테스트 / 출품 / 투표 (Phase 2 선반영)
CREATE TABLE contest (
    id         BIGSERIAL PRIMARY KEY,
    brand_name VARCHAR(100) NOT NULL,
    title      VARCHAR(200) NOT NULL,
    starts_at  TIMESTAMPTZ NOT NULL,
    ends_at    TIMESTAMPTZ NOT NULL
);

CREATE TABLE contest_entry (
    id         BIGSERIAL PRIMARY KEY,
    contest_id BIGINT NOT NULL REFERENCES contest(id),
    user_id    BIGINT NOT NULL REFERENCES users(id),
    codi_id    BIGINT NOT NULL REFERENCES daily_codi(id),
    UNIQUE (contest_id, user_id)                    -- 1인 1출품
);

CREATE TABLE contest_vote (
    entry_id   BIGINT NOT NULL REFERENCES contest_entry(id) ON DELETE CASCADE,
    voter_id   BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    PRIMARY KEY (entry_id, voter_id)                -- 1인 1표
);

-- 행정동 폴리곤 (방 판정: ST_Contains — 설계서 2.5)
-- 실서비스에선 통계청 행정동 경계 shp를 적재. 아래는 개발용 근사 폴리곤.
CREATE TABLE admin_dong (
    dong_code  VARCHAR(10) PRIMARY KEY,
    name       VARCHAR(50) NOT NULL,
    geom       GEOMETRY(POLYGON, 4326) NOT NULL
);
CREATE INDEX idx_admin_dong_geom ON admin_dong USING GIST (geom);

INSERT INTO admin_dong (dong_code, name, geom) VALUES
('1120011400', '성수동1가', ST_GeomFromText(
    'POLYGON((127.038 37.538, 127.052 37.538, 127.052 37.553, 127.038 37.553, 127.038 37.538))', 4326)),
('1120011500', '성수동2가', ST_GeomFromText(
    'POLYGON((127.052 37.538, 127.068 37.538, 127.068 37.553, 127.052 37.553, 127.052 37.538))', 4326)),
('1117010100', '한남동', ST_GeomFromText(
    'POLYGON((126.995 37.526, 127.012 37.526, 127.012 37.541, 126.995 37.541, 126.995 37.526))', 4326));
