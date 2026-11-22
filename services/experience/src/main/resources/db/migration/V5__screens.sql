-- 화면 설정(ADR-0009). 바꿀 때마다 리비전을 하나 더 쌓고, 최신 리비전을 내려준다. V1·V3은 db/common이 쓴다.
CREATE TABLE screen_revisions (
    screen_id  TEXT        NOT NULL,
    revision   INT         NOT NULL CHECK (revision > 0),
    layout     JSONB       NOT NULL,
    created_by TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (screen_id, revision)
);

INSERT INTO screen_revisions (screen_id, revision, layout, created_by) VALUES
('home', 1, '{
  "title": "내 광고",
  "sections": [
    {"id": "notice", "type": "Banner", "version": 1,
     "props": {"text": "노출 성과는 1분 안에 반영됩니다. 예산을 다 쓰면 캠페인이 자동으로 멈춥니다.", "tone": "info"}},
    {"id": "settlement", "type": "SettlementSummary", "version": 1, "props": {"title": "이번 달 정산"}},
    {"id": "gauge", "type": "BudgetGauge", "version": 1, "props": {"title": "예산 사용률"},
     "fallbackText": "앱을 업데이트하면 예산 사용률을 볼 수 있어요"},
    {"id": "performance", "type": "CampaignPerformanceList", "version": 2, "props": {"title": "캠페인 성과"}},
    {"id": "cta", "type": "ActionButton", "version": 1, "props": {"label": "광고 상품 보러 가기", "screen": "products"}}
  ]
}', 'seed'),
('products', 1, '{
  "title": "광고 상품",
  "sections": [
    {"id": "refund", "type": "Banner", "version": 1,
     "props": {"text": "집행 전 캠페인은 전액 환불됩니다. 집행 중에는 쓴 금액을 뺀 나머지를 돌려드립니다.", "tone": "warning"}},
    {"id": "products", "type": "ProductList", "version": 1, "props": {"title": "상품 선택"}},
    {"id": "purchase", "type": "PurchaseForm", "version": 1,
     "props": {"title": "구매", "submitLabel": "결제하기", "defaultBudget": 300000, "defaultDays": 7}}
  ]
}', 'seed');
