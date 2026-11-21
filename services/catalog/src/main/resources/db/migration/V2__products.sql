CREATE TABLE products (
    id         BIGINT PRIMARY KEY,
    code       TEXT    NOT NULL UNIQUE,
    name       TEXT    NOT NULL,
    placement  TEXT    NOT NULL,
    cpv_price  BIGINT  NOT NULL CHECK (cpv_price > 0),
    min_budget BIGINT  NOT NULL CHECK (min_budget > 0),
    max_days   INT     NOT NULL CHECK (max_days > 0),
    active     BOOLEAN NOT NULL
);

INSERT INTO products (id, code, name, placement, cpv_price, min_budget, max_days, active) VALUES
    (1, 'SEARCH_TOP_7D',   '검색 상단 7일',             'SEARCH_TOP',  50, 100000,  7, TRUE),
    (2, 'HOME_BANNER_14D', '홈 배너 14일',              'HOME_BANNER', 30, 200000, 14, TRUE),
    (3, 'NEARBY_LIST_30D', '내 주변 목록 30일',         'NEARBY_LIST', 20, 300000, 30, TRUE),
    (4, 'EVENT_SPOTLIGHT', '이벤트 스포트라이트(종료)', 'EVENT',       80, 500000,  3, FALSE);
